package org.ort.app.ui.data

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ort.app.status.StatusViewState
import org.ort.app.status.StatusViewStateMapper
import org.ort.app.ui.navigation.DrawerBadgeViewState
import org.ort.capture.android.heartbeat.FileHeartbeatStore
import org.ort.core.Attribution
import org.ort.core.AttributionState
import org.ort.core.SystemClock
import org.ort.core.TransmissionId
import org.ort.data.OrtDatabase
import org.ort.data.entity.TransmissionEntity
import org.ort.pipeline.CaptureStatusRepository
import org.ort.pipeline.capture.AsrAvailability
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.ShedStatus
import org.ort.pipeline.capture.VadAvailability
import org.ort.pipeline.passb.LexiconLookup
import org.ort.pipeline.passb.LexiconMatch
import org.ort.pipeline.passb.RealLexiconLookup
import org.ort.pipeline.shed.FakeShedSignals
import org.ort.pipeline.shed.ShedController
import java.io.File
import java.time.ZoneId

/**
 * The read path build-plan P8/P11's original plain-view smoke-test Activities used to poll
 * (`StatusActivity`, `TransmissionListActivity`) — both since deleted (audit F-002: they were the
 * second copy of the fabricated-shed-signal bug fixed here, and nothing launched them once
 * `ReaderActivity`/`OrtNavHost` became the app's real reader in build-plan P13/P14) — reused here
 * rather than reinvented, so the Compose screens show the identical facts the plain-view surfaces
 * used to show ("no behaviour change").
 *
 * A real reader (M5, P14 on) replaces polling with a `Flow` observed straight from `:data` and
 * `:pipeline`; this stays a poll for now — it is not this prompt's job to change that wiring, only
 * to prove Compose can render what it produces.
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
        // FR-UI-7 / audit F-004: read the real, process-wide ASR/VAD availability the capture
        // service set (or has not set yet — `AsrAvailability.state`/`VadAvailability.state`
        // default to their own honest "not started"/fallback states, never a healthy default).
        //
        // FR-RUN-5 / audit F-002: the shed level/backlog CaptureStatusRepository.current() just
        // computed above are discarded — they came from the inert, never-ticked `ShedController`
        // `statusRepository` constructs purely to satisfy its constructor (see that function's
        // doc comment). The real reading lives in `ShedStatus`, published by `RealCaptureService`
        // (audit F-007) every ~10 s once capture actually starts. `CaptureState` is still `Idle`
        // before any session in this process has started capturing — the shed monitor coroutine
        // cannot have sampled anything yet either, so that is exactly when "not measured" (not a
        // fabricated `0`) is the honest thing to show.
        val shedMeasured = CaptureState.state != CaptureState.State.Idle
        val base = StatusViewStateMapper.from(
            status,
            AsrAvailability.state,
            VadAvailability.state,
            shedLevel = if (shedMeasured) ShedStatus.currentLevel else null,
            backlog = if (shedMeasured) ShedStatus.backlog else null,
        )
        val failure = CaptureState.failureReason
        return if (failure != null) base.copy(stateLabel = "${base.stateLabel} — $failure") else base
    }

    /**
     * The drawer's live badges (FR-UI-7, audit F-020) — see [DrawerBadgeViewState]'s own doc
     * comment for what "real" means for each field. `null` for [sessionId] (no active or prior
     * session) means no badge can be real, so every field comes back `null`.
     */
    public suspend fun drawerBadges(context: Context, sessionId: String?): DrawerBadgeViewState {
        if (sessionId == null) return DrawerBadgeViewState.NONE
        val db = OrtDatabase.create(context.applicationContext)
        val logCount = db.transmissionDao().listBySession(sessionId).size
        val elapsedLabel = if (CaptureState.isCapturing && CaptureState.sessionId == sessionId) {
            db.sessionDao().getById(sessionId)?.startedAt?.let { startedAt ->
                formatElapsedShort(SystemClock.wallMillis() - startedAt)
            }
        } else {
            null
        }
        return DrawerBadgeViewState(logCount = logCount, threadsCount = null, captureElapsedLabel = elapsedLabel)
    }

    /** `"6:42"` below an hour, `"1:06:42"` at or above one — matches `Menu.dc.html`'s precision. */
    private fun formatElapsedShort(elapsedMillis: Long): String {
        val totalSeconds = (elapsedMillis / 1000).coerceAtLeast(0)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
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
     * Audit F-018: Q8's real "search the lexicon" correction tier, now reachable through
     * `:pipeline`'s [LexiconLookup] over the bundled ITU table and callsign grammar — the
     * `:pipeline` call site P16 recorded as missing (CHANGELOG "Left open"). [lexiconLookup] is
     * lazily built once, not per keystroke, since [RealLexiconLookup.bundled] parses the bundled
     * assets. Runs off the main thread on [Dispatchers.Default] (a CPU-bound pool, not `IO`) — the
     * grammar's beam search is real CPU work, not I/O, and this is a live search box a caller may
     * invoke on every keystroke.
     */
    private val lexiconLookup: LexiconLookup by lazy { RealLexiconLookup.bundled() }

    public suspend fun searchLexicon(query: String, limit: Int = 20): List<LexiconMatch> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return withContext(Dispatchers.Default) { lexiconLookup.search(q, limit) }
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
        val timestamps = entities.map { it.startedAtUtc }
        val pattern = activityPatternForEverySession(db, timestamps, nowMillis)
        val dayOfWeekPattern = dayOfWeekPatternForEverySession(db, timestamps, nowMillis)
        val weekOverWeek = weekOverWeekComparisonForEverySession(db, timestamps, nowMillis)
        val label = db.catalogDao().getStation(stationId)?.callsign ?: stationId
        return StationViewMapper.detail(stationId, label, details, pattern, dayOfWeekPattern, weekOverWeek)
    }

    /** Everything heard on [frequencyHz], across every session (FR-UI-10), plus its activity pattern (FR-UI-11). */
    public suspend fun frequencyDetail(context: Context, frequencyHz: Long, nowMillis: Long): FrequencyDetailViewState {
        val db = OrtDatabase.create(context.applicationContext)
        val entities = db.activityDao().transmissionsForFrequency(frequencyHz)
        val details = entities.map { detailFrom(context, db, it) }
        val timestamps = entities.map { it.startedAtUtc }
        val pattern = activityPatternForEverySession(db, timestamps, nowMillis)
        val dayOfWeekPattern = dayOfWeekPatternForEverySession(db, timestamps, nowMillis)
        val weekOverWeek = weekOverWeekComparisonForEverySession(db, timestamps, nowMillis)
        return FrequencyViewMapper.detail(frequencyHz, details, pattern, dayOfWeekPattern, weekOverWeek)
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
    ): List<HourActivityBucket> =
        ActivityPatternMapper.buildPattern(everySessionWindow(db), matchingTimestamps, nowMillis)

    /**
     * The day-of-week half of FR-UI-11 (audit F-019) — same every-session windows as
     * [activityPatternForEverySession], bucketed by calendar day in the device's own zone (F-001:
     * real wall-clock timestamps, never a sample position).
     */
    private suspend fun dayOfWeekPatternForEverySession(
        db: OrtDatabase,
        matchingTimestamps: List<Long>,
        nowMillis: Long,
    ): List<DayOfWeekActivityBucket> = ActivityPatternMapper.buildDayOfWeekPattern(
        everySessionWindow(db),
        matchingTimestamps,
        nowMillis,
        ZoneId.systemDefault(),
    )

    /** FR-UI-11's "how that has changed" half (audit F-019). */
    private suspend fun weekOverWeekComparisonForEverySession(
        db: OrtDatabase,
        matchingTimestamps: List<Long>,
        nowMillis: Long,
    ): List<WeekOverWeekBucket> = ActivityPatternMapper.buildWeekOverWeekComparison(
        everySessionWindow(db),
        matchingTimestamps,
        nowMillis,
        ZoneId.systemDefault(),
    )

    private suspend fun everySessionWindow(db: OrtDatabase): List<SessionWindow> =
        db.sessionDao().listAll().map { session ->
            val gaps = db.captureGapDao().listBySession(session.id).map { gap ->
                GapWindow(startedAt = gap.startedAt, endedAt = gap.endedAt)
            }
            SessionWindow(startedAtUtc = session.startedAt, endedAtUtc = session.endedAt, gaps = gaps)
        }

    private fun sourceId(entity: TransmissionEntity): TransmissionId? =
        entity.attributionSourceTransmissionId?.let { runCatching { TransmissionId.parse(it) }.getOrNull() }

    private fun statusRepository(context: Context): CaptureStatusRepository {
        val heartbeatStore = FileHeartbeatStore(File(context.filesDir, "heartbeat.txt"))
        // audit F-002: `CaptureStatusRepository`'s constructor requires a `ShedController`, but
        // its output (`CaptureStatus.shedLevel`) is never read any more — `currentStatus` above
        // reads the real level/backlog from `ShedStatus` instead. Rather than a second bespoke
        // always-nominal fake object (the bug this fix removes), this reuses `:pipeline`'s own
        // `FakeShedSignals` purely to satisfy the constructor; nothing sampled from it is ever
        // shown to a user.
        val shedController = ShedController(FakeShedSignals(), SystemClock)
        return CaptureStatusRepository(heartbeatStore, shedController, SystemClock)
    }
}
