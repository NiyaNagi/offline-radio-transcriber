package org.ort.app.transmissions

import org.ort.core.Attribution
import org.ort.core.AttributionState

/**
 * One transmission's minimal display facts (build-plan P11): this is the first point in the
 * project where an [Attribution] reaches a screen (M3's text-derived path, technical design
 * §9.2), so it is deliberately independent of `:data`'s Room entity — a `TransmissionEntity`
 * pulls in a database dependency this display concern does not need.
 */
public data class TransmissionRow(val id: String, val transcript: String, val attribution: Attribution)

/**
 * The transmission list's render-ready row (FR-A11Y-1, constitution VII's accessibility floor):
 * the four attribution states must be distinguishable **without colour**. [attributionLabel]
 * carries the state's name in plain text plus a distinct ASCII marker per state — a marker
 * survives greyscale, a screen reader, and a printed transcript equally, which a colour alone
 * does not.
 */
public data class TransmissionListRowViewState(
    val id: String,
    val transcript: String,
    val attributionLabel: String,
    /** The resolved station, shown only when the state carries one (`CONFIRMED`/`INFERRED`). */
    val stationLabel: String?,
)

public object TransmissionListViewStateMapper {

    public fun from(row: TransmissionRow): TransmissionListRowViewState = TransmissionListRowViewState(
        id = row.id,
        transcript = row.transcript,
        attributionLabel = label(row.attribution),
        stationLabel = row.attribution.stationId?.let { "Station: $it" },
    )

    /**
     * A plain-text label naming both the state and a state-specific marker (FR-A11Y-1). The
     * marker is redundant with the name by design — the name alone already satisfies "not
     * colour-only", but a short marker is what a compact list row shows before the full word,
     * and it must itself differ per state (an icon that is the same for two states would fail
     * the same accessibility floor colour alone would).
     */
    private fun label(attribution: Attribution): String {
        val marker = when (attribution.state) {
            AttributionState.CONFIRMED -> "✓" // check mark
            AttributionState.INFERRED -> "~"
            AttributionState.AMBIGUOUS -> "?"
            AttributionState.UNKNOWN -> "—" // em dash
        }
        return "$marker ${attribution.state.name}"
    }
}
