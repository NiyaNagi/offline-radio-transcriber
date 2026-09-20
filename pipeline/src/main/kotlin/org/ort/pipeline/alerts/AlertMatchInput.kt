package org.ort.pipeline.alerts

import org.ort.core.AttributionState

/**
 * Build-plan P31 (FR-ALR-3): everything [AlertMatcher] needs about one transmission, read fresh
 * from the same [org.ort.pipeline.passb.PassBResult]/[org.ort.data.entity.TransmissionEntity] row
 * [org.ort.pipeline.passb.DataPassBResultSink] just wrote — never a Pass A partial (FR-ALR-3,
 * AC-194). [transcriptText] and [stationId] are `null` exactly when Pass B did not produce them
 * (a [org.ort.asrapi.PassBOutcome.Rejected]/[org.ort.asrapi.PassBOutcome.Failed] outcome, or an
 * attribution state that carries no station) — never a fabricated placeholder (constitution I).
 */
public data class AlertMatchInput(
    val transmissionId: String,
    val attributionState: AttributionState,
    val stationId: String?,
    val transcriptText: String?,
    val frequencyHz: Long?,
)
