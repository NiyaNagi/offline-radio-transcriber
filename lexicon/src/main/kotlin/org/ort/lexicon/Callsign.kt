package org.ort.lexicon

/**
 * A structurally parsed callsign (FR-LEX-7): a core of `prefix + call-area digit + suffix`,
 * plus any modifiers (`/P`, `/M`, `/MM`, `/AM`, `/QRP`, a bare call-area digit) and an optional
 * secondary prefix for reciprocal forms (`VE7/K7ABC` — secondary `VE7`; `K7ABC/VE7` — the `VE7`
 * lands in [modifiers]).
 */
public data class ParsedCallsign(
    val prefix: String,
    val areaDigit: Char,
    val suffix: String,
    val modifiers: List<String> = emptyList(),
    val secondaryPrefix: String? = null,
) {
    /** The bare core, `prefix + areaDigit + suffix`, e.g. `K7ABC`. */
    public val core: String get() = "$prefix$areaDigit$suffix"

    /** The full callsign as spelled, secondary prefix and modifiers reattached with `/`. */
    public val canonical: String get() = buildString {
        secondaryPrefix?.let { append(it).append('/') }
        append(core)
        modifiers.forEach { append('/').append(it) }
    }
}

/**
 * One ranked callsign hypothesis emitted by [CallsignGrammar.parse]. Only structurally valid
 * candidates — an ITU-allocated prefix (FR-LEX-8) — are emitted, so [allocation] is non-null.
 * Database presence, priors and calibration are **not** applied here; that is P7.
 */
public data class CallsignCandidate(
    val parsed: ParsedCallsign,
    val allocation: ItuAllocation,
    /** Sum of the lattice alternative log-probabilities on the traversed path (higher is better). */
    val acousticLogProb: Float,
    /** Confusion-weighted substitution + deletion penalties accumulated on the path (lower is better). */
    val editPenalty: Float,
    /** The lattice slot indices this parse consumed. */
    val slotSpan: IntRange,
    /**
     * R-320/FR-UI-8 (`Detail-Why.dc.html` section 1), R-182/FR-UI-4 (transcript-highlight span):
     * one [SlotDetail] per index in [slotSpan], in order — the lattice's own per-slot unit, score,
     * kept alternate (or `null`) and transcript character span (or `null`/`null`). Defaults to
     * `emptyList()` so every existing caller/constructor (`:data`, `:pipeline`) keeps compiling
     * unchanged; [CallsignGrammar.parse] is the only real producer and always fills it.
     */
    val slotDetails: List<SlotDetail> = emptyList(),
) {
    /** The callsign string, e.g. `K7ABC` or `VE7/K7ABC/P`. */
    public val text: String get() = parsed.canonical

    /** Path score: acoustic evidence less edit penalty. Ranking key, not a calibrated probability. */
    public val score: Float get() = acousticLogProb - editPenalty
}
