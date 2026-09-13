package org.ort.app.ui.recordings

import org.ort.app.ui.data.ReaderTransmissionViewStateMapper
import org.ort.app.ui.improve.Plurals
import org.ort.core.Attribution
import org.ort.core.AttributionState
import org.ort.core.TransmissionId
import org.ort.core.TransmissionState
import org.ort.core.capture.CaptureMode
import org.ort.data.entity.CaptureGapCause
import org.ort.data.entity.CaptureGapEntity
import org.ort.data.entity.TransmissionEntity
import org.ort.data.entity.TransmissionLabelEntity
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** [RecordingSessionViewStateMapper.map]'s own per-over input — every fact a database read has
 * already resolved for one transmission, bundled so [map] itself stays a plain function of data
 * rather than a database client (detekt `LongParameterList`, the same "bundle the request" shape
 * [RecordingsBudgetInputs] already uses). */
public data class RecordingSessionOverInput(
    public val transmission: TransmissionEntity,
    public val transcriptText: String?,
    /** The real, resolved callsign — [org.ort.data.entity.StationEntity.callsign], falling back to
     * [org.ort.data.entity.TransmissionEntity.stationId] itself only when no station row exists —
     * the same fallback [org.ort.pipeline.archive.SessionAudioExport.buildOverManifest] already
     * uses (`null` when [org.ort.data.entity.TransmissionEntity.stationId] itself is `null`). */
    public val callsign: String?,
    public val label: TransmissionLabelEntity?,
    /** [org.ort.data.entity.WorkQueueItemEntity.attemptCount] of this transmission's own currently-
     * `FAILED` item, if one exists — `null` when none does (a `FAILED` [TransmissionState] whose
     * item has already been requeued and is `READY`/`LEASED` again, or a stale read). */
    public val failedAttemptCount: Int?,
    /** A real `File.isFile` check against this transmission's own retained audio path — never
     * assumed from its processing state (constitution I: audio retention and processing outcome
     * are two different facts). */
    public val hasAudioFile: Boolean,
)

public data class RecordingSessionMapperInput(
    public val sessionId: String,
    public val startedAtMillis: Long,
    public val endedAtMillis: Long?,
    /** `null` on a pre-schema-v7 session this fact was never tracked for (constitution I: honest
     * omission, never guessed). */
    public val captureMode: CaptureMode?,
    public val overs: List<RecordingSessionOverInput>,
    public val gaps: List<CaptureGapEntity>,
    public val failedCount: Int,
    public val stationCount: Int,
    /** [org.ort.pipeline.archive.SessionAudioDeletionService.preview]'s own real, current bytes for
     * `SessionAudioTarget.BOTH` — never an estimate (constitution VI). */
    public val deleteFreesBytes: Long,
    public val exportAvailable: Boolean,
    /** The transport controller's own loaded transmission id, if it belongs to this session — real
     * only then (constitution I: this screen is not live, so a playhead is never fabricated). */
    public val playingTransmissionId: String?,
    public val nowMillis: Long,
)

/**
 * `Recording-Session.dc.html` (RC02): the pure decision behind [RecordingSessionPolling.state] —
 * pulled out to a plain function of already-resolved data (the same
 * [RecordingsViewStateMapper]/[org.ort.app.ui.navigation.resolveTransportBarState] shape this
 * codebase already establishes for "the decision, not the database read, is what gets tested")
 * so every rule here is directly unit-testable with constructed fixtures.
 */
public object RecordingSessionViewStateMapper {

    private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US).withZone(ZoneOffset.UTC)
    private val HOUR_FORMAT = DateTimeFormatter.ofPattern("HH", Locale.US).withZone(ZoneOffset.UTC)
    private const val AXIS_LABEL_COUNT = 5

    public fun map(input: RecordingSessionMapperInput): RecordingSessionViewState {
        val sessionEnd = input.endedAtMillis ?: input.nowMillis
        val durationMillis = (sessionEnd - input.startedAtMillis).coerceAtLeast(1L)

        val overs = input.overs.sortedBy { it.transmission.startedAtUtc }
        val oversById = overs.associateBy { it.transmission.id }

        val timedOverRows = overs.map { it.transmission.startedAtUtc to overRow(it, oversById) }
        val timedGapRows = input.gaps.map { it.startedAt to gapRow(it) }
        val rows = (timedOverRows + timedGapRows).sortedBy { (startedAt, _) -> startedAt }.map { (_, row) -> row }

        val coverage = RecordingSessionCoverageViewState(
            ticks = overs.map { coverageTick(it.transmission, input.startedAtMillis, durationMillis) },
            gaps = input.gaps.map { coverageGap(it, input.startedAtMillis, durationMillis, sessionEnd) },
            playheadFraction = input.playingTransmissionId
                ?.let { id -> oversById[id]?.transmission }
                ?.let { fractionOf(it.startedAtUtc, input.startedAtMillis, durationMillis) },
            axisLabels = axisLabels(input.startedAtMillis, sessionEnd),
        )

        return RecordingSessionViewState(
            sessionId = input.sessionId,
            header = RecordingSessionHeaderViewState(
                dateLabel = DAY_FORMAT.format(Instant.ofEpochMilli(input.startedAtMillis)),
                timeRangeLabel = timeRangeLabel(input.startedAtMillis, input.endedAtMillis),
                durationLabel = hoursMinutesLabel(durationMillis),
                modeLabel = input.captureMode?.let { modeLabel(it) },
                countsLabel = countsLabel(overs.size, input.stationCount, input.gaps.size, input.failedCount),
            ),
            coverage = coverage,
            rows = rows,
            deleteFreesBytes = input.deleteFreesBytes,
            exportAvailable = input.exportAvailable,
        )
    }

    private fun modeLabel(mode: CaptureMode): String = if (mode == CaptureMode.LOCAL_MICROPHONE) {
        "${mode.operatorLabel.lowercase(Locale.US)} · room audio"
    } else {
        mode.operatorLabel.lowercase(Locale.US)
    }

    private fun countsLabel(overCount: Int, stationCount: Int, gapCount: Int, failedCount: Int): String = buildString {
        append(Plurals.count(overCount, "over"))
        append(" · ")
        append(Plurals.count(stationCount, "station"))
        if (gapCount > 0) {
            append(" · ")
            append(Plurals.count(gapCount, "gap"))
        }
        if (failedCount > 0) {
            append(" · ")
            append("$failedCount failed")
        }
    }

    private fun timeRangeLabel(startedAt: Long, endedAt: Long?): String {
        val start = ReaderTransmissionViewStateMapper.timeLabel(startedAt).substringBeforeLast(":")
        val end = endedAt?.let { ReaderTransmissionViewStateMapper.timeLabel(it).substringBeforeLast(":") } ?: "now"
        return "$start – $end"
    }

    private fun hoursMinutesLabel(elapsedMillis: Long): String {
        val minutes = (elapsedMillis / 60_000).coerceAtLeast(0)
        return "${minutes / 60} h ${minutes % 60} m"
    }

    private fun fractionOf(atMillis: Long, sessionStart: Long, durationMillis: Long): Float =
        (((atMillis - sessionStart).toFloat()) / durationMillis.toFloat()).coerceIn(0f, 1f)

    private fun coverageTick(
        transmission: TransmissionEntity,
        sessionStart: Long,
        durationMillis: Long,
    ): RecordingSessionCoverageTick {
        val end = transmission.endedAtUtc ?: (transmission.startedAtUtc + transmission.durationMs)
        return RecordingSessionCoverageTick(
            fractionStart = fractionOf(transmission.startedAtUtc, sessionStart, durationMillis),
            fractionEnd = fractionOf(end, sessionStart, durationMillis),
            failed = transmission.processingState == TransmissionState.FAILED,
        )
    }

    private fun coverageGap(
        gap: CaptureGapEntity,
        sessionStart: Long,
        durationMillis: Long,
        sessionEnd: Long,
    ): RecordingSessionCoverageGap = RecordingSessionCoverageGap(
        fractionStart = fractionOf(gap.startedAt, sessionStart, durationMillis),
        fractionEnd = fractionOf(gap.endedAt ?: sessionEnd, sessionStart, durationMillis),
    )

    private fun axisLabels(sessionStart: Long, sessionEnd: Long): List<String> {
        if (sessionEnd <= sessionStart) return listOf(HOUR_FORMAT.format(Instant.ofEpochMilli(sessionStart)))
        val span = sessionEnd - sessionStart
        return (0 until AXIS_LABEL_COUNT).map { index ->
            val at = sessionStart + span * index / (AXIS_LABEL_COUNT - 1)
            HOUR_FORMAT.format(Instant.ofEpochMilli(at))
        }
    }

    private fun overRow(
        input: RecordingSessionOverInput,
        oversById: Map<String, RecordingSessionOverInput>,
    ): RecordingSessionRow.Over {
        val transmission = input.transmission
        val status = statusOf(transmission)
        val attribution = if (status == RecordingSessionOverStatus.RESOLVED) attributionFrom(transmission) else null
        return RecordingSessionRow.Over(
            id = transmission.id,
            timeLabel = ReaderTransmissionViewStateMapper.timeLabel(transmission.startedAtUtc),
            frequencyLabel = ReaderTransmissionViewStateMapper.frequencyLabel(transmission.frequencyHz),
            durationLabel = ReaderTransmissionViewStateMapper.durationLabel(transmission.durationMs),
            status = status,
            transcript = if (status == RecordingSessionOverStatus.RESOLVED) input.transcriptText else null,
            attribution = attribution,
            callsign = input.callsign,
            alternate = null, // R-560-style AMBIGUOUS candidate: no candidate-inspection read wired here yet.
            attributionStateLabel = attribution?.state?.name?.lowercase(Locale.US),
            inferredFromLabel = attribution
                ?.takeIf { it.state == AttributionState.INFERRED }
                ?.sourceTransmissionId
                ?.let { oversById[it.toString()]?.transmission?.startedAtUtc }
                ?.let { ReaderTransmissionViewStateMapper.timeLabel(it) },
            hasAudio = input.hasAudioFile,
            stationId = transmission.stationId,
            trainingLabel = trainingLabel(input.label),
            markedForTraining = input.label?.markedForTraining ?: false,
            statusReasonLabel = statusReasonLabel(status, transmission, input.failedAttemptCount),
            canRetry = status == RecordingSessionOverStatus.FAILED,
        )
    }

    private fun statusOf(transmission: TransmissionEntity): RecordingSessionOverStatus =
        when (transmission.processingState) {
            TransmissionState.COMPLETE -> RecordingSessionOverStatus.RESOLVED
            TransmissionState.REJECTED -> RecordingSessionOverStatus.REJECTED
            TransmissionState.FAILED -> RecordingSessionOverStatus.FAILED
            TransmissionState.CAPTURED, TransmissionState.PROCESSING -> RecordingSessionOverStatus.PROCESSING
        }

    private fun statusReasonLabel(
        status: RecordingSessionOverStatus,
        transmission: TransmissionEntity,
        failedAttemptCount: Int?,
    ): String? = when (status) {
        RecordingSessionOverStatus.REJECTED -> transmission.rejectionReason ?: "rejected"
        RecordingSessionOverStatus.FAILED -> failedAttemptCount?.let {
            "Pass B errored ${Plurals.count(it, "time")}"
        } ?: "Pass B errored"
        RecordingSessionOverStatus.RESOLVED, RecordingSessionOverStatus.PROCESSING -> null
    }

    private fun trainingLabel(label: TransmissionLabelEntity?): String? {
        if (label == null || !label.markedForTraining) return null
        return listOfNotNull("training", label.rating).joinToString(" · ")
    }

    /**
     * Duplicated deliberately from `org.ort.app.ui.data.LogViewData`'s own private
     * `attributionFrom`/`org.ort.app.ui.data.ReaderPolling`'s own private copy — both are `private`
     * to packages this build unit does not own, the same "each package keeps its own small, pure
     * copy" convention `LogViewData`'s own doc comment already states outright for the identical
     * function.
     */
    private fun attributionFrom(entity: TransmissionEntity): Attribution {
        val stationId = entity.stationId
        if (entity.corrected && stationId != null) {
            return Attribution.unknown().withCorrection(stationId)
        }
        val confidence = entity.attributionConfidence
        return when (entity.attributionState) {
            AttributionState.CONFIRMED ->
                if (stationId != null && confidence != null) {
                    Attribution.confirmed(stationId, confidence)
                } else {
                    Attribution.unknown()
                }
            AttributionState.INFERRED ->
                if (stationId != null && confidence != null) {
                    Attribution.inferred(stationId, confidence, sourceId(entity))
                } else {
                    Attribution.unknown()
                }
            AttributionState.AMBIGUOUS -> Attribution.ambiguous()
            AttributionState.UNKNOWN -> Attribution.unknown()
        }
    }

    private fun sourceId(entity: TransmissionEntity): TransmissionId? =
        entity.attributionSourceTransmissionId?.let { runCatching { TransmissionId.parse(it) }.getOrNull() }

    private fun gapRow(gap: CaptureGapEntity): RecordingSessionRow.Gap = RecordingSessionRow.Gap(
        id = gap.id,
        timeLabel = ReaderTransmissionViewStateMapper.timeLabel(gap.startedAt),
        titleLabel = "Not listening for ${
            ReaderTransmissionViewStateMapper.durationLabel((gap.endedAt ?: gap.startedAt) - gap.startedAt)
        }",
        causeLabel = gapCauseProse(gap.cause),
        resumedLabel = gap.endedAt?.let { "resumed ${ReaderTransmissionViewStateMapper.timeLabel(it)}" },
        bluetoothAudioDropped = gap.cause == CaptureGapCause.BLUETOOTH_AUDIO_LOST,
    )

    /** Duplicated deliberately from `org.ort.app.ui.data.LogViewData`'s own private
     * `gapCauseProse` — see [attributionFrom]'s own doc comment for why. */
    private fun gapCauseProse(cause: CaptureGapCause): String = when (cause) {
        CaptureGapCause.CALL -> "incoming call"
        CaptureGapCause.INPUT_LOST -> "input lost"
        CaptureGapCause.BLUETOOTH_AUDIO_LOST -> "Bluetooth audio lost"
        CaptureGapCause.OS_STOPPED -> "app stopped by the OS"
        CaptureGapCause.ROUTE_LOST -> "route lost"
        CaptureGapCause.INTERRUPTION -> "interruption"
        CaptureGapCause.ROUTE_CHANGE -> "route changed"
        CaptureGapCause.DEVICE_LOST -> "device lost"
        CaptureGapCause.STORAGE -> "storage full"
        CaptureGapCause.UNKNOWN -> "reason unknown"
    }
}
