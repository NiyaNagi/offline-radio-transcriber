package org.ort.pipeline.export

import org.ort.core.AttributionState
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
     * read [callsign] at all. [confidence]/[corrected] travel on the same object as the callsign
     * itself, never as a separately-nullable field a writer could read out of step with the state
     * that produced it. [confidence] is nullable: [org.ort.core.Attribution.withCorrection] — a
     * real, everyday production write path (a human overriding a callsign,
     * `CorrectionPolling.applyCorrectedAttribution`) — produces a genuine `INFERRED` row with **no**
     * confidence at all (a human's own correction is not a calibrated probability). That is a real
     * category this type must be able to state honestly, not a bug to paper over with an invented
     * number (constitution I).
     */
    public sealed interface CallsignKnown : ExportAttribution {
        public val callsign: String
        public val confidence: Double?
        public val corrected: Boolean
    }

    /** `CONFIRMED` means heard and resolved in *this* transmission (constitution I) — never a
     * promoted voice match, never a cross-session inference. This type does not enforce that rule
     * itself (the data layer, [org.ort.core.Attribution], already does); it only ever *carries*
     * whatever state the data layer already decided. */
    public data class Confirmed(
        override val callsign: String,
        override val confidence: Double?,
        override val corrected: Boolean,
    ) : CallsignKnown

    public data class Inferred(
        override val callsign: String,
        override val confidence: Double?,
        override val corrected: Boolean,
    ) : CallsignKnown

    /** More than one candidate, none resolved — never a callsign, by construction (this object
     * carries none to read). */
    public data object Ambiguous : ExportAttribution

    /** Nothing resolved at all — never a callsign, by construction. */
    public data object Unknown : ExportAttribution

    /**
     * Register R-1039 (halt), constitution I: the data layer states [state] — `CONFIRMED` or
     * `INFERRED`, a station the system genuinely resolved a decision for (never
     * [AttributionState.AMBIGUOUS]/[AttributionState.UNKNOWN], which already have their own,
     * callsign-less branches above and construction of this type with either is refused) — but
     * this row carries no station id to name a callsign with at all.
     * [org.ort.core.Attribution]'s own factory functions ([org.ort.core.Attribution.confirmed]/
     * [org.ort.core.Attribution.inferred]/[org.ort.core.Attribution.withCorrection]) all require a
     * non-blank station id for exactly these two states, so reaching this branch means a
     * `transmission` row was written outside that contract (a raw DAO update, a migration, a
     * corrupted restore) — a real data-integrity defect, worth stating rather than hiding. [reason]
     * says why.
     *
     * Never [CallsignKnown]: a writer cannot read a callsign out of this branch — the same
     * structural guarantee this file's own class kdoc already describes; this is "represent a real
     * inconsistency honestly," never a third way to carry a callsign. Never folded into [Ambiguous]/
     * [Unknown]: those mean the system itself never resolved a station at all, and collapsing this
     * case into either would hide a confirmation or an inference that genuinely happened — exactly
     * the "quietly relabelled UNKNOWN" constitution I forbids.
     */
    public data class UnresolvedCallsign(public val state: AttributionState, public val reason: String) :
        ExportAttribution {
        init {
            require(state == AttributionState.CONFIRMED || state == AttributionState.INFERRED) {
                "UnresolvedCallsign is only for CONFIRMED/INFERRED — AMBIGUOUS and UNKNOWN already " +
                    "have their own, dedicated callsign-less branches (ExportAttribution.Ambiguous/.Unknown)"
            }
        }
    }
}

/** The one row shape produced by [toCells]: a callsign cell and its state tag, always together —
 * see [ExportAttribution]'s own kdoc for why this pairing is the structural guarantee. [noteCell]
 * is empty except for [ExportAttribution.UnresolvedCallsign], where it carries
 * [ExportAttribution.UnresolvedCallsign.reason] — the one case where the state is real but the
 * callsign is honestly absent, so a reader needs the reason alongside it (constitution I). */
public data class AttributionCells(
    public val callsignCell: String,
    public val stateTag: String,
    public val confidenceCell: String,
    public val correctedCell: String,
    public val noteCell: String = "",
)

/** `CONFIRMED`/`INFERRED`/`AMBIGUOUS`/`UNKNOWN` — the same names
 * [org.ort.core.AttributionState.name] already uses, never a second, differently-spelled vocabulary
 * for the same four states. [ExportAttribution.UnresolvedCallsign] reads its own real
 * [ExportAttribution.UnresolvedCallsign.state] the identical way — never a fifth, invented tag. */
public fun ExportAttribution.stateTag(): String = when (this) {
    is ExportAttribution.Confirmed -> "CONFIRMED"
    is ExportAttribution.Inferred -> "INFERRED"
    is ExportAttribution.UnresolvedCallsign -> state.name
    ExportAttribution.Ambiguous -> "AMBIGUOUS"
    ExportAttribution.Unknown -> "UNKNOWN"
}

/**
 * The one function every writer in this package calls to render an attribution — see the class
 * kdoc above for why this is what makes FR-EXP-4 structural rather than conventional.
 * [unidentifiedPlaceholder] is what [AttributionCells.callsignCell] reads for [ExportAttribution
 * .Ambiguous]/[ExportAttribution.Unknown] — the system itself never resolved a station.
 * [unresolvedPlaceholder] is a *different* word for [ExportAttribution.UnresolvedCallsign] —
 * deliberately not [unidentifiedPlaceholder]: the system did resolve a station here, this row just
 * cannot name it, and conflating the two placeholders would misstate which of those happened
 * (constitution I). Neither is ever a fabricated or guessed callsign, and neither is the empty
 * string by default (a blank cell in a CSV column is easy to misread as "forgot to fill this in";
 * an explicit word is not).
 */
public fun ExportAttribution.toCells(
    unidentifiedPlaceholder: String = "UNIDENTIFIED",
    unresolvedPlaceholder: String = "CALLSIGN UNAVAILABLE",
): AttributionCells = when (this) {
    is ExportAttribution.CallsignKnown -> AttributionCells(
        callsignCell = callsign,
        stateTag = stateTag(),
        confidenceCell = confidence?.let { "%.4f".format(Locale.ROOT, it) } ?: "",
        correctedCell = corrected.toString(),
    )
    is ExportAttribution.UnresolvedCallsign -> AttributionCells(
        callsignCell = unresolvedPlaceholder,
        stateTag = stateTag(),
        confidenceCell = "",
        correctedCell = "",
        noteCell = reason,
    )
    ExportAttribution.Ambiguous, ExportAttribution.Unknown -> AttributionCells(
        callsignCell = unidentifiedPlaceholder,
        stateTag = stateTag(),
        confidenceCell = "",
        correctedCell = "",
    )
}
