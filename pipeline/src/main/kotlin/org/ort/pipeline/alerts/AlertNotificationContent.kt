package org.ort.pipeline.alerts

import org.ort.core.AttributionState

/**
 * Build-plan P31 (FR-ALR-5, AC-196): the notification's title/text, built as plain data — no
 * `android.app.Notification` here — the same "content is a pure function, tested without
 * Robolectric" split `org.ort.capture.android.service.CaptureNotificationBuilder` already
 * establishes for the capture notification.
 */
public data class AlertNotificationContent(val title: String, val text: String)

/**
 * FR-ALR-5, constitution I: "an attribution without its confidence state is a bug." This is the
 * one place that wording is decided, so no caller can accidentally phrase an `INFERRED` callsign
 * match as though it were heard.
 *
 * - A [AlertWatch.Callsign] watch **always** names the attribution state explicitly (FR-ALR-5's
 *   own text): `CONFIRMED` reads "heard now"; every other state reads a hedged "possible" —
 *   `INFERRED`, `AMBIGUOUS` and `UNKNOWN` are all one honest, non-asserting phrasing, since none of
 *   the three means "heard in this transmission" (constitution I: `CONFIRMED` is the only state
 *   that does).
 * - A [AlertWatch.Keyword]/[AlertWatch.Frequency] watch's *match* is the keyword or the frequency,
 *   not a callsign — but when the transmission does carry a resolved station, its attribution
 *   state is still named on the second line, for the identical reason: never let a reader infer
 *   "heard" from a bare callsign string alone.
 */
public object AlertNotificationContentBuilder {

    public fun build(firing: AlertFiring): AlertNotificationContent {
        val watch = firing.watch
        val input = firing.input
        val countSuffix = if (firing.occurrenceCount > 1) " · ${firing.occurrenceCount}×" else ""
        return when (watch) {
            is AlertWatch.Callsign -> AlertNotificationContent(
                title = "${callsignHeadline(input.attributionState)}: ${watch.callsign}$countSuffix",
                text = callsignBody(input.attributionState),
            )
            is AlertWatch.Keyword -> AlertNotificationContent(
                title = "Keyword \"${watch.keyword}\" heard$countSuffix",
                text = stationLine(input),
            )
            is AlertWatch.Frequency -> AlertNotificationContent(
                title = "Activity on ${watch.displayValue()}$countSuffix",
                text = stationLine(input),
            )
        }
    }

    private fun callsignHeadline(state: AttributionState): String = when (state) {
        AttributionState.CONFIRMED -> "Heard now"
        AttributionState.INFERRED, AttributionState.AMBIGUOUS, AttributionState.UNKNOWN -> "Possible match"
    }

    /** Never the word "heard" outside the [AttributionState.CONFIRMED] branch — FR-ALR-5's own
     * text, verbatim: "SHALL NEVER present an INFERRED match as though the callsign were heard." */
    private fun callsignBody(state: AttributionState): String = when (state) {
        AttributionState.CONFIRMED -> "Confirmed — heard in this transmission."
        AttributionState.INFERRED ->
            "Inferred from context or a voice match — not confirmed heard in this transmission."
        AttributionState.AMBIGUOUS -> "Ambiguous — more than one candidate, not confirmed heard in this transmission."
        AttributionState.UNKNOWN -> "Unresolved — not confirmed heard in this transmission."
    }

    private fun stationLine(input: AlertMatchInput): String {
        val station = input.stationId ?: return "No station resolved for this transmission yet."
        return "$station · ${callsignHeadline(input.attributionState).lowercase()}"
    }
}
