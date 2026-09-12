package org.ort.app.ui.data

import android.content.Context
import org.ort.app.ui.improve.Plurals
import org.ort.core.Attribution
import org.ort.core.AttributionState
import org.ort.core.PassId
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.CaptureGapEntity
import org.ort.data.entity.TranscriptPass
import org.ort.data.entity.TransmissionEntity
import org.ort.pipeline.capture.ShedStatus

/**
 * `Live-Monitor.dc.html` (design-intent N07, R-1007, WPL) — the "Logged tonight" list's own read
 * path and render-ready rows. Everything about the header (state/elapsed/sub-line/Stop), the level
 * envelope, and the pinned bar's own room mark is drawn from state [org.ort.app.ui.screens
 * .CaptureStatusContent] already polls ([org.ort.app.ui.data.CaptureStatusViewState],
 * [org.ort.app.ui.data.LevelViewState], [org.ort.app.ui.components.LiveBarViewState]) — this file
 * is deliberately narrow: only the one new fact this screen adds, the session's own overs newest
 * first, each carrying the state it is actually in (constitution I — never render one as finished
 * when it is not, never omit one because its state is awkward).
 *
 * Deliberately independent of `ui/data/LogViewData.kt` and `ui/data/ReaderPolling.kt` (neither is
 * this package's row to edit) — every query here goes straight through [OrtDatabase], the same
 * "duplicate the small mapping rather than take a cross-package dependency" choice
 * `LogViewData.kt`'s own file doc comment already makes for its own, differently-shaped rows.
 * [ReaderTransmissionViewStateMapper] (same package, `TransmissionDetail.kt`) is reused directly
 * for `timeLabel`/`durationLabel`/`frequencyLabel` so the three screens can never disagree about
 * what those mean.
 */
public sealed interface LiveMonitorOverRow {
    public val id: String
    public val timeLabel: String

    /** A pass is actively leased and producing text right now (`PROCESSING`, current version Pass
     * B/REPROCESS) — the artboard's open-quadrant spinner row. */
    public data class Transcribing(
        override val id: String,
        override val timeLabel: String,
        val durationLabel: String,
        val passLabel: String,
        /** `null` when [ShedStatus] has not measured a tier yet this process — never fabricated. */
        val tierLabel: String?,
    ) : LiveMonitorOverRow

    /** Queued (`CAPTURED`), or leased with no transcript text at all yet — nothing to name a
     * running pass from, so this is the honest, broader "not started" state. [aheadCount] is a
     * real count of other still-queued overs earlier in this session, never an invented number. */
    public data class Waiting(
        override val id: String,
        override val timeLabel: String,
        val durationLabel: String,
        val aheadCount: Int,
    ) : LiveMonitorOverRow

    /** `COMPLETE` — the artboard's four attribution-marker rows (confirmed/inferred/ambiguous/
     * unknown) live here, one type, distinguished by [attribution] alone (constitution I: the four
     * states are a closed set, carried at the data layer). [inferredFromLabel] is the "inferred
     * from HH:MM:SS" caption — present only for INFERRED, and only when the source transmission is
     * still in this same session's own fetched list (never guessed for one that is not). */
    public data class Resolved(
        override val id: String,
        override val timeLabel: String,
        val transcript: String,
        val durationLabel: String,
        val frequencyLabel: String,
        val attribution: Attribution,
        val callsign: String?,
        val attributionStateLabel: String,
        val inferredFromLabel: String?,
    ) : LiveMonitorOverRow

    /** `FAILED` — "not transcribed", never silently dropped (constitution III: the audio is kept,
     * and this row says so). [attemptsLabel] reads the real, durable
     * [org.ort.data.entity.WorkQueueItemEntity.attemptCount] for this transmission's Pass B item —
     * `null` only when no such row exists to read (an honest absence, never a guessed count). */
    public data class NotTranscribed(
        override val id: String,
        override val timeLabel: String,
        val durationLabel: String,
        val attemptsLabel: String?,
    ) : LiveMonitorOverRow

    /**
     * FR-RUN-12 (constitution IV, "a gap is data"): a silence that was listened to, rendered as a
     * row rather than an absence — the span between two overs in this session during which no
     * [CaptureGapEntity] was open at all, so the squelch genuinely never opened rather than the app
     * not listening. Deliberately distinct from [org.ort.app.ui.data.LogListItem.Gap] (the *not
     * listening* row `Log.dc.html` already draws): that row means capture itself stopped; this one
     * means capture kept running and heard nothing, which is the opposite fact.
     */
    public data class ListenedSilence(
        override val id: String,
        override val timeLabel: String,
        val durationLabel: String,
    ) : LiveMonitorOverRow
}

/** [overs] is newest-first (design-intent N07's own words), unlike `Log.dc.html`'s chronological
 * list — this screen is a live instrument read top-down from "just happened". */
public data class LiveMonitorOversViewState(val overs: List<LiveMonitorOverRow>, val summaryLabel: String) {
    public companion object {
        public val EMPTY: LiveMonitorOversViewState = LiveMonitorOversViewState(
            overs = emptyList(),
            summaryLabel = "0 overs",
        )
    }
}

/** Pure builders — DB-free, so every rule here (partial/queue/attribution classification, the
 * listened-silence span rule) is directly unit-testable. */
public object LiveMonitorOversMapper {

    private const val MAX_TIER: Int = 3

    /** Mirrors `LogItemsMapper.partialFor`'s own "still open and carrying a partial" rule
     * (`ui/data/LogViewData.kt`), duplicated rather than depending on that file's own
     * `TransmissionDetail`-shaped signature — this package's own file doc comment states why. */
    internal enum class PartialKind { HEARING, RESOLVING }

    internal fun partialKind(state: TransmissionState, pass: TranscriptPass?, hasCurrentText: Boolean): PartialKind? {
        val stillOpen = state == TransmissionState.CAPTURED || state == TransmissionState.PROCESSING
        if (!stillOpen || !hasCurrentText) return null
        return when (pass) {
            TranscriptPass.A -> PartialKind.HEARING
            TranscriptPass.B, TranscriptPass.REPROCESS -> PartialKind.RESOLVING
            null -> null
        }
    }

    /** [entity]'s own row, or `null` for a `REJECTED` entity (out of this screen's scope — the
     * artboard's own seven example rows never include one; `Log.dc.html` is the rejected surface)
     * or a still-`HEARING` one (that fact is the "Hearing now" card's own, not a list row — the two
     * never double-count the same transmission). */
    @Suppress("LongParameterList")
    public fun rowFor(
        entity: TransmissionEntity,
        currentPass: TranscriptPass?,
        currentText: String?,
        aheadCount: Int,
        failedAttemptCount: Int?,
        tierLabel: String?,
        sourceTimeLabel: String?,
    ): LiveMonitorOverRow? {
        val timeLabel = ReaderTransmissionViewStateMapper.timeLabel(entity.startedAtUtc)
        val durationLabel = ReaderTransmissionViewStateMapper.durationLabel(entity.durationMs)
        val kind = partialKind(entity.processingState, currentPass, currentText != null)
        return when {
            entity.processingState == TransmissionState.REJECTED -> null
            kind == PartialKind.HEARING -> null
            kind == PartialKind.RESOLVING -> LiveMonitorOverRow.Transcribing(
                id = entity.id,
                timeLabel = timeLabel,
                durationLabel = durationLabel,
                passLabel = passLabelFor(currentPass),
                tierLabel = tierLabel,
            )

            entity.processingState == TransmissionState.CAPTURED ||
                entity.processingState == TransmissionState.PROCESSING ->
                LiveMonitorOverRow.Waiting(
                    id = entity.id,
                    timeLabel = timeLabel,
                    durationLabel = durationLabel,
                    aheadCount = aheadCount,
                )

            entity.processingState == TransmissionState.FAILED -> LiveMonitorOverRow.NotTranscribed(
                id = entity.id,
                timeLabel = timeLabel,
                durationLabel = durationLabel,
                attemptsLabel = failedAttemptCount?.let { "Pass B errored " + Plurals.count(it, "time") },
            )

            else -> LiveMonitorOverRow.Resolved(
                id = entity.id,
                timeLabel = timeLabel,
                transcript = currentText.orEmpty(),
                durationLabel = durationLabel,
                frequencyLabel = ReaderTransmissionViewStateMapper.frequencyLabel(entity.frequencyHz),
                attribution = attributionFrom(entity),
                callsign = entity.stationId,
                attributionStateLabel = attributionStateLabel(entity.attributionState),
                inferredFromLabel = if (entity.attributionState == AttributionState.INFERRED) sourceTimeLabel else null,
            )
        }
    }

    /** Lowercase, operator-facing prose for the four closed states (guide §9: "Enum values are
     * prose"). Duplicated from `LogViewData.kt`'s own `attributionProse` deliberately — see this
     * file's own doc comment on why this package's read path stays independent of that one. */
    public fun attributionStateLabel(state: AttributionState): String = when (state) {
        AttributionState.CONFIRMED -> "confirmed"
        AttributionState.INFERRED -> "inferred"
        AttributionState.AMBIGUOUS -> "ambiguous"
        AttributionState.UNKNOWN -> "unknown station"
    }

    private fun passLabelFor(pass: TranscriptPass?): String = when (pass) {
        TranscriptPass.REPROCESS -> "Reprocessing"
        TranscriptPass.B, TranscriptPass.A, null -> "Pass B running"
    }

    /** Duplicated from `LogViewData.kt`/`ReaderPolling.kt` deliberately — see this file's own doc
     * comment. */
    private fun attributionFrom(entity: TransmissionEntity): Attribution {
        val stationId = entity.stationId
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
                    Attribution.inferred(stationId, confidence)
                } else {
                    Attribution.unknown()
                }

            AttributionState.AMBIGUOUS -> Attribution.ambiguous()
            AttributionState.UNKNOWN -> Attribution.unknown()
        }
    }

    /**
     * FR-RUN-12: the span between two consecutive (non-rejected) overs in [sorted] (ascending by
     * `startedAtUtc`) that no [gaps] entry overlaps at all, and that is at least [floorMillis] long
     * — a floor so an ordinary few seconds of squelch closing between overs does not spam the list
     * with a row for every breath (a real, if arbitrary, editorial choice; see this package's own
     * report). A span any part of which a gap covers is the *not listening* fact `Log.dc.html`
     * already renders, never double-counted here as a *listened* one.
     */
    public fun listenedSilenceRows(
        sorted: List<TransmissionEntity>,
        gaps: List<CaptureGapEntity>,
        floorMillis: Long,
    ): List<Pair<Long, LiveMonitorOverRow.ListenedSilence>> = (0 until sorted.size - 1).mapNotNull { i ->
        val prevEnd = sorted[i].endedAtUtc ?: (sorted[i].startedAtUtc + sorted[i].durationMs)
        val nextStart = sorted[i + 1].startedAtUtc
        val span = nextStart - prevEnd
        val coveredByGap = gaps.any { gap ->
            val gapEnd = gap.endedAt ?: Long.MAX_VALUE
            gap.startedAt < nextStart && gapEnd > prevEnd
        }
        if (span < floorMillis || coveredByGap) {
            null
        } else {
            prevEnd to LiveMonitorOverRow.ListenedSilence(
                id = "silence-$prevEnd",
                timeLabel = ReaderTransmissionViewStateMapper.timeLabel(prevEnd),
                durationLabel = ReaderTransmissionViewStateMapper.durationLabel(span),
            )
        }
    }

    public fun summaryLabel(totalOvers: Int, waitingCount: Int): String =
        Plurals.count(totalOvers, "over") + " · $waitingCount waiting"

    /** `RealCaptureService.tierFromShedLevel()`'s own formula, reused rather than invented a second
     * time — see `CaptureStatusMapper.tierFacts`'s own doc comment for why this project keeps this
     * one formula in one place. */
    public fun tierLabel(shedLevel: Int): String = "tier " + (MAX_TIER - shedLevel).coerceIn(0, MAX_TIER)
}

/** The real, `:data`-direct read path (see this file's own doc comment for why it is independent
 * of `LogPolling`/`ReaderPolling`). */
public object LiveMonitorOversPolling {

    /** A floor of one minute: the artboard's own example gap is 4m52s, and an ordinary few seconds
     * of squelch tail between overs is not "a silence that was listened to" in the sense the
     * operator asked about — see [LiveMonitorOversMapper.listenedSilenceRows]'s own doc comment. */
    private const val LISTENED_SILENCE_FLOOR_MILLIS = 60_000L

    /** [nonRejected]'s own currently-open transcript, if any — a small local shape purely so
     * [current] does not re-query [org.ort.data.dao.TranscriptDao] once per read. */
    private data class CurrentTranscript(val pass: TranscriptPass?, val text: String?)

    /** `CAPTURED`/`PROCESSING` with no `HEARING`/`RESOLVING` partial to show for it yet — the same
     * condition [LiveMonitorOversMapper.rowFor] itself renders as [LiveMonitorOverRow.Waiting],
     * kept in one place so the "how many overs are waiting" count and the list of them can never
     * disagree with each other. */
    private fun isWaiting(entity: TransmissionEntity, current: CurrentTranscript?): Boolean {
        val stillOpen = entity.processingState == TransmissionState.CAPTURED ||
            entity.processingState == TransmissionState.PROCESSING
        val hasPartial = LiveMonitorOversMapper.partialKind(
            entity.processingState,
            current?.pass,
            current?.text != null,
        ) != null
        return stillOpen && !hasPartial
    }

    public suspend fun current(context: Context, sessionId: String?): LiveMonitorOversViewState {
        if (sessionId == null) return LiveMonitorOversViewState.EMPTY
        val db = OrtDatabase.create(context.applicationContext)
        val entities = db.transmissionDao().listBySession(sessionId)
        val gaps = db.captureGapDao().listBySession(sessionId)
        val nonRejected = entities.filter { it.processingState != TransmissionState.REJECTED }
        val timeById = nonRejected.associate { it.id to it.startedAtUtc }

        val currentByTransmission = nonRejected.associate { entity ->
            val versions = db.transcriptDao().getAllVersions(entity.id)
            val current = versions.firstOrNull { it.isCurrent }
            entity.id to CurrentTranscript(current?.pass, current?.text)
        }

        val waitingSorted = nonRejected
            .filter { entity -> isWaiting(entity, currentByTransmission[entity.id]) }
            .sortedBy { it.startedAtUtc }
        val aheadCountById = waitingSorted.withIndex().associate { (index, entity) -> entity.id to index }

        val tierLabel = LiveMonitorOversMapper.tierLabel(ShedStatus.currentLevel)

        val rows = nonRejected.mapNotNull { entity ->
            val current = currentByTransmission[entity.id]
            val failedAttemptCount = if (entity.processingState == TransmissionState.FAILED) {
                db.workQueueDao()
                    .findByTransmissionAndPass(entity.id, PassId.B_OFFLINE.name)
                    .firstOrNull()
                    ?.attemptCount
            } else {
                null
            }
            val sourceTimeLabel = entity.attributionSourceTransmissionId
                ?.let { timeById[it] }
                ?.let { ReaderTransmissionViewStateMapper.timeLabel(it) }
            val row = LiveMonitorOversMapper.rowFor(
                entity = entity,
                currentPass = current?.pass,
                currentText = current?.text,
                aheadCount = aheadCountById[entity.id] ?: 0,
                failedAttemptCount = failedAttemptCount,
                tierLabel = tierLabel,
                sourceTimeLabel = sourceTimeLabel,
            )
            row?.let { (entity.startedAtUtc) to it }
        }

        val silenceRows = LiveMonitorOversMapper.listenedSilenceRows(
            nonRejected.sortedBy { it.startedAtUtc },
            gaps,
            LISTENED_SILENCE_FLOOR_MILLIS,
        )

        val merged = (rows + silenceRows).sortedByDescending { it.first }.map { it.second }
        return LiveMonitorOversViewState(
            overs = merged,
            summaryLabel = LiveMonitorOversMapper.summaryLabel(nonRejected.size, waitingSorted.size),
        )
    }
}
