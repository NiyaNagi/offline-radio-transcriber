package org.ort.pipeline.passb

import org.ort.lexicon.CallsignGrammar
import org.ort.lexicon.ConfusionCostMatrix
import org.ort.lexicon.ItuPrefixTable
import org.ort.lexicon.LatticeSource
import org.ort.lexicon.PhoneticLattice
import org.ort.lexicon.PhoneticUnit

/**
 * One grammar-valid match for a typed fragment (audit F-018, FR-UI-6, Q8/D32): either a complete,
 * structurally valid callsign or a live ITU prefix allocation the fragment could still grow into.
 * Plain strings, not `:lexicon`'s [org.ort.lexicon.ItuAllocation] — `:app` has no compile edge to
 * `:lexicon` (module graph, constitution VII) and must not gain one just to render a lexicon
 * search result. [DataPassBResultSink] already persists candidates the identical way
 * (`ituPrefix`/`ituCountry` as plain columns), so this mirrors an established pattern rather than
 * inventing a new one.
 */
public data class LexiconMatch(val callsign: String, val ituPrefix: String, val ituCountry: String, val ituIso: String)

/**
 * Q8's "search the lexicon" correction tier (FR-UI-6, D32, audit F-018): typed-fragment search
 * over the bundled ITU prefix allocation table and the callsign grammar `PassBFactory` already
 * constructs. This is deliberately **not** the ranked-candidate resolver ([CallsignGrammar.parse]
 * over an *acoustic* lattice is Pass B's own path, already reachable through the "pick a resolved
 * candidate" tier) and **not** a spoken-form lookup ([org.ort.lexicon.VariantTable] resolves
 * acoustic input, not text an operator types with their fingers).
 *
 * **AC-11 holds structurally, not by assertion**: every match [RealLexiconLookup] returns is
 * either [CallsignGrammar.parse]'s own output — which itself only ever emits a candidate carrying
 * an [org.ort.lexicon.ItuAllocation] — or drawn directly from [ItuPrefixTable.allocations]. There
 * is no code path here that can fabricate a prefix the table does not carry.
 */
public interface LexiconLookup {
    /**
     * Grammar-valid callsigns and/or live prefix allocations matching [prefixOrPartial], best
     * (most complete) match first, at most [limit] entries. Never throws on an unparseable
     * fragment or an unknown prefix — that is simply zero matches, not a failure (this is a live
     * search box, not a validator the operator must satisfy before typing).
     */
    public suspend fun search(prefixOrPartial: String, limit: Int): List<LexiconMatch>
}

/**
 * The real implementation, over the same bundled [ItuPrefixTable]/[CallsignGrammar] tables
 * [PassBFactory] already constructs for Pass B itself — one lexicon, not two drifting copies.
 */
public class RealLexiconLookup(private val ituTable: ItuPrefixTable, private val grammar: CallsignGrammar) :
    LexiconLookup {

    override suspend fun search(prefixOrPartial: String, limit: Int): List<LexiconMatch> {
        val normalized = prefixOrPartial.trim().uppercase()
        if (normalized.isEmpty() || limit <= 0) return emptyList()

        // Preserves insertion order and de-dupes an entry that would otherwise appear from both
        // paths below (an exact callsign whose core also happens to equal an allocated prefix).
        val matches = LinkedHashMap<String, LexiconMatch>()

        // Only a fragment composed entirely of phonetic-unit characters (A-Z, 0-9, "/") is a
        // plausible callsign fragment at all — [PhoneticUnit.spell] is the same closed alphabet
        // check the grammar itself is built on. A fragment containing anything else (a stray
        // symbol, e.g. a fat-fingered "@") is rejected here rather than let
        // [ItuPrefixTable.allocationFor]'s character-by-character trie walk silently match on
        // just its valid leading substring and misreport the *whole* fragment as inside a block.
        val units = runCatching { PhoneticUnit.spell(normalized) }.getOrNull()

        // A complete, structurally valid callsign: exact text match only — never a confusion-based
        // near-miss. This is a deliberate typed search, not acoustic resolution, so a fragment that
        // does not literally spell a valid callsign is not "close enough".
        units?.let {
            val lattice = PhoneticLattice.ofUnits(it, LatticeSource.TEXT_DERIVED)
            grammar.parse(lattice)
                .filter { candidate -> candidate.text == normalized }
                .forEach { candidate ->
                    matches.putIfAbsent(
                        candidate.text,
                        LexiconMatch(
                            callsign = candidate.text,
                            ituPrefix = candidate.allocation.prefix,
                            ituCountry = candidate.allocation.entity,
                            ituIso = candidate.allocation.iso,
                        ),
                    )
                }
        }

        // The fragment itself already lies inside a known allocated block — the "still typing a
        // callsign" case, e.g. "K7" on the way to "K7ABC": [ItuPrefixTable.allocationFor] finds
        // "K" as the longest allocated prefix "K7" starts with.
        if (units != null) {
            ituTable.allocationFor(normalized)?.let { allocation ->
                matches.putIfAbsent(
                    normalized,
                    LexiconMatch(
                        callsign = normalized,
                        ituPrefix = allocation.prefix,
                        ituCountry = allocation.entity,
                        ituIso = allocation.iso,
                    ),
                )
            }
        }

        // Allocated prefixes the fragment could grow *into* — the "just started typing" case,
        // e.g. "K" surfacing "K", "KH6" and "KL7" as the allocated blocks that begin with it.
        ituTable.allocations
            .asSequence()
            .filter { it.prefix.startsWith(normalized) }
            .forEach { allocation ->
                matches.putIfAbsent(
                    allocation.prefix,
                    LexiconMatch(
                        callsign = allocation.prefix,
                        ituPrefix = allocation.prefix,
                        ituCountry = allocation.entity,
                        ituIso = allocation.iso,
                    ),
                )
            }

        return matches.values.take(limit)
    }

    public companion object {
        /** The real lookup over the same bundled tables [PassBFactory] constructs for Pass B. */
        public fun bundled(): RealLexiconLookup {
            val ituTable = ItuPrefixTable.bundled()
            return RealLexiconLookup(ituTable, CallsignGrammar(ituTable, ConfusionCostMatrix.bundled()))
        }
    }
}
