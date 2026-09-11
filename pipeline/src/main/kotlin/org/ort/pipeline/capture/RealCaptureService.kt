package org.ort.pipeline.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.ort.capture.android.AndroidAudioIo
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioDeviceKind
import org.ort.capture.android.AudioIo
import org.ort.capture.android.AudioRecordSource
import org.ort.capture.android.BackoffLadder
import org.ort.capture.android.GapRecord
import org.ort.capture.android.GapTracker
import org.ort.capture.android.codec.DeflatePredictiveCodec
import org.ort.capture.android.codec.FlacStore
import org.ort.capture.android.heartbeat.FileHeartbeatStore
import org.ort.capture.android.heartbeat.HeartbeatRecord
import org.ort.capture.android.heartbeat.UncleanEndDetector
import org.ort.capture.android.service.CaptureNotificationBuilder
import org.ort.capture.android.service.CaptureNotificationContent
import org.ort.capture.android.service.CaptureNotificationExpandedFacts
import org.ort.captureapi.CaptureEvent
import org.ort.core.AttributionState
import org.ort.core.PassId
import org.ort.core.SampleClock
import org.ort.core.SystemClock
import org.ort.core.TransmissionState
import org.ort.core.Ulid
import org.ort.core.capture.AudioRouteKind
import org.ort.data.OrtDatabase
import org.ort.data.WorkQueue
import org.ort.data.dao.WorkQueueDao
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TerminationReason
import org.ort.data.entity.TransmissionEntity
import org.ort.pipeline.CaptureProcessingLoop
import org.ort.pipeline.GapPersister
import org.ort.pipeline.Pass
import org.ort.pipeline.PassDrainRunner
import org.ort.pipeline.diagnostics.DiagnosticsLog
import org.ort.pipeline.passb.AsrEngineAvailability
import org.ort.pipeline.passb.PassBFactory
import org.ort.pipeline.passb.RealAsrEngineProvider
import org.ort.pipeline.passb.UnavailableAsrEngine
import org.ort.pipeline.reprocess.ReprocessRunner
import org.ort.pipeline.reprocess.SafePass
import org.ort.pipeline.rig.CaptureConfiguration
import org.ort.pipeline.rig.CaptureConfigurationStore
import org.ort.pipeline.rig.DefaultRigTransportFactory
import org.ort.pipeline.rig.FrequencyReading
import org.ort.pipeline.rig.RigSupervisor
import org.ort.pipeline.rig.RigTransportFactory
import org.ort.pipeline.rig.SharedPreferencesCaptureConfigurationStore
import org.ort.pipeline.shed.AndroidShedSignals
import org.ort.pipeline.shed.ShedController
import org.ort.pipeline.shed.ShedEventPersister
import org.ort.pipeline.shed.ShedSignals
import org.ort.segment.FrameSpec
import org.ort.segment.SegmentConfig
import org.ort.segment.SegmentId
import org.ort.segment.SegmentOutcome
import org.ort.segment.SegmentRecord
import org.ort.segment.SegmentSink
import org.ort.segment.SegmentWriter
import org.ort.segment.Segmenter
import org.ort.segment.SileroVad
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * **This is a v0 smoke-test wiring, not a build-plan prompt's output.** Every piece it composes
 * (`AudioRecordSource`, `Segmenter`, `WorkQueue`, `OrtDatabase`) was built test-first by P4/P5/P8
 * and is exercised on Robolectric there; what didn't exist before this class is the glue that
 * actually runs them together against a real microphone on a real device, which
 * `:capture-android`'s own `CaptureService` doc comment names as `:pipeline`'s job ("wiring
 * capture into the queue"). Two real gaps this fills in, deliberately, for exactly this purpose:
 *
 * - **VAD**: a real Silero VAD binding does exist (`com.k2fsa.sherpa.onnx.Vad`, in the same
 *   `sherpa-onnx-jvm` jar `RealSherpaDecoder` uses — see `asr-sherpa/README.md`), wrapped by
 *   [RealSileroVad][org.ort.asrsherpa.real.RealSileroVad] and wired here via [RealVadProvider].
 *   What this repo does not have is the model file itself — [EnergyVadModel] (a simple RMS-energy
 *   threshold, wrapped in the real [SileroVad] hysteresis logic) is the fallback when
 *   [RealVadProvider] finds no model installed, and [VadAvailability] records honestly which one
 *   is actually running (constitution I — never silently one pretending to be the other).
 * - **Route/interruption handling**: [AndroidAudioIo] (see its own doc comment) is a real but
 *   minimal `AudioIo` — no proactive route-change callback, relies on read-error detection.
 *
 * Neither of these is claimed as AC-2/AC-3/AC-46/etc.'s device verification (that's P9's job on
 * the reference device with the real VAD once it exists) — this exists to prove the *plumbing*
 * moves real audio into a real durable queue on real hardware, which is the thing no test suite
 * in this repo can verify.
 */
public class RealCaptureService : Service() {

    /**
     * audit F-011: the seam that lets a test start this real [Service] under Robolectric's
     * `ServiceController` and still substitute fakes for the four genuinely-Android/IO-bound
     * collaborators [startCapture] used to construct directly — [OrtDatabase], the
     * [org.ort.capture.android.AudioIo] + selected device pair, the [AsrEngineAvailability]
     * lookup and [org.ort.pipeline.shed.ShedSignals]. Deliberately a plain settable field, not
     * Hilt (see the finding): a test sets it after `onCreate()` (which never touches it) and
     * before delivering the start intent that calls [startCapture]. Every default here is the
     * exact real construction this class already did — production behaviour is unchanged.
     */
    internal var dependencies: Dependencies = Dependencies()

    /** See [dependencies]'s kdoc. */
    internal data class Dependencies(
        val database: (android.content.Context) -> OrtDatabase = { OrtDatabase.create(it) },
        val audioIo: (android.content.Context) -> Pair<AudioIo, AudioDeviceDescriptor>? = { ctx ->
            val io = AndroidAudioIo(ctx)
            io.defaultInputDevice()?.let { device -> io to device }
        },
        val asrEngine: (File) -> AsrEngineAvailability = { RealAsrEngineProvider(it).provide() },
        val shedSignals: (android.content.Context, WorkQueueDao, File) -> ShedSignals =
            { ctx, dao, dir -> AndroidShedSignals(ctx, dao, dir) },
        /**
         * R-104/F7: `PowerManager.getThermalStatus()` is API 29+ only; below that the OS exposes
         * nothing and [ThermalStatus.THERMAL_STATUS_NONE] is reported honestly rather than
         * invented (constitution I). A settable seam, the same reason every other producer here is
         * one — a Robolectric test can substitute a fixed reading without a real thermal sensor.
         */
        val osThermalStatus: (android.content.Context) -> Int = { ctx ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                (ctx.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager).currentThermalStatus
            } else {
                ThermalStatus.THERMAL_STATUS_NONE
            }
        },
        /**
         * WPC2 (FR-CAP-12, AC-131): where the mode/rig choice for THIS session start is read from
         * — see [CaptureConfigurationStore]'s own kdoc. The real, `SharedPreferences`-backed store
         * uses a fixed, well-known preferences file
         * ([SharedPreferencesCaptureConfigurationStore.PREFS_NAME]) so `:app`'s settings screens
         * (WPE) can open the *same* file with their own store instance and write to it — this is a
         * shared, file-backed contract, not an object passed around between modules that cannot
         * see each other (module graph, constitution VII).
         */
        val captureConfigurationStore: (android.content.Context) -> CaptureConfigurationStore = { ctx ->
            SharedPreferencesCaptureConfigurationStore(
                ctx.getSharedPreferences(
                    SharedPreferencesCaptureConfigurationStore.PREFS_NAME,
                    android.content.Context.MODE_PRIVATE,
                ),
            )
        },
        /** WPC2's [RigTransportFactory] seam (FR-RIG-13/14) — see [RigSupervisor]'s own kdoc. */
        val rigTransportFactory: (android.content.Context) -> RigTransportFactory = { ctx ->
            DefaultRigTransportFactory(ctx)
        },
    )

    private var wakeLock: PowerManager.WakeLock? = null
    private var source: AudioRecordSource? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var sessionId: String = ""
    private var transmissionCount = 0
    private var startedAtWallMillis = 0L
    private var startedAtMonotonicNanos = 0L
    private var startedAtUtcOffsetMinutes = 0
    private lateinit var heartbeatStore: FileHeartbeatStore

    // R-102: the notification's expanded rows need a DB read (last over, frequencies seen) and the
    // selected device's own label -- neither is otherwise available outside startCapture()'s local
    // scope. Nullable because both start unset (before capture ever starts, and in tests that never
    // call startCapture()).
    private var db: OrtDatabase? = null
    private var selectedDeviceLabel: String? = null

    // audit F-005: the only way onHeartbeat() can report the real sample position instead of a
    // fabricated 0L -- the segmenter is the one thing in this class that knows how much audio has
    // actually been fed to it (Segmenter.position(), unchanged in :segment).
    private var segmenter: Segmenter? = null

    // WPC2 (FR-CAP-12/13, AC-129/AC-131): the configuration read ONCE at this session's start via
    // CaptureConfigurationStore.activateForNewSession() and never re-read until the NEXT session
    // starts -- a mode/rig change written to the store mid-session must not alter the running
    // session (see startCapture()'s own comment at the read site).
    private var activeConfiguration: CaptureConfiguration = CaptureConfiguration.DEFAULT

    // WPC2: builds/owns the live RigModule for this session and republishes RigStatus
    // (Connected/Stale/Absent) as it changes -- see RigSupervisor's own kdoc for why a rig-link
    // drop (FR-RIG-15) never touches gapRelay/GapPersister the way an audio-route drop does.
    private var rigSupervisor: RigSupervisor? = null

    // R-113: captured once at open so every InputStatus republish (route change, device loss,
    // resume) reuses the same expected device and open timestamp rather than re-deriving them.
    // io/device are locals inside startCapture(); these are the fields the event handlers in
    // runCaptureFlow() read instead.
    private var selectedInputDevice: AudioDeviceDescriptor? = null
    private var currentAudioIo: AudioIo? = null
    private var inputOpenedAtWallMillis: Long = 0L
    private var inputRouteConfirmedThisSession = false

    // R-173: every path that ends a session (stopCaptureInternal for both a clean and an unclean
    // stop, the storage floor, a route-mismatch/other Failed halt, the "flow ended unexpectedly"
    // fallback) calls endSessionRow() -- guarded by this flag so a session is only ever closed
    // once, by whichever path gets there first. A real risk without it: stopCapture() (ACTION_STOP)
    // calls stopCaptureInternal(markClean = true), then stopSelf() asynchronously triggers
    // onDestroy() -> stopCaptureInternal(markClean = false) a second time for the SAME session --
    // the second call must not overwrite an honest USER-stop with KILLED.
    private var sessionEndRecorded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ort:real-capture")
        ensureChannel()
        heartbeatStore = FileHeartbeatStore(File(filesDir, "heartbeat.txt"))
        // FR-OBS-1: the one place this service's process configures where its four diagnostics
        // logs live -- see DiagnosticsLog's own kdoc for why this is a plain File, not the :app
        // DiagnosticsLogPaths type the bundle producer reads back with.
        DiagnosticsLog.configure(filesDir)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }
        // A foreground service is a singleton per process — relaunching MainActivity (e.g. the
        // app icon tapped again while this is already running) sends a second start command with
        // a *different* session id. Without this guard that would start a second concurrent
        // AudioRecordSource/Segmenter/coroutine against the same microphone, writing two
        // overlapping sessions into :data at once. Found by on-device testing.
        if (source != null) return START_STICKY
        sessionId = intent?.getStringExtra(EXTRA_SESSION_ID) ?: Ulid.generate().value
        wakeLock?.let { if (!it.isHeld) it.acquire(WAKE_LOCK_TIMEOUT_MILLIS) }
        // Anchored together, once, at session start (FR-RUN-15/16): every transmission's
        // timestamps are derived from this anchor plus its sample position, never from a fresh
        // wall-clock read at persist time (constitution I).
        startedAtWallMillis = SystemClock.wallMillis()
        startedAtMonotonicNanos = SystemClock.monotonicNanos()
        startedAtUtcOffsetMinutes = SystemClock.utcOffsetMinutes()
        startForeground(NOTIFICATION_ID, notification(0))
        startCapture()
        return START_STICKY
    }

    override fun onDestroy() {
        stopCaptureInternal(markClean = false)
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    private fun startCapture() {
        // FR-OBS-1: the service-lifecycle log's first line for this session.
        DiagnosticsLog.logServiceStarted(sessionId)
        val db = dependencies.database(applicationContext)
        this.db = db
        sessionEndRecorded = false
        val queue = WorkQueue(db, SystemClock)
        val gapPersister = GapPersister(db.captureGapDao(), SystemClock)
        persistUncleanEndGapIfAny(db, gapPersister)

        // WPC2 (FR-CAP-12, AC-131): read ONCE, here, before anything about this session is
        // decided -- activateForNewSession() promotes a pending settings change (written while a
        // PREVIOUS session was capturing) to current and clears it; the value returned is frozen
        // for this whole session (assigned to the field, never read from the store again until the
        // next startCapture() call).
        activeConfiguration = dependencies.captureConfigurationStore(applicationContext).activateForNewSession()

        // WPC2 (FR-RIG-2/3/4/6/7/13/14/15): builds the chosen rig (or the null module, honestly,
        // when none is configured) and republishes RigStatus as it connects/drops/reconnects. See
        // RigSupervisor's own kdoc for why a rig-link drop never opens a CaptureGap.
        val supervisor = RigSupervisor(dependencies.rigTransportFactory(applicationContext), scope)
        rigSupervisor = supervisor
        supervisor.connect(activeConfiguration)

        // A real enumerated device — never a fabricated descriptor, which RouteVerifier would
        // (correctly) reject on the first read, halting capture. See defaultInputDevice()'s kdoc.
        val (io, device) = dependencies.audioIo(applicationContext) ?: run {
            CaptureState.failed("no audio input device is available")
            updateNotification(CaptureNotificationContent.State.FAILED)
            rigSupervisor?.disconnect()
            rigSupervisor = null
            return
        }
        io.select(device)
        selectedDeviceLabel = device.label
        val audioSource = AudioRecordSource(io, device)
        source = audioSource
        CaptureState.capturing(sessionId)

        // R-113: published honestly as "not yet verified" here -- the OS has not actually routed
        // anything until AudioRecordSource's first successful read confirms it. runCaptureFlow's
        // CaptureEvent.Frames branch republishes this with routeVerified/routedDeviceMatches true
        // the moment that first read succeeds (AudioRecordSource never emits Frames before its own
        // route check passes -- see that class's kdoc).
        currentAudioIo = io
        selectedInputDevice = device
        inputOpenedAtWallMillis = SystemClock.wallMillis()
        inputRouteConfirmedThisSession = false
        InputStatus.opened(
            descriptor = device,
            nativeRateHz = audioSource.deviceFormat.sampleRate,
            resamplerId = resamplerIdLabel(audioSource.resamplerIdentity),
            routeVerified = false,
            routedDeviceMatches = false,
            openedAtMillis = inputOpenedAtWallMillis,
        )

        // audit F-028: before this, nothing in the running service constructed a GapTracker --
        // AudioRecordSource emitted Interrupted/Resumed (and, after F-010, a dropped-span cause)
        // but no one joined them to CaptureGapDao (FR-RUN-12, AC-48; constitution IV). Reuses the
        // [gapPersister] constructed above for the F5 check -- one instance, two callers.
        //
        // WPC3 (FR-CAP-5, F23): [selectedInputDevice] is read at PERSIST time, not captured here --
        // it is a `var` field AudioRecordSource's own route-change handling can update mid-session,
        // and a gap this relay persists must reflect the route that actually dropped, not whatever
        // was selected when the relay was constructed.
        val gapRelay = CaptureGapRelay(GapTracker(SystemClock)) { gap ->
            gapPersister.persist(
                sessionId,
                gap,
                isBluetoothAudioRoute = selectedInputDevice?.kind == AudioDeviceKind.BLUETOOTH,
            )
        }

        startShedMonitor(db)

        // The processing loop (build-plan P12, defect 3): drains whatever RealSegmentSink
        // enqueues, independent of the capture flow below -- a stalled or unavailable ASR engine
        // must never block capture (constitution IV). Its own coroutine in the same service-scoped
        // `scope`, cancelled by the same `scope.cancel()` onDestroy() already calls.
        scope.launch { startProcessingLoop(db, queue) }

        scope.launch { runCaptureFlow(db, queue, audioSource, gapRelay) }
    }

    /**
     * F5 (register R-106, OS_STOPPED): detect an unclean end from the PREVIOUS launch's heartbeat
     * before this session's own audio loop can write a fresh one over the same file, and persist a
     * gap on that PREVIOUS session — never on the session about to start. Equivalent to
     * `org.ort.pipeline.CaptureStatusRepository.uncleanEndFromPreviousLaunch()` (that class is
     * outside this package's file ownership — see this package's report) — both are exactly
     * `UncleanEndDetector(heartbeatStore).detect()`. Deliberately synchronous, before anything in
     * [startCapture] can write to [heartbeatStore]. Does not "reopen" the previous session
     * (`Fail-Killed.dc.html`'s wording); that policy call is left to the lead.
     *
     * **FR-RUN-16 (register R-173)**: the same detection also closes the PREVIOUS session's row —
     * `endedAt` = its last heartbeat, flagged unclean ([TerminationReason.KILLED]) — via
     * [SessionDao.closeIfStillOpen][org.ort.data.dao.SessionDao.closeIfStillOpen], which only
     * writes when that row is genuinely still open (`endedAt IS NULL`): if `stopCaptureInternal`
     * already closed it correctly (e.g. an unclean `onDestroy` that still ran, just without
     * `ACTION_STOP`), this must not clobber that more-precise timestamp with a stale heartbeat.
     */
    private fun persistUncleanEndGapIfAny(db: OrtDatabase, gapPersister: GapPersister) {
        val previousLaunchGap = UncleanEndDetector(heartbeatStore).detect() ?: return
        val detectedAtWallMillis = SystemClock.wallMillis()
        val detectedAtMonotonicNanos = SystemClock.monotonicNanos()
        // FR-OBS-1: "the F5 evidence" lifecycle.log's board clause names -- a killed-restart gap,
        // logged against the PREVIOUS session, the same one the gap/session rows below are closed
        // against.
        DiagnosticsLog.logUncleanRestart(
            previousSessionId = previousLaunchGap.sessionId,
            gapMillis = (detectedAtWallMillis - previousLaunchGap.lastHeartbeatWallMillis).coerceAtLeast(0L),
        )
        scope.launch {
            gapPersister.persist(
                previousLaunchGap.sessionId,
                GapRecord(
                    // Monotonic bounds are not read back by GapPersister.persist (see its own
                    // kdoc) -- carried only because GapRecord requires them.
                    startMonotonicNanos = detectedAtMonotonicNanos,
                    endMonotonicNanos = detectedAtMonotonicNanos,
                    startWallMillis = previousLaunchGap.lastHeartbeatWallMillis,
                    endWallMillis = detectedAtWallMillis,
                    cause = OS_STOPPED_GAP_CAUSE,
                ),
            )
            db.sessionDao().closeIfStillOpen(
                id = previousLaunchGap.sessionId,
                endedAt = previousLaunchGap.lastHeartbeatWallMillis,
                terminationReason = TerminationReason.KILLED,
            )
        }
    }

    /**
     * audit F-007: before this, nothing in the running service ever constructed a ShedController
     * against a real ShedSignals, so FR-RUN-3's shed order could never actually trigger. Ticks a
     * real ShedController every SHED_SAMPLE_INTERVAL_MILLIS, persists each level transition
     * (FR-RUN-5) and republishes level+backlog through ShedStatus (FR-STO-4, FR-RUN-6); R-104/
     * R-105 add ThermalStatus/StorageForecast to the same tick — see [runShedMonitor]'s own kdoc.
     */
    private fun startShedMonitor(db: OrtDatabase) {
        val shedSignals = dependencies.shedSignals(applicationContext, db.workQueueDao(), filesDir)
        val shedController = ShedController(shedSignals, SystemClock)
        val shedRelay = ShedEventRelay(
            controller = shedController,
            sessionId = sessionId,
            persister = ShedEventPersister(db.shedEventDao(), SystemClock),
            samplePosition = { segmenter?.position() ?: 0L },
        )
        // R-105: the baseline this session's own storage growth is measured against -- taken once,
        // here, before the tick loop starts, so StorageForecast measures only what THIS session has
        // written, not the whole retained archive's pre-existing size.
        val audioDirectoryBytesAtSessionStart = audioDirectoryBytes()
        scope.launch { runShedMonitor(shedSignals, shedController, shedRelay, audioDirectoryBytesAtSessionStart) }
    }

    /** The session row, the segmenter, and the capture-event loop that feeds both it and [gapRelay]. */
    private suspend fun runCaptureFlow(
        db: OrtDatabase,
        queue: WorkQueue,
        audioSource: AudioRecordSource,
        gapRelay: CaptureGapRelay,
    ) {
        db.sessionDao().insert(
            SessionEntity(
                id = sessionId,
                startedAt = startedAtWallMillis,
                endedAt = null,
                profileId = null,
                deviceTier = null,
                appVersion = "smoke-test",
                terminationReason = null,
                sourceId = null,
                schemaVersion = OrtDatabase.SCHEMA_VERSION,
                // WPC2 (FR-CAP-13, AC-129): every session records its capture mode, audio route
                // and rig transport at start -- from activeConfiguration (frozen this session
                // start, FR-CAP-12) and the actual device the OS opened (audioSource.selectedDevice
                // is not exposed; the device this session actually opened with is captured in
                // startCapture() as [selectedInputDevice]).
                captureMode = activeConfiguration.mode.name,
                audioRouteKind = selectedInputDevice?.let { audioRouteKindFor(it.kind) }?.name,
                audioRouteLabel = selectedInputDevice?.label,
                bluetoothProfile = selectedInputDevice?.bluetoothProfile?.name,
                // NOTE (see this package's report): stored as :rig's RigTransportKind.name, a
                // superset of :core's own (mirrored) enum -- SessionEntity's kdoc says ":core's
                // RigTransportKind.name", but :core's type cannot express BLE/NETWORK, which a real
                // CaptureConfiguration can carry. USB_SERIAL/BLUETOOTH_SPP -- the two values that
                // exist in both enums -- read identically either way.
                rigTransport = activeConfiguration.rigTransportKind?.name,
            ),
        )

        val builtSegmenter = buildSegmenter(db, queue)
        segmenter = builtSegmenter

        var lastHeartbeatAt = 0L
        audioSource.start().collect { event ->
            // Fed through unconditionally, ahead of the exhaustive `when` below -- GapTracker
            // itself ignores every CaptureEvent shape it doesn't care about (audit F-028).
            gapRelay.onEvent(event)
            when (event) {
                is CaptureEvent.Frames -> {
                    builtSegmenter.onAudio(shortsToFloats(event.pcm))
                    publishLevelStatus(audioSource)
                    if (!inputRouteConfirmedThisSession) {
                        // AudioRecordSource never emits Frames before its own first-read route
                        // check has already passed (see that class's kdoc) -- this is the
                        // accurate "verified" signal, not an assumption.
                        inputRouteConfirmedThisSession = true
                        refreshInputStatusFromRoute(audioSource)
                    }
                    val now = SystemClock.wallMillis()
                    if (now - lastHeartbeatAt >= HEARTBEAT_INTERVAL_MILLIS) {
                        lastHeartbeatAt = now
                        onHeartbeat()
                    }
                }
                is CaptureEvent.Failed -> {
                    CaptureState.failed(event.error)
                    // R-113: the only other path (besides RouteChanged) that can land on a route
                    // mismatch -- AudioRecordSource's very first read can fail verification before
                    // any RouteChanged event was ever pending. See RouteVerifier's message shape.
                    if (event.error.startsWith(ROUTE_MISMATCH_ERROR_PREFIX)) {
                        refreshInputStatusFromRoute(audioSource)
                    }
                    // R-173: covers the route-mismatch halt and every other Failed reason alike --
                    // no TerminationReason names "route mismatch" specifically (the closed enum is
                    // USER/CRASH/KILLED/STORAGE/UNKNOWN), so UNKNOWN is the honest closest fit,
                    // flagged here rather than silently picking a more specific value that would
                    // overclaim (see this package's report).
                    endSessionRow(TerminationReason.UNKNOWN)
                    updateNotification(CaptureNotificationContent.State.FAILED)
                }
                CaptureEvent.RouteChanged -> refreshInputStatusFromRoute(audioSource)
                is CaptureEvent.Interrupted -> {
                    CaptureState.interrupted(event.cause)
                    // F23 (WPC3): the first retry's own ladder numbers, from the real
                    // BackoffLadder AudioRecordSource itself retries on -- see InputStatus.lost's
                    // own kdoc for why this is a one-time snapshot, not a live countdown
                    // (AudioRecordSource.recoverFromInterruption() is a private loop with no
                    // per-attempt signal past this first one -- :capture-android/:capture-api
                    // are out of this package's file ownership; see this package's report).
                    InputStatus.lost(
                        sinceMillis = SystemClock.wallMillis(),
                        attempt = 1,
                        ofTotal = RECONNECT_LADDER_STEPS,
                        nextRetryInMillis = BackoffLadder.delayMillisFor(1),
                    )
                    DiagnosticsLog.logInputLost(SystemClock.wallMillis())
                    // FR-OBS-1 "overruns": F-010's dropped-span encoding is the one Interrupted
                    // cause this file can actually attribute a real duration to -- see
                    // droppedSamplesMillisOrNull's own kdoc for why this duplicates GapPersister's
                    // stable-prefix technique instead of importing capture-android's internal
                    // DroppedSpanCause.
                    droppedSamplesMillisOrNull(event.cause)?.let { DiagnosticsLog.logOverrun(it) }
                    updateNotification(CaptureNotificationContent.State.INTERRUPTED)
                }
                CaptureEvent.Resumed -> {
                    CaptureState.capturing(sessionId)
                    refreshInputStatusFromRoute(audioSource)
                    updateNotification(CaptureNotificationContent.State.CAPTURING)
                }
                CaptureEvent.EndOfStream -> Unit
            }
        }
        // The flow completing means the source stopped for good — halted on a route mismatch
        // or an unrecoverable failure. Never leave the surface claiming "Capturing".
        if (CaptureState.isCapturing) {
            CaptureState.failed("capture stopped unexpectedly")
            endSessionRow(TerminationReason.UNKNOWN)
        }
    }

    /**
     * FR-RUN-15/16/18: the session's [SampleClock] is anchored once, here, from the same
     * wall/monotonic/offset triple captured together at session start -- never re-read per
     * segment. Real Silero VAD when a model is installed, the RMS-energy stand-in otherwise --
     * never silently one pretending to be the other (build-plan P12; see VadAvailability).
     */
    private fun buildSegmenter(db: OrtDatabase, queue: WorkQueue): Segmenter {
        val segmentConfig = SegmentConfig()
        val sampleClock = SampleClock(
            anchorMonotonicNanos = startedAtMonotonicNanos,
            anchorWallMillis = startedAtWallMillis,
            anchorUtcOffsetMinutes = startedAtUtcOffsetMinutes,
            sampleRate = FrameSpec.SAMPLE_RATE,
        )
        val sink = RealSegmentSink(
            filesDir,
            sessionId,
            db,
            queue,
            sampleClock,
            segmentConfig,
            // WPC3 (FR-RIG-6/8/9, D23): every transmission's frequency, with its provenance -- read
            // from whichever RigSupervisor this session built, band-scoped by whichever band's
            // squelch actually opened at the transmission's start (RigSupervisor.bandAtTransmissionStart's
            // own kdoc states the exact rule for a single band open / neither open / both open).
            // `null` when nothing is dual-band (every non-TH-D75A rig, and the null module) --
            // frequencyForTransmission(band = null, ...) is then exactly the pre-existing,
            // unscoped-history behaviour. When both bands were open, the reading's own
            // `changedDuringTransmission` flag (FR-RIG-6, reused rather than adding a second one)
            // is forced true to mark the attribution as ambiguous, regardless of whether the
            // frequency itself moved.
            frequencyProvider = { startNanos, endNanos ->
                val supervisor = rigSupervisor
                if (supervisor == null) {
                    FrequencyReading.UNKNOWN
                } else {
                    val resolved = supervisor.bandAtTransmissionStart(startNanos)
                    val reading = supervisor.frequencyForTransmission(resolved.band, startNanos, endNanos)
                    if (resolved.ambiguous) reading.copy(changedDuringTransmission = true) else reading
                }
            },
        ) {
            transmissionCount++
            onHeartbeat()
        }
        val vadResult = RealVadProvider.provide(filesDir)
        val vadModel = when (vadResult) {
            is VadProvisionResult.Available -> {
                VadAvailability.real()
                vadResult.vad
            }
            is VadProvisionResult.Unavailable -> {
                VadAvailability.stub(vadResult.reason)
                EnergyVadModel()
            }
        }
        return Segmenter(segmentConfig, SileroVad(vadModel), sink)
    }

    /**
     * Build-plan P12, defect 3: before this, `PassDrainRunner` (P8), `PassB` (P11) and
     * `RealSherpaDecoder` (P10 follow-up) all existed and none was constructed anywhere in the
     * running app -- a captured, enqueued transmission sat `CAPTURED` forever. [RealAsrEngineProvider]
     * only ever reads app-private storage (constitution V: no network in the processing path) --
     * fetching the model there is a separate, user-initiated action through `:net`, out of this
     * session's scope (see CHANGELOG). When no model is installed, [AsrAvailability] is set to
     * [AsrAvailability.State.Unavailable] and the queue still drains against [UnavailableAsrEngine]
     * so a rejection/failure reason is recorded honestly rather than nothing happening at all.
     *
     * **Audit F-025 (2026-09-07, recorded not fixed):** this loop only exists as long as
     * [RealCaptureService] does -- it is a coroutine in the service-scoped `scope`, cancelled by
     * `scope.cancel()` in [onDestroy] alongside everything else. There is no `WorkManager` job or
     * any other scheduler that resumes draining after the service stops. FR-RUN-2 still holds --
     * the backlog sits in the durable [WorkQueue] and nothing is lost, process death included --
     * but a backlog left behind when capture stops waits for the *next* capture session to start
     * before it drains further, and that wait is currently invisible to the user. Building a
     * post-capture drain (WorkManager or equivalent) is out of scope here: it is M8 streaming/M10
     * reprocessing work, not something to bolt on ad hoc from the capture-wiring prompt that owns
     * this file. See CHANGELOG.md's F-025 entry.
     */
    private suspend fun startProcessingLoop(db: OrtDatabase, queue: WorkQueue) {
        val availability = dependencies.asrEngine(filesDir)
        val (engine, modelRef, provider) = when (availability) {
            is AsrEngineAvailability.Available -> {
                AsrAvailability.available(availability.modelRef.canonical)
                Triple(availability.engine, availability.modelRef, availability.provider)
            }
            is AsrEngineAvailability.Unavailable -> {
                AsrAvailability.unavailable(availability.reason)
                updateNotification(CaptureNotificationContent.State.ASR_UNAVAILABLE)
                // audit F-013: no engine ran at all here, so the fingerprint's provider must say
                // so honestly ("none") rather than repeating the real engine's "cpu".
                Triple(UnavailableAsrEngine(availability.reason), org.ort.core.AssetRef("asr-unavailable", "0"), "none")
            }
        }
        // F7 (register R-104): ThermalStatus's real-time factor must be the wall time of a real
        // pass run over its segment's real audio duration -- never invented. Measured here, by
        // wrapping the pass RealCaptureService already constructs, rather than inside
        // CaptureProcessingLoop/PassDrainRunner: neither of those two files is in this package's
        // ownership (see this package's report for why this is deliberate, not an oversight).
        //
        // register R-290 (live-capture round): SafePass is the OUTERMOST wrapper, around
        // ThermalTrackingPass rather than only inside it, so a pass's own exception -- a missing
        // retained-audio file being the reproduced case -- is converted to PassRunOutcome.Errored
        // before it ever reaches CaptureProcessingLoop/WorkQueue.runLeased. This scope is a plain
        // CoroutineScope(Dispatchers.IO + Job()), not a SupervisorJob: an exception left uncaught
        // here would cancel this Job and, with it, the sibling runCaptureFlow coroutine actually
        // recording audio -- exactly the "capture must never block on or die from inference"
        // failure the constitution names. The item still ends FAILED with lastError set; capture
        // itself must never even notice a Pass B item failed.
        val pass = SafePass(ThermalTrackingPass(PassBFactory.create(filesDir, db, engine, modelRef, provider), db))
        CaptureProcessingLoop(PassDrainRunner(queue, runId = sessionId), pass).runForever()
    }

    /**
     * audit F-007: the loop FR-RUN-3/5/6 needed and never had. Runs for as long as [source] is
     * set (i.e. capture is actually running for this session) — cancelled either by that guard or
     * by `scope.cancel()` on service destruction, whichever comes first.
     *
     * Order matters within a tick: the backlog is refreshed, then the controller is sampled (so
     * its shed-level decision sees the fresh count), then any new transitions are persisted and
     * republished, and only then is the storage floor checked — a floor breach's loud stop should
     * reflect the same tick's shed level, not a stale one from before this tick ran. R-104/R-105:
     * [ThermalStatus] and [StorageForecast] are sampled/updated on this same tick, beside the
     * existing floor check, so a caller never reads a thermal/storage reading from a different
     * moment than the shed level next to it.
     */
    private suspend fun runShedMonitor(
        signals: ShedSignals,
        controller: ShedController,
        relay: ShedEventRelay,
        audioDirectoryBytesAtSessionStart: Long,
    ) {
        // FR-OBS-1 "tier changes with their cause": the same shed-level -> Tier mapping
        // ReprocessRunner.currentTierFromShedLevel() already applies to decide what a reprocess
        // pass runs at -- reused here rather than duplicated, so live capture and reprocessing can
        // never disagree about what shed level N means. "Cause" is the shed level itself: the one
        // real fact this loop has for why the tier moved.
        var previousTier = ReprocessRunner.currentTierFromShedLevel()
        while (source != null) {
            // refreshBacklog() is AndroidShedSignals's own live-read step (queueBacklog() then
            // returns a cache); a test's ShedSignals fake exposes queueBacklog() directly with no
            // refresh needed, and the plain ShedSignals interface (this parameter's type, since
            // audit F-011) does not declare a refresh step at all.
            if (signals is AndroidShedSignals) signals.refreshBacklog()
            controller.sample()
            relay.drain()
            ShedStatus.update(controller.currentLevel, signals.queueBacklog())
            ThermalStatus.sample(dependencies.osThermalStatus(applicationContext))

            val currentTier = ReprocessRunner.currentTierFromShedLevel()
            if (currentTier != previousTier) {
                DiagnosticsLog.logTierChange(previousTier, currentTier, controller.currentLevel)
                previousTier = currentTier
            }

            val freeBytes = signals.freeStorageBytes()
            val audioBytesNow = audioDirectoryBytes()
            StorageForecast.update(
                freeBytes = freeBytes,
                audioDirectoryBytes = audioBytesNow,
                bytesWrittenThisSession = (audioBytesNow - audioDirectoryBytesAtSessionStart).coerceAtLeast(0L),
                sessionElapsedMillis = SystemClock.wallMillis() - startedAtWallMillis,
                floorBytes = STORAGE_FLOOR_BYTES,
            )

            if (storageFloorBreached(freeBytes)) {
                stopForStorageExhaustion()
                return
            }
            delay(SHED_SAMPLE_INTERVAL_MILLIS)
        }
    }

    /**
     * FR-STO-4 / FR-RUN-3 level 5 / constitution IV: "only storage exhaustion stops capture,
     * loudly". Uses the same failure path a route mismatch already relies on
     * ([CaptureState.failed] plus a visible notification), then actually stops the audio source
     * -- stopping capture "loudly" means the surface reads `Failed` with the real reason, never a
     * write that silently throws later.
     */
    private fun stopForStorageExhaustion() {
        val reason = "storage exhausted: free space below the ${STORAGE_FLOOR_BYTES / (1024 * 1024)} MiB floor"
        CaptureState.failed(reason)
        endSessionRow(TerminationReason.STORAGE)
        updateNotification(CaptureNotificationContent.State.FAILED)
        source?.stop()
    }

    /**
     * R-113: reads the OS's live routed device the same way [AudioRecordSource] itself does
     * ([AudioIo.routedDevice]) and republishes [InputStatus] accordingly — a match republishes
     * [InputStatus.State.Opened] with both booleans true; a mismatch (or nothing routed) publishes
     * [InputStatus.State.Mismatch] instead, never silently keeping the old [InputStatus.opened]
     * (constitution IV). Called wherever the route can actually have changed: the first confirmed
     * read this session, every [CaptureEvent.RouteChanged], a route-mismatch [CaptureEvent.Failed],
     * and [CaptureEvent.Resumed] after the device reopens.
     */
    private fun refreshInputStatusFromRoute(audioSource: AudioRecordSource) {
        val expected = selectedInputDevice ?: return
        val routed = currentAudioIo?.routedDevice()
        if (routed != null && routed.id == expected.id) {
            InputStatus.opened(
                descriptor = expected,
                nativeRateHz = audioSource.deviceFormat.sampleRate,
                resamplerId = resamplerIdLabel(audioSource.resamplerIdentity),
                routeVerified = true,
                routedDeviceMatches = true,
                openedAtMillis = inputOpenedAtWallMillis,
            )
            DiagnosticsLog.logRouteVerified(expected.kind, audioSource.deviceFormat.sampleRate, true)
        } else {
            InputStatus.mismatch(expected, routed)
            DiagnosticsLog.logRouteMismatch(expected.kind, routed?.kind)
        }
    }

    /**
     * R-113: [org.ort.captureapi.ResamplerIdentity] is nullable (a native-rate device resamples
     * nothing); `InputStatus.Opened.resamplerId` is not, so "no resampler" is stated honestly
     * rather than left blank.
     */
    private fun resamplerIdLabel(identity: org.ort.captureapi.ResamplerIdentity?): String =
        identity?.toString() ?: "none (native rate matches output)"

    /** WPC2 (FR-CAP-13): `:capture-android`'s [AudioDeviceKind] mirrored onto `:core`'s
     * [AudioRouteKind] — the type [SessionEntity.audioRouteKind] is documented against. */
    private fun audioRouteKindFor(kind: AudioDeviceKind): AudioRouteKind = when (kind) {
        AudioDeviceKind.BUILT_IN_MIC -> AudioRouteKind.BUILT_IN_MIC
        AudioDeviceKind.USB_DEVICE -> AudioRouteKind.USB
        AudioDeviceKind.WIRED_HEADSET -> AudioRouteKind.WIRED_HEADSET
        AudioDeviceKind.BLUETOOTH -> AudioRouteKind.BLUETOOTH_SCO
        AudioDeviceKind.UNKNOWN -> AudioRouteKind.UNKNOWN
    }

    /**
     * FR-OBS-1: the encoded duration from `org.ort.capture.android.DroppedSpanCause.encode`'s
     * output, or `null` if [cause] is not one of ours. `:pipeline` cannot reference that object
     * (`internal`, a different module) -- [GapPersister.kt]'s own `causeFor` already duplicates the
     * same stable `"dropped samples:"` prefix for exactly this reason (see its kdoc); this repeats
     * only the narrower duration-extraction half that file does not itself expose.
     */
    private fun droppedSamplesMillisOrNull(cause: String): Long? {
        if (!cause.startsWith("dropped samples:", ignoreCase = true)) return null
        return DROPPED_SAMPLES_DURATION_PATTERN.find(cause)?.groupValues?.get(1)?.toLongOrNull()
    }

    /**
     * R-112: reads [AudioRecordSource.levelMeter]'s already-computed snapshot — a cheap, lock-free
     * field read, never blocking the frame path — and republishes it through [LevelStatus]. A
     * no-op until the meter has processed its first frame this session (`snapshot` still `null`).
     */
    private fun publishLevelStatus(audioSource: AudioRecordSource) {
        val snapshot = audioSource.levelMeter.snapshot ?: return
        LevelStatus.update(
            LevelStatus.State.Measured(
                peakDbfs = snapshot.peakDbfs,
                rmsDbfs = snapshot.rmsDbfs,
                noiseFloorDbfs = snapshot.noiseFloorDbfs,
                clipped = snapshot.clipped,
                clipCountLastSecond = snapshot.clipCountLastSecond,
                sampleRateHz = snapshot.sampleRateHz,
                updatedAtMillis = SystemClock.wallMillis(),
            ),
            peakHistoryDbfs = snapshot.peakHistoryDbfs,
        )
        // R-419: the real session-lifetime running total LevelMeter itself accumulates -- see
        // LevelStatus.recordClippedSamplesThisSession's own kdoc for why this is a separate call,
        // not folded into the State.Measured above.
        LevelStatus.recordClippedSamplesThisSession(snapshot.clippedSamplesTotal)
        // FR-OBS-1: only the clipped frames, never every ~10Hz tick -- capture.log would otherwise
        // rotate constantly on ordinary healthy sessions, drowning out the events worth keeping.
        if (snapshot.clipped) {
            DiagnosticsLog.logLevelClip(snapshot.clipCountLastSecond, snapshot.peakDbfs.toDouble())
        }
    }

    private fun onHeartbeat() {
        // audit F-005: samplePosition used to be a fabricated 0L literal. The segmenter is the
        // one thing here that knows how much audio has actually been fed to it (Segmenter.position(),
        // "absolute sample position of the next sample to be fed" -- no :segment change needed). 0L
        // is honest, not fabricated, in the one case there is genuinely no sample yet: before the
        // segmenter has been built for this session (the field is only assigned once capture starts).
        heartbeatStore.write(buildHeartbeatRecord(sessionId) { segmenter?.position() ?: 0L })
        updateNotification(CaptureNotificationContent.State.CAPTURING)
    }

    private fun stopCapture() {
        stopCaptureInternal(markClean = true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * **Ordering is deliberate, not incidental (R-173).** Every "this session just ended" fact —
     * [endSessionRow], [CaptureState.idle] — is published *before* [source]`?.stop()`. `[scope]`
     * runs on real `Dispatchers.IO` (no test-dispatcher seam in this class), so `runCaptureFlow`'s
     * own "did the flow end unexpectedly?" check (`if (CaptureState.isCapturing) ...`, its own
     * kdoc) executes on a *different thread* once [source] actually stops — and only runs after
     * observing [AudioRecordSource]'s `@Volatile stopRequested` flip true. The JMM's happens-before
     * chain (a volatile write happens-before a later read of the *same* volatile by another thread;
     * happens-before is transitive across a thread's own program order) guarantees that thread then
     * sees this thread's *prior* writes too — including [CaptureState.idle]'s — so it correctly
     * reads `Idle`, not stale `Capturing`, and never misclassifies a deliberate stop as unexpected
     * or races [endSessionRow] for which reason wins. Stopping [source] first would not have this
     * guarantee.
     */
    private fun stopCaptureInternal(markClean: Boolean) {
        // R-173: a deliberate stop (ACTION_STOP -> markClean = true) is TerminationReason.USER;
        // an unclean stop that still reached onDestroy (markClean = false) is KILLED -- the same
        // label DigestPolling already renders as "ended unclean -- the phone stopped the app". A
        // genuine hard kill where onDestroy never runs at all is closed later, on next launch, by
        // persistUncleanEndGapIfAny()'s closeIfStillOpen() call instead.
        val reason = if (markClean) TerminationReason.USER else TerminationReason.KILLED
        endSessionRow(reason)
        // FR-OBS-1: logged even when sessionEndRecorded already short-circuited endSessionRow's own
        // DB write above -- a second stopCaptureInternal call (R-173) is still a real service-stop
        // event worth a lifecycle.log line, distinct from whether the DB row itself was touched.
        DiagnosticsLog.logServiceStopped(sessionId, reason, markClean)
        // audit F-022: only a clean stop (ACTION_STOP) clears CaptureState.sessionId -- an
        // unclean stop (onDestroy without a prior ACTION_STOP, e.g. the OS killing the process)
        // leaves it in place so a Failed/Idle read still names the session that was running. See
        // CaptureState.idle()'s kdoc.
        CaptureState.idle(clearSession = markClean)
        // R-302: LevelStatus/InputStatus are live-session facts, same as CaptureState -- left at
        // their last live value here, a stale "too quiet" or "input mismatch" reading survives a
        // stop and is misread as current on the post-stop Capture-Status/Now-Idle boards. Reset on
        // every path that ends a session, clean or unclean, same as CaptureState.idle() above.
        LevelStatus.reset()
        InputStatus.reset()
        if (markClean && sessionId.isNotEmpty()) heartbeatStore.markCleanShutdown(sessionId)
        source?.stop()
        source = null
        segmenter = null
        // WPC2: the rig is a per-session resource same as the audio source -- torn down on every
        // path that ends a session, clean or unclean, so a stale Connected/Stale reading never
        // survives into a later Idle screen the way LevelStatus/InputStatus's own reset() prevents.
        rigSupervisor?.disconnect()
        rigSupervisor = null
    }

    /**
     * register R-173: the one place every session-ending path writes `SessionEntity.endedAt` —
     * before this existed, nothing did, so a real session read "still running" forever
     * (`Now-Idle`, `Sessions`). Guarded by [sessionEndRecorded] (see that field's own kdoc) so
     * whichever path reaches here first wins and no later path can overwrite it with a less
     * accurate reason. `runBlocking(Dispatchers.IO)`, not `scope.launch` — deliberately, so this
     * always completes before the caller proceeds: [onDestroy] calls `scope.cancel()` immediately
     * after [stopCaptureInternal] returns, which would race an async write launched on [scope]
     * and could lose it. Room forbids a query on the *calling* thread, not the dispatcher the
     * suspend body actually runs on, so this is safe to call from `onStartCommand`/`onDestroy`'s
     * own (main) thread — the same reasoning [RealSegmentSink.close]'s existing `runBlocking` uses,
     * made explicit about the dispatcher since this call, unlike that one, can run on the main
     * thread.
     */
    private fun endSessionRow(reason: TerminationReason?) {
        if (sessionEndRecorded) return
        val database = db ?: return
        if (sessionId.isEmpty()) return
        val endedAt = SystemClock.wallMillis()
        runBlocking(Dispatchers.IO) {
            database.sessionDao().setEnded(sessionId, endedAt, reason)
        }
        sessionEndRecorded = true
    }

    /**
     * The one synchronous notification build — required for `startForeground`'s immediate second
     * argument, called from [onStartCommand] on its own (main) thread, before [startCapture] has
     * set [db]/[source]/[selectedDeviceLabel]. Deliberately the plain "just started" shape (no
     * last-over/input row yet — nothing has happened this session) rather than the DB-backed
     * [buildNotificationContent], which must never run on the main thread (Room forbids it) — see
     * that function's own kdoc.
     */
    private fun notification(elapsedMillis: Long): android.app.Notification = renderNotification(
        CaptureNotificationBuilder.build(
            state = CaptureNotificationContent.State.CAPTURING,
            elapsedMillis = elapsedMillis,
            transmissionCount = 0,
            facts = CaptureNotificationExpandedFacts(
                frequenciesLabel = frequenciesLabel(0),
                tier = tierFromShedLevel(),
                degradedReason = degradedReason(),
                lastOverCallsign = null,
                lastOverAtWallMillis = null,
                inputDeviceName = null,
                inputVerified = false,
                storageLabel = storageLabel(),
            ),
        ),
    )

    /**
     * R-102: assembles [CaptureNotificationContent] from the same typed facts
     * [CaptureNotificationBuilder] requires — never free text. **Suspend, and only ever called from
     * [scope]** (Dispatchers.IO): [db]'s DAOs are suspend functions Room refuses to run on the main
     * thread, and [updateNotification] is the one caller, which always launches on [scope] rather
     * than running on whatever thread called it — see that function's own kdoc for why.
     */
    private suspend fun buildNotificationContent(
        state: CaptureNotificationContent.State,
        elapsedMillis: Long,
    ): CaptureNotificationContent {
        val sessionRows = db?.transmissionDao()?.listBySession(sessionId) ?: emptyList()
        val lastOver = sessionRows.lastOrNull()
        val frequenciesSeen = sessionRows.mapNotNull { it.frequencyHz }.distinct().size

        return CaptureNotificationBuilder.build(
            state = state,
            elapsedMillis = elapsedMillis,
            transmissionCount = transmissionCount,
            facts = CaptureNotificationExpandedFacts(
                frequenciesLabel = frequenciesLabel(frequenciesSeen),
                tier = tierFromShedLevel(),
                degradedReason = degradedReason(),
                lastOverCallsign = lastOver?.stationId,
                lastOverAtWallMillis = lastOver?.startedAtUtc,
                inputDeviceName = selectedDeviceLabel,
                inputVerified = source?.routedDevice() != null,
                storageLabel = storageLabel(),
            ),
        )
    }

    /**
     * The Android-notification half of [content] — actions, style, channel. Shared by
     * [notification]/[updateNotification].
     */
    private fun renderNotification(content: CaptureNotificationContent): android.app.Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, RealCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        // R-102: an explicit-component PendingIntent to org.ort.app.ui.ReaderActivity, named by
        // string rather than ::class.java -- :pipeline must not depend on :app (module boundary,
        // constitution VII), so this is the one legal way to target it. exported=false is fine
        // here: a PendingIntent the app itself created runs with the creating app's own identity
        // when the system launches it on tap, the same mechanism the Stop action above already
        // relies on for this same service.
        val openIntent = Intent()
            .setClassName(packageName, READER_ACTIVITY_CLASS_NAME)
            .putExtra(EXTRA_SESSION_ID, sessionId)
        val openPendingIntent = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(content.title)
            .setContentText(content.secondLine.text)
            .setStyle(
                NotificationCompat.InboxStyle()
                    .addLine("Last over: ${lastOverLine(content)}")
                    .addLine("Input: ${inputLine(content)}")
                    .addLine("Storage: ${content.storageLabel}"),
            )
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            // guide §6.19 / this package's brief: no sound -- the channel is already IMPORTANCE_LOW
            // (ensureChannel()), and NotificationCompat.Builder plays none unless setSound() is
            // called, which it never is here.
            .addAction(0, "Open", openPendingIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun lastOverLine(content: CaptureNotificationContent): String {
        if (!content.hasLastOver) return "none yet"
        val callsign = content.lastOverCallsign ?: "unidentified"
        val time = content.lastOverAtWallMillis?.let { formatClockTime(it) } ?: ""
        return if (time.isEmpty()) callsign else "$callsign · $time"
    }

    private fun inputLine(content: CaptureNotificationContent): String {
        val verified = if (content.inputVerified) "verified" else "not verified"
        return "${content.inputDeviceName ?: "none"} · $verified"
    }

    // A new SimpleDateFormat per call, deliberately: updateNotification() can have more than one
    // build running concurrently on Dispatchers.IO's thread pool (nothing cancels an in-flight one
    // before launching the next), and SimpleDateFormat is not thread-safe -- a shared instance here
    // would be a real, if rare, data-corruption bug, not a style choice.
    private fun formatClockTime(wallMillis: Long): String =
        SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date(wallMillis))

    /** "frequencies · tier" — see R-102's line in this package's report for what each half can read today. */
    private fun frequenciesLabel(frequenciesSeenThisSession: Int): String {
        val rig = RigStatus.state
        if (rig is RigStatus.State.Connected) {
            val fromRig = rig.bands.mapNotNull { it.frequencyHz }.joinToString(" and ") { formatFrequencyMHz(it) }
            if (fromRig.isNotEmpty()) return fromRig
        }
        // "the configured frequency if any" (this package's brief) has no source in
        // RealCaptureService today -- FR-CFG/FR-RIG are both unbuilt -- so this falls through
        // straight to a count of frequencies actually seen this session, honestly 0 while nothing
        // populates TransmissionEntity.frequencyHz (see this package's report).
        return "$frequenciesSeenThisSession frequencies"
    }

    private fun formatFrequencyMHz(hz: Long): String = "%.3f".format(hz / 1_000_000.0)

    /** "tier from the shed level for now" (this package's brief) — a placeholder pending FR-TIER's real model. */
    private fun tierFromShedLevel(): Int = (MAX_TIER - ShedStatus.currentLevel).coerceIn(0, MAX_TIER)

    /**
     * The second line's degraded replacement (never a second notification): [CaptureState] first
     * (a real failure/interruption is always the most important fact), then whatever combination
     * of [ThermalStatus]/[RigStatus]/[StorageForecast] applies, joined the way
     * `Capture-Notification.dc.html`'s own degraded example does — `"Running warm — tier 2 ·
     * radio disconnected, frequency stale"`.
     */
    private fun degradedReason(): String? {
        val fromCaptureState = when (val s = CaptureState.state) {
            is CaptureState.State.Failed -> s.reason
            is CaptureState.State.Interrupted -> "interrupted: ${s.cause}"
            CaptureState.State.Capturing, CaptureState.State.Idle -> null
        }
        if (fromCaptureState != null) return fromCaptureState

        val reasons = mutableListOf<String>()
        when (ThermalStatus.state) {
            is ThermalStatus.State.Warm -> reasons += "Running warm — tier ${tierFromShedLevel()}"
            is ThermalStatus.State.Hot -> reasons += "Overheating — tier ${tierFromShedLevel()}"
            is ThermalStatus.State.Nominal -> Unit
        }
        if (RigStatus.state is RigStatus.State.Stale) reasons += "radio disconnected, frequency stale"
        when (StorageForecast.state) {
            is StorageForecast.State.OneNightLeft -> reasons += "storage: 1 night left"
            is StorageForecast.State.AtFloor -> reasons += "storage at the floor"
            is StorageForecast.State.Fine,
            is StorageForecast.State.ThreeNightsLeft,
            is StorageForecast.State.NotYetMeasured,
            -> Unit
        }
        return reasons.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /**
     * "38.2 of 60 GB · 16 nights left, or the forecast's honest 'no budget set'" (this package's
     * brief). FR-STO-3's own budget/retention settings screen (WP10, register R-090) does not
     * exist yet, so "no budget set" is always true today, not a fallback for a missing
     * measurement — nights-left is still appended whenever [StorageForecast] has actually measured
     * one (constitution I: both facts stated, never one silently standing in for the other).
     */
    private fun storageLabel(): String {
        val forecast = StorageForecast.state
        val gbText = "%.1f GB".format(forecast.audioDirectoryBytes / BYTES_PER_GIB)
        val nightsLeft = when (forecast) {
            is StorageForecast.State.Fine -> forecast.nightsLeft
            is StorageForecast.State.ThreeNightsLeft -> forecast.nightsLeft
            is StorageForecast.State.OneNightLeft -> forecast.nightsLeft
            is StorageForecast.State.AtFloor, is StorageForecast.State.NotYetMeasured -> null
        }
        return if (nightsLeft != null) {
            "$gbText · no budget set · ${nightsLeft.toInt().coerceAtLeast(0)} nights left"
        } else {
            "$gbText · no budget set"
        }
    }

    /**
     * Always dispatches onto [scope] (Dispatchers.IO) to build the notification, regardless of
     * which thread calls this — some callers (the "no audio input device" early return in
     * [startCapture]) run on [onStartCommand]'s own main thread, and Room refuses DAO access there
     * ([buildNotificationContent] needs [db]). Fire-and-forget: the notification lands a moment
     * later, never blocking the caller, and always the same [NOTIFICATION_ID] updated in place.
     */
    private fun updateNotification(state: CaptureNotificationContent.State) {
        val elapsed = SystemClock.wallMillis() - startedAtWallMillis
        scope.launch {
            val content = buildNotificationContent(state, elapsed)
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, renderNotification(content))
        }
    }

    /** R-105: the audio archive's real, on-disk size — `filesDir/audio`, recursively (`audioPath()`'s own root). */
    private fun audioDirectoryBytes(): Long {
        val dir = File(filesDir, "audio")
        if (!dir.isDirectory) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Capture", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun shortsToFloats(shorts: ShortArray): FloatArray = FloatArray(shorts.size) { shorts[it] / SHORT_MAX }

    public companion object {
        public const val CHANNEL_ID: String = "ort.real-capture"
        public const val NOTIFICATION_ID: Int = 1002
        public const val ACTION_STOP: String = "org.ort.pipeline.capture.STOP"
        public const val EXTRA_SESSION_ID: String = "session_id"
        private const val HEARTBEAT_INTERVAL_MILLIS: Long = 30_000
        private const val WAKE_LOCK_TIMEOUT_MILLIS: Long = 12 * 60 * 60 * 1000L
        private const val SHORT_MAX: Float = 32_768f

        /** technical design §7.3's 10 s shed-controller tick (audit F-007). */
        private const val SHED_SAMPLE_INTERVAL_MILLIS: Long = 10_000

        /**
         * FR-STO-4's storage floor (audit F-007) — deliberately generous, not tuned: this is the
         * honest "stop before a write throws" line, not the full FR-STO-3 budget/warning system
         * (still open, F-020/F-021 note it separately). 100 MiB is comfortably above a single
         * FLAC-encoded transmission (minutes of 16 kHz mono speech, low tens of KB) plus Room's
         * WAL/journal overhead, so normal operation never brushes it — it only fires when the
         * device is genuinely, materially out of space, which is exactly when capture must stop
         * loudly rather than let a write fail silently underneath the segmenter.
         */
        internal const val STORAGE_FLOOR_BYTES: Long = 100L * 1024 * 1024

        /** FR-OBS-1: matches `DroppedSpanCause.encode`'s `"... over ${durationMillis}ms ..."`. */
        private val DROPPED_SAMPLES_DURATION_PATTERN = Regex("""over (\d+)ms""")

        /**
         * F5 (register R-106): the literal `GapPersister.causeFor` recognises as
         * `org.ort.data.entity.CaptureGapCause.OS_STOPPED`. Internal, not `private`, so
         * `RealCaptureServiceUncleanEndGapTest` can assert against it directly rather than
         * duplicating the string.
         */
        internal const val OS_STOPPED_GAP_CAUSE: String = "os stopped: unclean end detected on launch"

        /**
         * R-113: the literal prefix [org.ort.capture.android.AudioRecordSource] emits both places
         * it can fail a route check (`"route mismatch: " + verdict.reason`) — matched, not
         * duplicated, so [refreshInputStatusFromRoute] only fires for an actual route mismatch and
         * not some other [CaptureEvent.Failed] reason (e.g. "device open failed").
         */
        internal const val ROUTE_MISMATCH_ERROR_PREFIX: String = "route mismatch:"

        /**
         * R-102: the explicit component this app's own notification's Open action targets — see
         * [renderNotification]'s kdoc for why by name, not `::class.java`.
         */
        internal const val READER_ACTIVITY_CLASS_NAME: String = "org.ort.app.ui.ReaderActivity"

        /** "tier from the shed level for now" (R-102) — the shed order's own top level (FR-RUN-3). */
        private const val MAX_TIER: Int = 3

        /** F23 (WPC3): `BackoffLadder`'s own step count (1s, 2s, 5s, 10s, 30s, then holding) —
         * copied rather than read, since that object does not expose its length publicly; see
         * [org.ort.pipeline.rig.RigSupervisor]'s matching constant for the same policy applied to
         * the two rig-side ladders. */
        internal const val RECONNECT_LADDER_STEPS: Int = 5

        private const val BYTES_PER_GIB: Double = 1024.0 * 1024.0 * 1024.0
    }
}

/**
 * FR-STO-4: `true` once free storage has dropped to or below [STORAGE_FLOOR_BYTES] (or a caller-
 * supplied [floorBytes] in tests). A plain function, not a method on [AndroidShedSignals], so it
 * is testable without a live signal source and so its threshold can be asserted on directly.
 */
internal fun storageFloorBreached(freeBytes: Long, floorBytes: Long = RealCaptureService.STORAGE_FLOOR_BYTES): Boolean =
    freeBytes < floorBytes

/**
 * F7 (register R-104): wraps whatever real Pass B [RealCaptureService.startProcessingLoop]
 * constructs so [ThermalStatus]'s measured real-time factor is exactly the wall time of a real
 * pass run over its segment's real audio duration — never invented, never estimated from a batch
 * average. Deliberately placed here, as a decorator [RealCaptureService] wires in, rather than
 * inside [CaptureProcessingLoop] or [PassDrainRunner] (the two files this package's brief itself
 * points at as "where a pass's start/end is known"): neither of those two files is in this
 * package's file-ownership row (WP11a owns `RealCaptureService.kt`'s wiring, not `:pipeline`'s
 * general pass-running machinery), so the measurement is taken at the one point already inside
 * this file's ownership that sees every real pass run — the [Pass] this class constructs and
 * hands to [CaptureProcessingLoop]. See this package's report for this exact reasoning.
 *
 * [durationMs] is looked up fresh per run via [transmissionDao], the one fact [WorkQueueItemEntity]
 * itself does not carry — a transmission with no measurable duration (not found, or `<= 0`, which
 * should not happen but is not asserted against here) simply does not contribute a sample, rather
 * than recording a nonsense infinite or zero real-time factor.
 */
internal class ThermalTrackingPass(
    private val delegate: Pass,
    private val db: OrtDatabase,
    private val clock: org.ort.core.Clock = SystemClock,
) : Pass {
    override suspend fun run(item: org.ort.data.entity.WorkQueueItemEntity): org.ort.data.PassRunOutcome {
        val startWallMillis = clock.wallMillis()
        val outcome = delegate.run(item)
        val elapsedMillis = (clock.wallMillis() - startWallMillis).coerceAtLeast(0L)

        val durationMs = db.transmissionDao().getById(item.transmissionId)?.durationMs
        if (durationMs != null && durationMs > 0) {
            ThermalStatus.recordPassTiming(elapsedMillis.toDouble() / durationMs.toDouble())
        }
        // FR-OBS-1 "per-pass latency": every real pass run this loop drives, tagged with the tier
        // it ran at (the same mapping runShedMonitor's own tier-change logging uses).
        DiagnosticsLog.logPassLatency(item.pass, ReprocessRunner.currentTierFromShedLevel(), elapsedMillis)
        return outcome
    }
}

/**
 * audit F-005: the exact seam that used to write a fabricated `samplePosition = 0L` into every
 * heartbeat. Extracted to a small, non-Android function so it is testable without starting the
 * whole [RealCaptureService] under Robolectric (no `ServiceController` harness exists for it yet
 * -- see finding F-011). [samplePosition] is read lazily, at write time, from whatever currently
 * knows the real value (the session's [Segmenter][org.ort.segment.Segmenter]).
 */
internal fun buildHeartbeatRecord(sessionId: String, samplePosition: () -> Long): HeartbeatRecord =
    HeartbeatRecord(sessionId, SystemClock.monotonicNanos(), SystemClock.wallMillis(), samplePosition())

/**
 * audit F-028: the seam that joins `:capture-android`'s [GapTracker] output to
 * [GapPersister][org.ort.pipeline.GapPersister], which nothing in the running service used to
 * construct -- `captureGapDao` was always empty in production regardless of how faithfully
 * [GapTracker] itself modelled a gap (FR-RUN-12, FR-UI-12, AC-48). Extracted as a plain,
 * non-Android class -- same approach F-005 used for [buildHeartbeatRecord] -- so it is testable
 * without starting the whole [RealCaptureService] under Robolectric (no `ServiceController`
 * harness exists yet for it; see F-011, still open).
 *
 * [tracker] already de-duplicates the two shapes a gap can arrive in (an open/close
 * `Interrupted`/`Resumed` pair, or a dropped-span `Interrupted` that closes itself immediately --
 * F-010); this only has to notice when [GapTracker.gaps] has grown and persist what's new, once,
 * in the order it appeared.
 */
internal class CaptureGapRelay(private val tracker: GapTracker, private val persist: suspend (GapRecord) -> Unit) {
    private var persistedCount = 0

    suspend fun onEvent(event: CaptureEvent) {
        tracker.onEvent(event)
        while (persistedCount < tracker.gaps.size) {
            persist(tracker.gaps[persistedCount])
            persistedCount++
        }
    }
}

/**
 * audit F-007: the same shape [CaptureGapRelay] uses, for [ShedController]'s `events` list instead
 * of [GapTracker]'s `gaps` — nothing in the running service used to construct a real
 * [ShedController] at all, so this is new ground, not a persistence gap in an otherwise-wired
 * class. [controller]'s `events` list only grows, one entry per transition; [drain] notices when
 * it has grown since the last call and persists exactly what's new, once, tracking the level
 * immediately before each transition itself (the controller only exposes the level *after*).
 */
internal class ShedEventRelay(
    private val controller: ShedController,
    private val sessionId: String,
    private val persister: ShedEventPersister,
    private val samplePosition: () -> Long,
) {
    private var persistedCount = 0
    private var levelBeforeNext = controller.currentLevel

    suspend fun drain() {
        while (persistedCount < controller.events.size) {
            val event = controller.events[persistedCount]
            persister.persist(sessionId, levelBeforeNext, event, samplePosition())
            levelBeforeNext = event.level
            persistedCount++
        }
    }
}

/**
 * A simple RMS-energy voice-activity model — explicitly not Silero (see [RealCaptureService]'s
 * doc comment for why). Good enough to segment real speech from real silence for a smoke test.
 */
internal class EnergyVadModel(private val threshold: Float = 0.02f) : org.ort.segment.VadModel {
    override fun speechProbability(frame: FloatArray): Float {
        var sumSquares = 0.0
        for (s in frame) sumSquares += s.toDouble() * s.toDouble()
        val rms = kotlin.math.sqrt(sumSquares / frame.size).toFloat()
        return (rms / threshold).coerceIn(0f, 1f)
    }
}

/**
 * Writes each confirmed segment's PCM to a staged file incrementally (never buffered in heap —
 * same discipline `:segment`'s own doc comments require), then FLAC-encodes it via [FlacStore]
 * on close and persists a real [TransmissionEntity] + queue entry. [onSegmentPersisted] is called
 * after every successful persist so the caller can update its own counters/heartbeat.
 */
internal class RealSegmentSink(
    private val filesDir: File,
    private val sessionId: String,
    private val db: OrtDatabase,
    private val queue: WorkQueue,
    private val sampleClock: SampleClock,
    private val segmentConfig: SegmentConfig,
    /** WPC2 (FR-RIG-6/8/9): the frequency reading (with provenance) for the transmission spanning
     * [startNanos, endNanos] on the session's monotonic timeline. Defaults to always-unknown so
     * every pre-existing caller/test of this class keeps compiling unchanged. */
    private val frequencyProvider: (startNanos: Long, endNanos: Long) -> FrequencyReading =
        { _, _ -> FrequencyReading.UNKNOWN },
    private val onSegmentPersisted: () -> Unit,
) : SegmentSink {

    private val flacStore = FlacStore(DeflatePredictiveCodec())

    private companion object {
        /** FR-SEG-6 / AC-72's `rejected:too_short` tag, as the free-text `rejectionReason` value. */
        const val REJECTION_REASON_TOO_SHORT = "too_short"
    }

    override fun open(id: SegmentId, startSample: Long): SegmentWriter {
        val staged = File(filesDir, "staging/${sessionId}_${id.index}.pcm")
        staged.parentFile?.mkdirs()
        val raf = RandomAccessFile(staged, "rw")
        raf.setLength(0)

        return object : SegmentWriter {
            override fun append(pcm: FloatArray) {
                val bytes = ByteArray(pcm.size * 2)
                for (i in pcm.indices) {
                    val s = (pcm[i] * Short.MAX_VALUE).toInt().coerceIn(
                        Short.MIN_VALUE.toInt(),
                        Short.MAX_VALUE.toInt(),
                    )
                    bytes[i * 2] = (s and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                }
                raf.write(bytes)
            }

            override fun close(record: SegmentRecord): SegmentRecord {
                raf.close()
                val transmissionId = "$sessionId-${record.id.index}"
                // FR-RUN-15/16/18: derived from the session anchor plus this segment's sample
                // position on the sample-accurate timeline -- never a fresh wall-clock read here,
                // which would silently drift from when the audio actually happened.
                val timestamps = sampleClock.timestampsAt(record.startSample)
                // Constitution III "nothing is deleted quietly" / FR-SEG-6 -> AC-72: a too-short
                // segment is recorded and its audio retained exactly like SPEECH, just marked
                // REJECTED and never enqueued for Pass B (audit F-006 -- this used to delete the
                // staged PCM here and return, losing the segment with no trace).
                val (processingState, rejectionReason) = when (record.outcome) {
                    SegmentOutcome.SPEECH -> TransmissionState.CAPTURED to null
                    SegmentOutcome.REJECTED_TOO_SHORT -> TransmissionState.REJECTED to REJECTION_REASON_TOO_SHORT
                }
                // WPC2 (FR-RIG-6/8/9): read once, at close, over this segment's own start/end on
                // the session's monotonic timeline -- never a fresh "now" read (constitution III's
                // reasoning for sample-accurate timestamps applies just as much to the rig reading
                // that describes them).
                val frequencyReading = frequencyProvider(
                    sampleClock.monotonicNanosAt(record.startSample),
                    sampleClock.monotonicNanosAt(record.endSample),
                )
                val entity = TransmissionEntity(
                    id = transmissionId,
                    sessionId = sessionId,
                    threadId = null,
                    startedAtUtc = timestamps.startedAtUtcMillis,
                    endedAtUtc = sampleClock.wallMillisAt(record.endSample),
                    durationMs = (record.endSample - record.startSample) * 1000 / FrameSpec.SAMPLE_RATE,
                    audioFormat = "flac/16k/mono",
                    preRollMs = segmentConfig.preRollMs,
                    postRollMs = segmentConfig.postRollMs,
                    frequencyHz = frequencyReading.frequencyHz,
                    frequencyProvenance = frequencyReading.provenance,
                    mode = null,
                    signalStrength = null,
                    channelName = null,
                    voiceprintId = null,
                    attributionState = AttributionState.UNKNOWN,
                    stationId = null,
                    attributionConfidence = null,
                    attributionSourceTransmissionId = null,
                    processingState = processingState,
                    rejectionReason = rejectionReason,
                    samplePosition = record.startSample,
                    monotonicStartNanos = timestamps.monotonicStartNanos,
                    utcOffsetMinutes = timestamps.utcOffsetMinutes,
                    calibrationId = null,
                    executionProvider = null,
                    // WPC3 (FR-RIG-6): the rig reported a different reading before this
                    // transmission ended than it had at the start -- either a genuine
                    // mid-transmission change, or (D23) both bands were open at start and this
                    // reading is an ambiguous pick between them (see RealCaptureService's
                    // frequencyProvider wiring). Either way, never silently overwritten.
                    rigStateChangedMidTransmission = frequencyReading.changedDuringTransmission,
                )
                val encoded = File(filesDir, entity.audioPath())

                val result = flacStore.encodeAndVerify(staged, encoded)
                if (result !is org.ort.capture.android.codec.FlacEncodeResult.Success) return record

                runBlocking {
                    db.transmissionDao().insert(entity)
                    if (record.outcome == SegmentOutcome.SPEECH) queue.enqueue(transmissionId, PassId.B_OFFLINE)
                }
                onSegmentPersisted()
                return record
            }
        }
    }
}
