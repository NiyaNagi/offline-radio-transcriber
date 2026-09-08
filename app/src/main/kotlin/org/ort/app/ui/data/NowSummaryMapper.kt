package org.ort.app.ui.data

/**
 * The "Now" home's header counts (`design/canvas/Main.dc.html`: "412 overs · 19 stations · 2
 * bands") — build-plan P14, carried forward by ui-conformance-plan WP4 (R-030/R-033).
 *
 * Extracted into its own file, split out of `TransmissionDetail.kt` (WP5's file otherwise, in
 * full), because `ui-conformance-plan.md` §D's WP4 row names "the `NowSummaryMapper` file under
 * `ui/data/`" as a file WP4 owns on its own. [TransmissionDetail] itself is still WP5's type,
 * imported here as a read-only input the same way [org.ort.app.ui.data.ActivityPatternMapper]
 * (WP8's) is called, not edited, elsewhere in this package.
 */
public data class NowSummaryViewState(val overCount: Int, val stationCount: Int)

public object NowSummaryMapper {

    /**
     * Only the two counts computable honestly from what `:data` records today. Band counting
     * needs a frequency-to-band table no prompt has wired to the reader yet — left out rather
     * than guessed. [stationCount] counts **distinct attributed stations only**: an `UNKNOWN` or
     * `AMBIGUOUS` over contributes to [overCount] but never fabricates a station.
     */
    public fun from(details: List<TransmissionDetail>): NowSummaryViewState = NowSummaryViewState(
        overCount = details.size,
        stationCount = details.mapNotNull { it.attribution.stationId }.toSet().size,
    )
}
