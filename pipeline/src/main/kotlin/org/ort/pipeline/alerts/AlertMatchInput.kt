package org.ort.pipeline.alerts

import org.ort.core.AttributionState

/**
 * Build-plan P31 (FR-ALR-3): everything [AlertMatcher] needs about one transmission, read fresh
 * from the same [org.ort.pipeline.passb.PassBResult]/[org.ort.data.entity.TransmissionEntity] row
 * [org.ort.pipeline.passb.DataPassBResultSink] just wrote — never a Pass A partial (FR-ALR-3,
 * AC-194). [transcriptText] and [stationId] are `null` exactly when Pass B did not produce them
 * (a [org.ort.asrapi.PassBOutcome.Rejected]/[org.ort.asrapi.PassBOutcome.Failed] outcome, or an
 * attribution state that carries no station) — never a fabricated placeholder (constitution I).
 *
 * **Register R-1125:** [stationId] is [org.ort.core.Attribution.stationId] — `null` for
 * `AMBIGUOUS`/`UNKNOWN`, which (register R-1110) is every attribution today without a calibrator.
 * [resolvedCallsign] is different on purpose: the text of Pass B's own top-ranked candidate
 * ([org.ort.lexicon.RankedCandidate.candidate]'s text), present whenever the grammar parsed at
 * least one candidate for this transcript — *regardless* of whether the resolver was willing to
 * assert it as an attribution. A callsign watch matches against this, not [stationId], because
 * "tell me when W7ABC is on" must not go permanently dark just because the system is honest that
 * it cannot yet say *how sure* it is (constitution I: `CONFIRMED` is never restored to make a
 * watch fire again — see [AlertMatcher] and [AlertNotificationContentBuilder] for how the
 * notification states that uncertainty instead of hiding it).
 */
public data class AlertMatchInput(
    val transmissionId: String,
    val attributionState: AttributionState,
    val stationId: String?,
    /** Register R-1125: the top-ranked candidate's callsign, independent of [attributionState] —
     * see this class's own kdoc. `null` exactly when Pass B parsed no candidate at all. */
    val resolvedCallsign: String?,
    val transcriptText: String?,
    val frequencyHz: Long?,
)
