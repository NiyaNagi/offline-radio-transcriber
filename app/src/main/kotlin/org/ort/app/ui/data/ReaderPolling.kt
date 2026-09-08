package org.ort.app.ui.data

import android.content.Context
import android.os.PowerManager
import org.ort.app.status.StatusViewState
import org.ort.app.status.StatusViewStateMapper
import org.ort.capture.android.heartbeat.FileHeartbeatStore
import org.ort.core.Attribution
import org.ort.core.AttributionState
import org.ort.core.SystemClock
import org.ort.core.TransmissionId
import org.ort.data.OrtDatabase
import org.ort.data.entity.TransmissionEntity
import org.ort.pipeline.CaptureStatusRepository
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.shed.ShedController
import org.ort.pipeline.shed.ShedSignals
import java.io.File

/**
 * The same v0 smoke-test data path [org.ort.app.status.StatusActivity] and
 * [org.ort.app.transmissions.TransmissionListActivity] poll (build-plan P8/P11) — reused here,
 * not reinvented, so the new Compose screens (build-plan P13) show the identical facts the plain-
 * view surfaces already show ("no behaviour change"). Those two Activities are owned by a
 * concurrent session (P12) and are left untouched; this is a new, independent read path over the
 * same repository types.
 *
 * A real reader (M5, P14 on) replaces polling with a `Flow` observed straight from `:data` and
 * `:pipeline`; this stays a poll for the same reason the Activities it mirrors do — it is not
 * this prompt's job to change that wiring, only to prove Compose can render what it produces.
 */
public object ReaderPolling {

    public fun uncleanEndBanner(context: Context): String? {
        val repository = statusRepository(context)
        val uncleanEnd = repository.uncleanEndFromPreviousLaunch() ?: return null
        return "The previous session ended unexpectedly. Last heartbeat: ${uncleanEnd.lastHeartbeatWallMillis}."
    }

    public suspend fun currentStatus(context: Context, sessionId: String, startedAtWallMillis: Long): StatusViewState {
        val db = OrtDatabase.create(context.applicationContext)
        val repository = statusRepository(context)
        val count = db.transmissionDao().listBySession(sessionId).size
        val gaps = db.captureGapDao().listBySession(sessionId).size
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val status = repository.current(
            sessionId = sessionId,
            isCapturing = CaptureState.isCapturing,
            elapsedMillis = SystemClock.wallMillis() - startedAtWallMillis,
            transmissionCount = count,
            gapCount = gaps,
            isIgnoringBatteryOptimizationsDiagnosticOnly = pm.isIgnoringBatteryOptimizations(context.packageName),
        )
        val base = StatusViewStateMapper.from(status)
        val failure = CaptureState.failureReason
        return if (failure != null) base.copy(stateLabel = "${base.stateLabel} — $failure") else base
    }

    /**
     * The real reader read path (build-plan P14, FR-UI-1): every transmission in [sessionId],
     * **newest first**, each carrying its real current transcript (or an honest `null` when Pass
     * B has not produced one yet — [org.ort.app.ui.data.ReaderTransmissionViewStateMapper] turns
     * that into "captured, not yet transcribed"), its real attribution, and every superseded
     * transcript version so a revision is visible rather than silently replaced (AC-31's
     * append-only guarantee, made visible here rather than just enforced at the data layer).
     */
    public suspend fun currentTransmissionDetails(context: Context, sessionId: String): List<TransmissionDetail> {
        val db = OrtDatabase.create(context.applicationContext)
        // listBySession orders ascending by samplePosition (capture order) — reversed here for
        // "newest first" (FR-UI-1) rather than adding a second, differently-ordered DAO query.
        return db.transmissionDao().listBySession(sessionId).asReversed().map { entity ->
            detailFrom(context, db, entity)
        }
    }

    /** One transmission's full detail, for the drill-in screen (FR-UI-5, FR-UI-8's non-lattice half). */
    public suspend fun transmissionDetail(context: Context, transmissionId: String): TransmissionDetail? {
        val db = OrtDatabase.create(context.applicationContext)
        val entity = db.transmissionDao().getById(transmissionId) ?: return null
        return detailFrom(context, db, entity)
    }

    /**
     * Builds a [TransmissionDetail] from an already-fetched [TransmissionEntity] (build-plan
     * P15) — the identical mapping [currentTransmissionDetails]/[transmissionDetail] use, exposed
     * so [org.ort.app.ui.data.SearchPolling] and [org.ort.app.ui.data.ThreadPolling] can turn a
     * [org.ort.data.dao.SearchDao] result into the same view-model type the reader already
     * renders, rather than building a second, drifting mapping.
     */
    public suspend fun detailFromEntity(context: Context, entity: TransmissionEntity): TransmissionDetail {
        val db = OrtDatabase.create(context.applicationContext)
        return detailFrom(context, db, entity)
    }

    private suspend fun detailFrom(context: Context, db: OrtDatabase, entity: TransmissionEntity): TransmissionDetail {
        val versions = db.transcriptDao().getAllVersions(entity.id)
        val current = versions.firstOrNull { it.isCurrent }
        val superseded = versions.filter { !it.isCurrent }.sortedBy { it.createdAt }.map { it.text }
        val audioFile = File(context.filesDir, entity.audioPath())
        val inspection = InspectionViewStateMapper.from(
            db.catalogDao().latticesFor(entity.id),
            db.catalogDao().candidatesFor(entity.id),
        )
        return TransmissionDetail(
            id = entity.id,
            startedAtUtcMillis = entity.startedAtUtc,
            frequencyHz = entity.frequencyHz,
            durationMs = entity.durationMs,
            signalStrength = entity.signalStrength,
            attribution = attributionFrom(entity),
            currentTranscriptText = current?.text,
            supersededTranscriptTexts = superseded,
            hasAudio = audioFile.isFile,
            threadId = entity.threadId,
            sessionId = entity.sessionId,
            samplePosition = entity.samplePosition,
            inspection = inspection,
            processingState = entity.processingState,
            rejectionReason = entity.rejectionReason,
        )
    }

    /**
     * FR-UI-6 + FR-SPK-7: applies a one-tap correction and its `CORRECTED` lock
     * ([org.ort.data.dao.CorrectionDao.recordCorrection]) — the only write path this reader uses
     * that can change an attribution the resolver already produced.
     */
    public suspend fun applyCorrection(context: Context, request: CorrectionRequest) {
        val db = OrtDatabase.create(context.applicationContext)
        db.correctionDao().recordCorrection(request.toEntity())
    }

    /**
     * Q8's second correction tier (Tier B): known stations this device has already heard, as the
     * reachable substitute for "search the lexicon" — see [CorrectionTier]'s own doc comment for
     * why full lexicon search is out of this prompt's reach.
     */
    public suspend fun searchKnownStations(context: Context, query: String): List<String> {
        val db = OrtDatabase.create(context.applicationContext)
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return db.activityDao().listStations()
            .map { it.id }
            .filter { it.contains(q, ignoreCase = true) }
    }

    /**
     * Reconstructs the type-safe [Attribution] the constitution requires (Principle I) from the
     * entity's raw columns. Falls back to [Attribution.unknown] rather than throwing if a
     * `CONFIRMED`/`INFERRED` row is ever missing the station or confidence its own factory
     * requires — a display concern must never crash the reader over a data inconsistency it did
     * not cause, but it must also never *invent* a station that was not actually written.
     */
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
                    Attribution.inferred(stationId, confidence, sourceId(entity))
                } else {
                    Attribution.unknown()
                }

            AttributionState.AMBIGUOUS -> Attribution.ambiguous()
            AttributionState.UNKNOWN -> Attribution.unknown()
        }
    }

    /** The "Stations" list (FR-UI-9): every station ever heard, most recently heard first. */
    public suspend fun listStationSummaries(context: Context): List<StationListEntryViewState> {
        val db = OrtDatabase.create(context.applicationContext)
        return db.activityDao().listStations().map { StationViewMapper.listEntry(it) }
    }

    /** The "Frequencies" list (FR-UI-10): every frequency ever recorded on a transmission. */
    public suspend fun listFrequencySummaries(context: Context): List<FrequencyListEntryViewState> {
        val db = OrtDatabase.create(context.applicationContext)
        return db.activityDao().listDistinctFrequencies().map { frequencyHz ->
            val count = db.activityDao().transmissionsForFrequency(frequencyHz).size
            FrequencyViewMapper.listEntry(frequencyHz, count)
        }
    }

    /**
     * Everything heard from [stationId], across every session (FR-UI-9), plus its activity
     * pattern (FR-UI-11), built from the *real* [org.ort.data.entity.SessionEntity] start/end and
     * [org.ort.data.entity.CaptureGapEntity] rows every recorded session carries — FR-UI-12's "not
     * heard" vs "not listening" distinction is structural in [ActivityPatternMapper], not decided
     * here.
     */
    public suspend fun stationDetail(context: Context, stationId: String, nowMillis: Long): StationDetailViewState {
        val db = OrtDatabase.create(context.applicationContext)
        val entities = db.activityDao().transmissionsForStation(stationId)
        val details = entities.map { detailFrom(context, db, it) }
        val pattern = activityPatternForEverySession(db, entities.map { it.startedAtUtc }, nowMillis)
        val label = db.catalogDao().getStation(stationId)?.callsign ?: stationId
        return StationViewMapper.detail(stationId, label, details, pattern)
    }

    /** Everything heard on [frequencyHz], across every session (FR-UI-10), plus its activity pattern (FR-UI-11). */
    public suspend fun frequencyDetail(context: Context, frequencyHz: Long, nowMillis: Long): FrequencyDetailViewState {
        val db = OrtDatabase.create(context.applicationContext)
        val entities = db.activityDao().transmissionsForFrequency(frequencyHz)
        val details = entities.map { detailFrom(context, db, it) }
        val pattern = activityPatternForEverySession(db, entities.map { it.startedAtUtc }, nowMillis)
        return FrequencyViewMapper.detail(frequencyHz, details, pattern)
    }

    /**
     * The [SessionWindow] for **every session ever recorded**, not only the ones a station or
     * frequency happened to be heard in (FR-UI-12): capture does not know in advance which
     * station or frequency the operator will later ask about, so an hour a session was capturing
     * through but this particular station/frequency stayed quiet is genuinely
     * [HourActivityState.SILENT_WHILE_LISTENING] — restricting to only the sessions that produced
     * a match would wrongly turn every other, equally-real listening hour into
     * [HourActivityState.NOT_LISTENING], which is exactly the fabricated-absence direction FR-UI-12
     * forbids, just aimed the other way.
     */
    private suspend fun activityPatternForEverySession(
        db: OrtDatabase,
        matchingTimestamps: List<Long>,
        nowMillis: Long,
    ): List<HourActivityBucket> {
        val sessions = db.sessionDao().listAll().map { session ->
            val gaps = db.captureGapDao().listBySession(session.id).map { gap ->
                GapWindow(startedAt = gap.startedAt, endedAt = gap.endedAt)
            }
            SessionWindow(startedAtUtc = session.startedAt, endedAtUtc = session.endedAt, gaps = gaps)
        }
        return ActivityPatternMapper.buildPattern(sessions, matchingTimestamps, nowMillis)
    }

    private fun sourceId(entity: TransmissionEntity): TransmissionId? =
        entity.attributionSourceTransmissionId?.let { runCatching { TransmissionId.parse(it) }.getOrNull() }

    private fun statusRepository(context: Context): CaptureStatusRepository {
        val heartbeatStore = FileHeartbeatStore(File(context.filesDir, "heartbeat.txt"))
        // Same neutral, always-nominal shed signals as the v0 Activities — no real shed telemetry
        // is wired for this smoke path (see StatusActivity's identical comment).
        val shedController = ShedController(
            object : ShedSignals {
                override fun batteryPercent(): Int = 100
                override fun isCharging(): Boolean = true
                override fun queueBacklog(): Int = 0
                override fun freeStorageBytes(): Long = Long.MAX_VALUE
            },
            SystemClock,
        )
        return CaptureStatusRepository(heartbeatStore, shedController, SystemClock)
    }
}
