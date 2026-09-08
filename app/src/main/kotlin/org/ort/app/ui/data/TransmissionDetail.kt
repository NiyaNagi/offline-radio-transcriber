package org.ort.app.ui.data

import org.ort.core.Attribution
import java.util.Locale

/**
 * Real, per-transmission facts as they exist in `:data` right now (build-plan P14) —
 * Compose-agnostic, mirroring `TransmissionRow`'s own separation from the Room entity
 * (build-plan P11's doc comment on [org.ort.app.transmissions.TransmissionRow]).
 *
 * [currentTranscriptText] is `null` exactly when no [org.ort.data.entity.TranscriptEntity] row
 * exists yet for this transmission — the honest "captured, not yet transcribed" state (this
 * prompt's stated empty case), never an empty string standing in for "nothing happened yet".
 * [supersededTranscriptTexts] is every earlier version, oldest first, **excluding** the current
 * one — present only when a pass genuinely superseded an earlier transcript (FR-UI-1: "a
 * superseded partial is visibly superseded rather than silently replaced").
 */
public data class TransmissionDetail(
    val id: String,
    val startedAtUtcMillis: Long,
    val frequencyHz: Long?,
    val durationMs: Long,
    val signalStrength: Double?,
    val attribution: Attribution,
    val currentTranscriptText: String?,
    val supersededTranscriptTexts: List<String>,
    val hasAudio: Boolean,
    /** Build-plan P16, FR-OBS-4: the labelled-sample defaults need the session a transmission came from. */
    val sessionId: String = "",
    /** The transmission's position in its session, in samples at the capture rate (technical design §8). */
    val samplePosition: Long = 0L,
    /** Build-plan P16, FR-UI-8: the resolver's lattice/candidates/per-prior breakdown, if recorded. */
    val inspection: InspectionViewState = InspectionViewState.EMPTY,
)

/** One row of the live/log view — everything [org.ort.app.ui.screens.LogScreen] renders. */
public data class TransmissionListEntryViewState(
    val id: String,
    val timeLabel: String,
    val frequencyLabel: String,
    val transcriptText: String,
    val attribution: Attribution,
    /** Non-null exactly when this transmission's current transcript superseded an earlier one. */
    val revisionNote: String?,
    val signalLabel: String?,
)

/** The transmission detail (drill-in) view — everything [org.ort.app.ui.screens.TransmissionDetailScreen] renders. */
public data class TransmissionDetailViewState(
    val id: String,
    val timeLabel: String,
    val frequencyLabel: String,
    val durationLabel: String,
    val signalLabel: String?,
    val attribution: Attribution,
    val transcriptText: String,
    val revisionHistory: List<String>,
    val hasAudio: Boolean,
    /**
     * FR-UI-8: the resolver's lattice/candidates/per-prior breakdown. Honestly empty until a
     * future pass writes [org.ort.data.entity.PhoneticLatticeEntity] /
     * [org.ort.data.entity.CallsignCandidateEntity] rows.
     */
    val inspection: InspectionViewState = InspectionViewState.EMPTY,
    /** FR-OBS-4 labelled-sample defaults, derived from the real transmission this screen is showing. */
    val sessionId: String = "",
    val startSample: Long = 0L,
    val endSample: Long = 0L,
)

public object ReaderTransmissionViewStateMapper {

    private const val NOT_YET_TRANSCRIBED = "(captured, not yet transcribed)"

    public fun listEntry(detail: TransmissionDetail): TransmissionListEntryViewState = TransmissionListEntryViewState(
        id = detail.id,
        timeLabel = timeLabel(detail.startedAtUtcMillis),
        frequencyLabel = frequencyLabel(detail.frequencyHz),
        transcriptText = detail.currentTranscriptText ?: NOT_YET_TRANSCRIBED,
        attribution = detail.attribution,
        revisionNote = revisionNote(detail.supersededTranscriptTexts.size),
        signalLabel = signalLabel(detail.signalStrength),
    )

    /** Samples per millisecond at the capture rate every retained transmission is stored at (technical design §5). */
    private const val SAMPLES_PER_MS = 16L

    public fun detailView(detail: TransmissionDetail): TransmissionDetailViewState = TransmissionDetailViewState(
        id = detail.id,
        timeLabel = timeLabel(detail.startedAtUtcMillis),
        frequencyLabel = frequencyLabel(detail.frequencyHz),
        durationLabel = "%.1fs".format(Locale.ROOT, detail.durationMs / 1000.0),
        signalLabel = signalLabel(detail.signalStrength),
        attribution = detail.attribution,
        transcriptText = detail.currentTranscriptText ?: NOT_YET_TRANSCRIBED,
        revisionHistory = detail.supersededTranscriptTexts,
        hasAudio = detail.hasAudio,
        inspection = detail.inspection,
        sessionId = detail.sessionId,
        startSample = detail.samplePosition,
        endSample = detail.samplePosition + detail.durationMs * SAMPLES_PER_MS,
    )

    private fun revisionNote(supersededCount: Int): String? = when {
        supersededCount <= 0 -> null
        supersededCount == 1 -> "revised · 1 earlier version"
        else -> "revised · $supersededCount earlier versions"
    }

    private fun frequencyLabel(frequencyHz: Long?): String =
        frequencyHz?.let { "%.3f".format(Locale.ROOT, it / 1_000_000.0) } ?: "—"

    private fun signalLabel(signalStrength: Double?): String? = signalStrength?.let { "S%.0f".format(Locale.ROOT, it) }

    private fun timeLabel(startedAtUtcMillis: Long): String {
        val totalSeconds = startedAtUtcMillis / 1000
        val hours = (totalSeconds / 3600) % 24
        val minutes = (totalSeconds / 60) % 60
        val seconds = totalSeconds % 60
        return "%02d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
    }
}

/** The "Now" home's header counts (`Main.dc.html`: "412 overs · 19 stations · 2 bands"). */
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
