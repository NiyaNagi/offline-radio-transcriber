package org.ort.lexicon

/** Tunables for the grammar beam search. Not wired to `ResolvedConfig` yet — priors are P7. */
public data class GrammarConfig(
    val beamWidth: Int = 32,
    val topK: Int = 10,
    val maxDeletions: Int = 2,
    val deletionCost: Float = 0.8f,
    val maxSuffixLen: Int = 4,
    val maxGroups: Int = 4,
)

/**
 * The callsign grammar as a beam search over lattice slots against the ITU-pruned finite-state
 * acceptor for `prefix + digit + suffix` (technical design §9.2).
 *
 * Two rules from the constitution shape it:
 *  - **Structural validity against the ITU table is the only hard filter** (FR-LEX-8). A parse
 *    reaching an accepting state with an allocated prefix is a candidate even with zero
 *    database hits (AC-10); an unallocated prefix yields no candidate, so it can never reach
 *    `CONFIRMED` (AC-11).
 *  - **Edit costs are confusion-weighted** (FR-LEX-10), so a near-miss along a plausible
 *    acoustic axis (`B`↔`D`) outranks one along an implausible axis.
 */
public class CallsignGrammar(
    private val itu: ItuPrefixTable,
    private val confusion: ConfusionCostMatrix,
    private val config: GrammarConfig = GrammarConfig(),
) {
    private data class Path(
        val units: List<PhoneticUnit>,
        val acoustic: Float,
        val penalty: Float,
        val deletions: Int,
    ) {
        val score: Float get() = acoustic - penalty
    }

    /** Ranked, structurally valid candidates for [lattice], best first, at most [GrammarConfig.topK]. */
    public fun parse(lattice: PhoneticLattice): List<CallsignCandidate> {
        if (lattice.isEmpty) return emptyList()
        var frontier = listOf(Path(emptyList(), 0f, 0f, 0))
        for (slot in lattice.slots) {
            frontier = frontier
                .flatMap { expand(it, slot) }
                .sortedByDescending { it.score }
                .take(config.beamWidth)
        }
        val span = 0 until lattice.slots.size
        val slotDetails = slotDetailsFor(lattice, span)
        return frontier
            .mapNotNull { path -> toCandidate(path, span, slotDetails) }
            .groupBy { it.text }
            .map { (_, group) -> group.maxByOrNull { it.score } ?: group.first() }
            .sortedWith(compareByDescending<CallsignCandidate> { it.score }.thenBy { it.text })
            .take(config.topK)
    }

    private fun expand(path: Path, slot: LatticeSlot): List<Path> {
        val out = ArrayList<Path>()
        for (alt in slot.alts) {
            for (v in listOf(alt.unit) + confusion.confusableWith(alt.unit)) {
                out += path.copy(
                    units = path.units + v,
                    acoustic = path.acoustic + alt.logProb,
                    penalty = path.penalty + confusion.substitutionCost(alt.unit, v),
                )
            }
        }
        if (path.deletions < config.maxDeletions) {
            out += path.copy(
                penalty = path.penalty + config.deletionCost,
                deletions = path.deletions + 1,
            )
        }
        return out
    }

    private fun toCandidate(path: Path, span: IntRange, slotDetails: List<SlotDetail>): CallsignCandidate? {
        val parsed = parseCallsign(path.units) ?: return null
        val allocation = itu.allocationFor(parsed.core)
            ?: parsed.secondaryPrefix?.let { itu.allocationFor(it) }
            ?: return null
        return CallsignCandidate(parsed, allocation, path.acoustic, path.penalty, span, slotDetails)
    }

    /**
     * R-320/FR-UI-8, R-182/FR-UI-4: one [SlotDetail] per [span] index, built straight from
     * [lattice]'s own [LatticeSlot.top]/[LatticeSlot.runnerUp]/char-span — the same real per-slot
     * data every [expand] call already reads from [LatticeSlot.alts], now surfaced on the result
     * instead of only feeding the aggregate acoustic/penalty score. Identical across every
     * candidate `parse` returns for the same [lattice] (it is a fact about the lattice, not about
     * any one candidate's parsed text) — computed once per call, not once per candidate.
     */
    private fun slotDetailsFor(lattice: PhoneticLattice, span: IntRange): List<SlotDetail> = span.map { i ->
        val slot = lattice.slots[i]
        SlotDetail(
            index = i,
            unit = slot.top.unit.symbol.toString(),
            score = slot.top.logProb.toDouble(),
            keptAlternate = slot.runnerUp?.unit?.symbol?.toString(),
            charStart = slot.charStart,
            charEnd = slot.charEnd,
        )
    }

    // --- the finite-state acceptor, expressed as small total functions -----------------------

    private fun parseCallsign(units: List<PhoneticUnit>): ParsedCallsign? {
        val groups = splitOnStroke(units)
        if (groups.isEmpty() || groups.any { it.isEmpty() } || groups.size > config.maxGroups) return null
        return groups.indices.firstNotNullOfOrNull { coreIdx -> tryParseAround(groups, coreIdx) }
    }

    /** Attempt a parse with `groups[coreIdx]` as the core: <=1 secondary prefix before, modifiers after. */
    private fun tryParseAround(groups: List<List<PhoneticUnit>>, coreIdx: Int): ParsedCallsign? {
        val core = parseCore(groups[coreIdx]) ?: return null
        val before = groups.subList(0, coreIdx)
        if (before.size > 1) return null
        val secondary = if (before.isEmpty()) null else (parsePrefixToken(before[0]) ?: return null)
        val mods = groups.subList(coreIdx + 1, groups.size).map { parseTail(it) ?: return null }
        return ParsedCallsign(core.first, core.second, core.third, mods, secondary)
    }

    private fun splitOnStroke(units: List<PhoneticUnit>): List<List<PhoneticUnit>> {
        val groups = ArrayList<List<PhoneticUnit>>()
        var current = ArrayList<PhoneticUnit>()
        for (u in units) {
            if (u.isSeparator) {
                groups += current
                current = ArrayList()
            } else {
                current += u
            }
        }
        groups += current
        return groups
    }

    /** `prefix (1-2 units, >=1 letter, <=1 digit) + area digit + suffix (1..maxSuffixLen letters)`. */
    private fun parseCore(g: List<PhoneticUnit>): Triple<String, Char, String>? =
        listOf(2, 1).firstNotNullOfOrNull { plen -> coreWithPrefixLen(g, plen) }

    private fun coreWithPrefixLen(g: List<PhoneticUnit>, plen: Int): Triple<String, Char, String>? {
        if (g.size < plen + 2) return null
        val prefix = g.subList(0, plen)
        val area = g[plen]
        val suffix = g.subList(plen + 1, g.size)
        if (!area.isDigit) return null
        if (suffix.isEmpty() || suffix.size > config.maxSuffixLen || suffix.any { !it.isLetter }) return null
        val prefixWellFormed = prefix.none { it.isSeparator } &&
            prefix.any { it.isLetter } &&
            prefix.count { it.isDigit } <= 1
        if (!prefixWellFormed) return null
        return Triple(prefix.str(), area.symbol, suffix.str())
    }

    private fun parsePrefixToken(g: List<PhoneticUnit>): String? {
        if (g.isEmpty() || g.size > 3) return null
        if (g.any { it.isSeparator } || g.none { it.isLetter }) return null
        return g.str()
    }

    private fun parseTail(g: List<PhoneticUnit>): String? {
        if (g.isEmpty() || g.any { it.isSeparator }) return null
        return g.str()
    }

    private fun List<PhoneticUnit>.str(): String = joinToString("") { it.symbol.toString() }
}
