package org.ort.app.ui.data

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
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
import org.ort.core.Tier
import org.ort.core.TransmissionId
import org.ort.core.TransmissionState
import org.ort.core.Ulid
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TransmissionEntity
import org.ort.pipeline.CaptureStatusRepository
import org.ort.pipeline.capture.AsrAvailability
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RealCaptureService
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.ShedStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus
import org.ort.pipeline.capture.VadAvailability
import org.ort.pipeline.passb.LexiconLookup
import org.ort.pipeline.passb.LexiconMatch
import org.ort.pipeline.passb.RealLexiconLookup
import org.ort.pipeline.shed.FakeShedSignals
import org.ort.pipeline.shed.ShedController
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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

    // -------------------------------------------------------------------------------------------
    // Capture status (ui-conformance-plan WP4, R-031/R-032/R-034/R-035/R-038) — Capture-Status.dc.html.
    // -------------------------------------------------------------------------------------------

    /**
     * `Capture-Status.dc.html`'s full read path. Every fact is real: [ShedStatus]/[ThermalStatus]/
     * [RigStatus]/[StorageForecast] are the same process-wide holders [currentStatus] above already
     * reads, and [InputStatus]/[LevelStatus] (WP11c, register R-112/R-113) are the two that used to
     * be honestly "not measured" here — see [org.ort.app.ui.data.CaptureStatusMapper]'s own kdoc.
     *
     * R-172: [sessionId] is [effectiveSessionId]'s **fallback** argument, not necessarily the
     * session actually read — see that function's own kdoc. Before this fix, every counted fact
     * below (`Overs`, `Since`/elapsed, `Backlog`'s "not measured" gate) was read straight from the
     * caller-supplied [sessionId] regardless of what was genuinely capturing, which is exactly how
     * a real, minute-old session's Since/elapsed/Overs line ended up mixed with an unrelated fixture
     * session's counts while every process-wide-holder fact (Input/Level/Thermal/Tier) stayed
     * correctly live — those never depended on [sessionId] at all, which is why only half the
     * screen was wrong.
     */
    public suspend fun captureStatus(context: Context, sessionId: String): CaptureStatusViewState {
        val effectiveSessionId = effectiveSessionId(sessionId) ?: sessionId
        val db = OrtDatabase.create(context.applicationContext)
        val session = db.sessionDao().getById(effectiveSessionId)
        val transmissions = db.transmissionDao().listBySession(effectiveSessionId)
        val gapCount = db.captureGapDao().listBySession(effectiveSessionId).size
        val rejectedCount = transmissions.count { it.processingState == TransmissionState.REJECTED }
        val failedCount = transmissions.count { it.processingState == TransmissionState.FAILED }

        val heartbeatStore = FileHeartbeatStore(File(context.filesDir, "heartbeat.txt"))
        val lastHeartbeat = heartbeatStore.last()
        val nowMillis = SystemClock.wallMillis()
        val heartbeatSecondsAgo = lastHeartbeat?.let { (nowMillis - it.wallMillis) / 1000 }
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isAlive = lastHeartbeat != null &&
            org.ort.capture.android.heartbeat.LivenessChecker().isAlive(
                lastHeartbeat.wallMillis,
                nowMillis,
                pm.isIgnoringBatteryOptimizations(context.packageName),
            )

        val shedMeasured = CaptureState.state != CaptureState.State.Idle
        val (batteryPercent, charging) = batteryReading(context)

        return CaptureStatusMapper.from(
            captureState = CaptureState.state,
            shedLevel = if (shedMeasured) ShedStatus.currentLevel else null,
            backlog = if (shedMeasured) ShedStatus.backlog else null,
            thermal = ThermalStatus.state,
            rig = RigStatus.state,
            storage = StorageForecast.state,
            asr = AsrAvailability.state,
            vad = VadAvailability.state,
            input = InputStatus.state,
            level = LevelStatus.state,
            nowMillis = nowMillis,
            sinceLabel = session?.startedAt?.let { hourMinuteUtcLabel(it) },
            elapsedLabel = session?.let { formatElapsedShort(nowMillis - it.startedAt) } ?: "0:00",
            heartbeatSecondsAgo = heartbeatSecondsAgo,
            isAlive = isAlive,
            transmissionCount = transmissions.size,
            rejectedCount = rejectedCount,
            failedCount = failedCount,
            gapCount = gapCount,
            batteryPercent = batteryPercent,
            batteryCharging = charging,
            batteryExemptionReportsIgnoring = pm.isIgnoringBatteryOptimizations(context.packageName),
        )
    }

    /**
     * `Level-Meter.dc.html`'s "Weakest over resolved tonight" row (R-175): the lowest recorded
     * [org.ort.data.entity.TransmissionEntity.signalStrength] among this session's own overs,
     * formatted the same "S%.0f" way [TransmissionDetail]'s own `signalLabel` does everywhere else
     * — never a fabricated dBFS figure alongside it: nothing in `:pipeline` records a per-over dBFS
     * reading (only [org.ort.pipeline.capture.LevelStatus]'s own live, session-wide peak/RMS does),
     * so the honest label is the S-meter reading alone. `null` when no over this session recorded a
     * signal strength at all — the row is then honestly absent, not a fabricated zero.
     */
    public suspend fun weakestOverLabel(context: Context, sessionId: String): String? {
        val db = OrtDatabase.create(context.applicationContext)
        val weakest = db.transmissionDao().listBySession(sessionId).mapNotNull { it.signalStrength }.minOrNull()
        return weakest?.let { "S%.0f".format(Locale.ROOT, it) }
    }

    /** `%02d:%02d` in UTC — matches [ReaderTransmissionViewStateMapper]'s own row-time convention. */
    private fun hourMinuteUtcLabel(utcMillis: Long): String {
        val totalMinutes = Math.floorDiv(utcMillis, 60_000L)
        val hour = Math.floorMod(totalMinutes / 60, 24L)
        val minute = Math.floorMod(totalMinutes, 60L)
        return "%02d:%02d".format(Locale.ROOT, hour, minute)
    }

    /** `BatteryManager`'s live percent/charging state — `null` percent only when the property is
     * genuinely unreadable (constitution I: never a fabricated reading). */
    private fun batteryReading(context: Context): Pair<Int?, Boolean> {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null to false
        val capacity = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val percent = capacity.takeIf { it in 0..100 }
        return percent to bm.isCharging
    }

    // -------------------------------------------------------------------------------------------
    // Now home (ui-conformance-plan WP4, R-030/R-033/R-036/R-037) — Main/Now-Idle/Now-First.dc.html.
    // -------------------------------------------------------------------------------------------

    // R-170: Locale.ROOT has no real month-name data, so "MMM" degraded to the literal "M09"
    // rather than "Sep" — guide §9's dates are prose, read in the device's own locale, not the
    // numeric/mono formatting the guide reserves for times and frequencies (those stay
    // Locale.ROOT elsewhere in this file, deliberately unchanged by this fix).
    private val nightDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
        .withZone(ZoneId.systemDefault())

    /**
     * R-172: `CaptureState.sessionId` is the live session whenever [CaptureState.isCapturing] is
     * true, full stop — [hostSessionId] (`NowContent`/`CaptureStatusContent`'s own `sessionId`
     * parameter, fixed once at `ReaderActivity.onCreate` per that class's own kdoc) is consulted
     * only as a **fallback**, for the one case there is genuinely no live session to prefer.
     * Centralised here — both content composables' only two entry points into this object
     * (`nowViewState`, `captureStatus`) route through it — so neither can drift out of step on
     * what "the live session" means, and so a session that starts capturing *after* this reader
     * already launched (a fresh `Start capture` tap from Now, or a scenario broadcast setting
     * [CaptureState] directly while the reader is already open — R-171) is picked up on the very
     * next poll tick, never stuck showing whichever session happened to be current at launch.
     */
    public fun effectiveSessionId(hostSessionId: String?): String? =
        CaptureState.sessionId.takeIf { CaptureState.isCapturing } ?: hostSessionId

    /**
     * The "Now" home's full read path. Renders [NowViewState.Idle] whenever nothing is capturing
     * right now — see [effectiveSessionId] for how the session to read is chosen.
     */
    public suspend fun nowViewState(context: Context, sessionId: String?): NowViewState {
        val nowMillis = SystemClock.wallMillis()
        val liveSessionId = effectiveSessionId(sessionId)?.takeIf { CaptureState.isCapturing }
        return if (liveSessionId == null) {
            idleNowViewState(context)
        } else {
            activeNowViewState(context, liveSessionId, nowMillis)
        }
    }

    private suspend fun activeNowViewState(context: Context, sessionId: String, nowMillis: Long): NowViewState {
        val db = OrtDatabase.create(context.applicationContext)
        val session = db.sessionDao().getById(sessionId)
        val details = currentTransmissionDetails(context, sessionId)
        val gaps = db.captureGapDao().listBySession(sessionId).map { GapWindow(it.startedAt, it.endedAt) }

        val attributedStationIds = details.mapNotNull { it.attribution.stationId }.toSet()
        val sessionStart = session?.startedAt ?: (details.minOfOrNull { it.startedAtUtcMillis } ?: nowMillis)
        val sessionEnd = session?.endedAt
        val firstHeardStationIds = attributedStationIds.filter { stationId ->
            val firstHeardAt = db.catalogDao().getStation(stationId)?.firstHeardAt ?: return@filter false
            firstHeardAt in sessionStart..(sessionEnd ?: nowMillis)
        }.toSet()

        val listeningOnLabel = (RigStatus.state as? RigStatus.State.Connected)?.bands
            ?.mapNotNull { it.frequencyHz }
            ?.joinToString(" and ") { "%.3f".format(Locale.ROOT, it / 1_000_000.0) }
            ?.let { if (it.isEmpty()) null else "listening on $it" }

        return NowViewStateMapper.active(
            details = details,
            gaps = gaps,
            sessionStartedAtUtc = sessionStart,
            sessionEndedAtUtc = sessionEnd,
            nowMillis = nowMillis,
            firstHeardStationIds = firstHeardStationIds,
            asrAvailable = AsrAvailability.isAvailable,
            missingModel = MissingModelFacts(
                title = "No transcription model installed",
                body = "Audio is being captured and kept; every over is transcribed once one is installed.",
                actionLabel = "Install a model",
            ),
            listeningOnLabel = listeningOnLabel,
        )
    }

    private suspend fun idleNowViewState(context: Context): NowViewState {
        val db = OrtDatabase.create(context.applicationContext)
        val allSessions = db.sessionDao().listAll()
        val lastEnded = allSessions.firstOrNull { it.endedAt != null }
        val lastSessionSummaryLabel = lastEnded?.endedAt?.let { endedAt ->
            val session = lastEnded
            val overCount = db.transmissionDao().listBySession(session.id).size
            val duration = (endedAt - session.startedAt).coerceAtLeast(0)
            "Last session ended ${hourMinuteUtcLabel(endedAt)} · $overCount overs · ${durationHoursMinutes(duration)}"
        }

        val earlierNights = allSessions.take(EARLIER_NIGHTS_LIMIT).map { session -> earlierNightRow(db, session) }
        val canGetBetter = canGetBetterRow(db, allSessions)

        val rig = RigStatus.state
        val rigLabel = when (rig) {
            RigStatus.State.Absent -> null
            is RigStatus.State.Connected -> rig.descriptor
            is RigStatus.State.Stale -> rig.lastKnown.descriptor
        }

        return NowViewStateMapper.idle(
            lastSessionSummaryLabel = lastSessionSummaryLabel,
            // R-036/this package's report: no process-wide input-device holder exists to read from
            // idle (see CaptureStatusViewState's kdoc for the same gap while capturing) — honestly
            // omitted rather than guessed.
            inputLabel = null,
            rigLabel = rigLabel,
            tierLabel = null,
            earlierNights = earlierNights,
            canGetBetter = canGetBetter,
        )
    }

    private suspend fun earlierNightRow(db: OrtDatabase, session: SessionEntity): EarlierNightRow {
        val overCount = db.transmissionDao().listBySession(session.id).size
        val stationCount = db.transmissionDao().listBySession(session.id)
            .mapNotNull { it.stationId }.toSet().size
        val gapCount = db.captureGapDao().listBySession(session.id).size
        val endedLabel = session.endedAt?.let { hourMinuteUtcLabel(it) } ?: "still running"
        val startedLabel = hourMinuteUtcLabel(session.startedAt)
        return EarlierNightRow(
            sessionId = session.id,
            title = "Overnight, ${nightDateFormat.format(java.time.Instant.ofEpochMilli(session.startedAt))}",
            subLine = "$startedLabel – $endedLabel · $overCount overs · $stationCount stations",
            gapsLabel = if (gapCount > 0) "$gapCount gap" + (if (gapCount == 1) "" else "s") else null,
        )
    }

    /**
     * R-036: "Can get better" when any recorded session's [SessionEntity.deviceTier] is a real,
     * parseable [Tier] — every such session was, by definition, captured below full capability
     * (nothing writes a non-null `deviceTier` for a full-capability session — see
     * `RealCaptureService.runCaptureFlow`, which always writes `null`). WP10 owns the Improve
     * destination itself (register R-107); this only builds the row and its honest count.
     */
    private suspend fun canGetBetterRow(db: OrtDatabase, sessions: List<SessionEntity>): CanGetBetterRow? {
        val qualifyingWithTier = sessions.mapNotNull { session ->
            val tier = session.deviceTier?.let { runCatching { Tier.valueOf(it) }.getOrNull() }
            if (tier != null) session to tier else null
        }
        if (qualifyingWithTier.isEmpty()) return null
        val overCount = qualifyingWithTier.sumOf { (session, _) -> db.transmissionDao().listBySession(session.id).size }
        val lowestTier = qualifyingWithTier.map { (_, tier) -> tier }.minByOrNull { it.ordinal }
        return CanGetBetterRow(
            headline = "$overCount overs were processed below this phone's capability",
            subLine = "captured at ${lowestTier?.name ?: "a lower"} tier · open Improve to reprocess",
        )
    }

    private fun durationHoursMinutes(millis: Long): String {
        val totalMinutes = millis / 60_000
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return "$h h $m m"
    }

    private const val EARLIER_NIGHTS_LIMIT = 3

    // -------------------------------------------------------------------------------------------
    // Starting capture from the reader (ui-conformance-plan WP4, R-036: Now-Idle's Start capture).
    // -------------------------------------------------------------------------------------------

    /**
     * `Now-Idle.dc.html`'s `Start capture` button — starts [RealCaptureService] exactly as
     * [org.ort.app.MainActivity.startCaptureAndShowStatus] does (this package's brief's own words):
     * the same same-process liveness guard (never mint a second session id while one is already
     * capturing), the same [RealCaptureService.EXTRA_SESSION_ID] extra, the same
     * `startForegroundService`/`startService` split by SDK level.
     */
    public fun startCapture(context: Context): String {
        val liveSessionId = CaptureState.sessionId.takeIf { CaptureState.isCapturing }
        if (liveSessionId != null) return liveSessionId
        val newSessionId = Ulid.generate().value
        val intent = Intent(context, RealCaptureService::class.java)
            .putExtra(RealCaptureService.EXTRA_SESSION_ID, newSessionId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        return newSessionId
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

    // R-round-two coordinator note: listStationSummaries/listFrequencySummaries/stationDetail/
    // frequencyDetail (and the activityPatternForEverySession/dayOfWeekPatternForEverySession/
    // weekOverWeekComparisonForEverySession/everySessionWindow helpers that existed only to serve
    // them) moved to WP8's own `ui/data/StationPolling.kt`, which now owns every "every session"
    // read — removed here rather than left as dead/duplicated code (WP3 confirmed nothing in the
    // nav host still calls the copies that used to live in this file).

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
