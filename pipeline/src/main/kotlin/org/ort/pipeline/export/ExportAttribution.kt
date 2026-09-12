package org.ort.pipeline.export

import java.util.Locale

/**
 * Register R-1009 (WPX), FR-EXP-4, constitution I (Uncertainty Is Content, NON-NEGOTIABLE):
 * *"Exports SHALL carry attribution confidence. An `INFERRED` attribution SHALL NOT be exported
 * as though `CONFIRMED`."*
 *
 * This is the export-format twin of [org.ort.core.Attribution]/[org.ort.core.AttributionState] —
 * the same closed, four-state set, carried down into the format layer so that **every place a
 * writer could read "the callsign" is a place it is also forced to read the state that came with
 * it**. There is deliberately no `callsign: String` anywhere on [ExportOverRecord] for a writer to
 * read directly and bypass this type: the only way to obtain a callsign is
 * [CallsignKnown.callsign], which exists only on the two subtypes that legitimately name a
 * station. A writer that pattern-matches on [ExportAttribution] with an exhaustive `when` (the
 * only way Kotlin lets it compile, since this is a `sealed interface` with no `else` escape
 * hatch) cannot produce a code path that emits [CallsignKnown.callsign] for an [Ambiguous] or
 * [Unknown] row, because those branches never see a [CallsignKnown] value to read it from.
 *
 * [toCells] is the single function every writer in this package calls to turn an attribution into
 * printable text — it *always* returns the callsign cell and the state tag together, in the same
 * returned value, so a writer cannot staple a `CONFIRMED` tag onto an `INFERRED` callsign (or
 * print a callsign at all for `AMBIGUOUS`/`UNKNOWN`) without deliberately ignoring this function's
 * return value and hand-rolling a second path — which is exactly the "conventionally avoided
 * rather than structurally impossible" shape this register row asked not to be built. See
 * `ExportAttributionTest`'s `FR_EXP_4_*` tests, which try to do exactly that and show the type
 * will not compile a violation, and `AdifExportWriterTest`/`CsvExportWriterTest`/
 * `JsonExportWriterTest`/`TextExportWriterTest`'s own `FR_EXP_4_*` tests, which prove no format's
 * *output* ever tags an inferred callsign as confirmed.
 */
public sealed interface ExportAttribution {

    /** The two states that legitimately name a station — the only subtype from which a writer can
     * read [callsign] at all. [confidence] and [corrected] travel on the same object as the
     * callsign itself, never as a separately-nullable field a writer could read out of step with
     * the state that produced it. */
    public sealed interface CallsignKnown : ExportAttribution {
        public val callsign: String
        public val confidence: Double
        public val corrected: Boolean
    }

    /** `CONFIRMED` means heard and resolved in *this* transmission (constitution I) — never a
     * promoted voice match, never a cross-session inference. This type does not enforce that rule
     * itself (the data layer, [org.ort.core.Attribution], already does); it only ever *carries*
     * whatever state the data layer already decided. */
    public data class Confirmed(
        override val callsign: String,
        override val confidence: Double,
        override val corrected: Boolean,
    ) : CallsignKnown

    public data class Inferred(
        override val callsign: String,
        override val confidence: Double,
        override val corrected: Boolean,
    ) : CallsignKnown

    /** More than one candidate, none resolved — never a callsign, by construction (this object
     * carries none to read). */
    public data object Ambiguous : ExportAttribution

    /** Nothing resolved at all — never a callsign, by construction. */
    public data object Unknown : ExportAttribution
}

/** The one row shape produced by [toCells]: a callsign cell and its state tag, always together —
 * see [ExportAttribution]'s own kdoc for why this pairing is the structural guarantee. */
public data class AttributionCells(
    public val callsignCell: String,
    public val stateTag: String,
    public val confidenceCell: String,
    public val correctedCell: String,
)

/** `CONFIRMED`/`INFERRED`/`AMBIGUOUS`/`UNKNOWN` — the same names
 * [org.ort.core.AttributionState.name] already uses, never a second, differently-spelled vocabulary
 * for the same four states. */
public fun ExportAttribution.stateTag(): String = when (this) {
    is ExportAttribution.Confirmed -> "CONFIRMED"
    is ExportAttribution.Inferred -> "INFERRED"
    ExportAttribution.Ambiguous -> "AMBIGUOUS"
    ExportAttribution.Unknown -> "UNKNOWN"
}

/**
 * The one function every writer in this package calls to render an attribution — see the class
 * kdoc above for why this is what makes FR-EXP-4 structural rather than conventional.
 * [unidentifiedPlaceholder] is what [AttributionCells.callsignCell] reads for [ExportAttribution
 * .Ambiguous]/[ExportAttribution.Unknown] — never a fabricated or guessed callsign (constitution
 * I), and never the empty string by default (a blank cell in a CSV column is easy to misread as
 * "forgot to fill this in"; an explicit word is not).
 */
public fun ExportAttribution.toCells(unidentifiedPlaceholder: String = "UNIDENTIFIED"): AttributionCells = when (this) {
    is ExportAttribution.CallsignKnown -> AttributionCells(
        callsignCell = callsign,
        stateTag = stateTag(),
        confidenceCell = "%.4f".format(Locale.ROOT, confidence),
        correctedCell = corrected.toString(),
    )
    ExportAttribution.Ambiguous, ExportAttribution.Unknown -> AttributionCells(
        callsignCell = unidentifiedPlaceholder,
        stateTag = stateTag(),
        confidenceCell = "",
        correctedCell = "",
    )
}
