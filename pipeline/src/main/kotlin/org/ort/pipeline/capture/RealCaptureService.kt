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
import org.ort.capture.android.AudioIo
import org.ort.capture.android.AudioRecordSource
import org.ort.capture.android.GapRecord
import org.ort.capture.android.GapTracker
import org.ort.capture.android.codec.DeflatePredictiveCodec
import org.ort.capture.android.codec.FlacStore
import org.ort.capture.android.heartbeat.FileHeartbeatStore
import org.ort.capture.android.heartbeat.HeartbeatRecord
import org.ort.capture.android.service.CaptureNotificationBuilder
import org.ort.captureapi.CaptureEvent
import org.ort.core.AttributionState
import org.ort.core.PassId
import org.ort.core.SampleClock
import org.ort.core.SystemClock
import org.ort.core.TransmissionState
import org.ort.core.Ulid
import org.ort.data.OrtDatabase
import org.ort.data.WorkQueue
import org.ort.data.dao.WorkQueueDao
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TransmissionEntity
import org.ort.pipeline.CaptureProcessingLoop
import org.ort.pipeline.GapPersister
import org.ort.pipeline.PassDrainRunner
import org.ort.pipeline.passb.AsrEngineAvailability
import org.ort.pipeline.passb.PassBFactory
import org.ort.pipeline.passb.RealAsrEngineProvider
import org.ort.pipeline.passb.UnavailableAsrEngine
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

    // audit F-005: the only way onHeartbeat() can report the real sample position instead of a
    // fabricated 0L -- the segmenter is the one thing in this class that knows how much audio has
    // actually been fed to it (Segmenter.position(), unchanged in :segment).
    private var segmenter: Segmenter? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ort:real-capture")
        ensureChannel()
        heartbeatStore = FileHeartbeatStore(File(filesDir, "heartbeat.txt"))
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
        val db = dependencies.database(applicationContext)
        val queue = WorkQueue(db, SystemClock)
        // A real enumerated device — never a fabricated descriptor, which RouteVerifier would
        // (correctly) reject on the first read, halting capture. See defaultInputDevice()'s kdoc.
        val (io, device) = dependencies.audioIo(applicationContext) ?: run {
            CaptureState.failed("no audio input device is available")
            updateNotification("Failed: no audio input device")
            return
        }
        io.select(device)
        val audioSource = AudioRecordSource(io, device)
        source = audioSource
        CaptureState.capturing(sessionId)

        // audit F-028: before this, nothing in the running service constructed a GapTracker or a
        // GapPersister -- AudioRecordSource emitted Interrupted/Resumed (and, after F-010, a
        // dropped-span cause) but no one joined them to CaptureGapDao, so captureGapDao was always
        // empty in production and P17's not-listening distinction (FR-UI-12) could never show a
        // real gap (FR-RUN-12, AC-48; constitution IV).
        val gapPersister = GapPersister(db.captureGapDao(), SystemClock)
        val gapRelay = CaptureGapRelay(GapTracker(SystemClock)) { gap -> gapPersister.persist(sessionId, gap) }

        // audit F-007: before this, nothing in the running service ever constructed a
        // ShedController against a real ShedSignals -- it was only ever built (against
        // FakeShedSignals) for :app's status display (F-002), so FR-RUN-3's shed order could
        // never actually trigger and a full disk was discovered only when a write threw. This
        // ticks a real ShedController every SHED_SAMPLE_INTERVAL_MILLIS, persists each level
        // transition (FR-RUN-5) and republishes level+backlog through ShedStatus for :app to
        // read; a free-storage floor breach stops capture loudly rather than letting a write fail
        // silently (FR-STO-4, FR-RUN-6; constitution IV).
        val shedSignals = dependencies.shedSignals(applicationContext, db.workQueueDao(), filesDir)
        val shedController = ShedController(shedSignals, SystemClock)
        val shedRelay = ShedEventRelay(
            controller = shedController,
            sessionId = sessionId,
            persister = ShedEventPersister(db.shedEventDao(), SystemClock),
            samplePosition = { segmenter?.position() ?: 0L },
        )
        scope.launch { runShedMonitor(shedSignals, shedController, shedRelay) }

        // The processing loop (build-plan P12, defect 3): drains whatever RealSegmentSink
        // enqueues, independent of the capture flow above -- a stalled or unavailable ASR engine
        // must never block capture (constitution IV: "capture MUST proceed with every processing
        // pass stalled"). Runs as its own coroutine in the same service-scoped `scope`, so it is
        // cancelled by the same `scope.cancel()` onDestroy() already calls.
        scope.launch { startProcessingLoop(db, queue) }

        scope.launch {
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
                        val now = SystemClock.wallMillis()
                        if (now - lastHeartbeatAt >= HEARTBEAT_INTERVAL_MILLIS) {
                            lastHeartbeatAt = now
                            onHeartbeat()
                        }
                    }
                    is CaptureEvent.Failed -> {
                        CaptureState.failed(event.error)
                        updateNotification("Failed: ${event.error}")
                    }
                    CaptureEvent.RouteChanged -> Unit
                    is CaptureEvent.Interrupted -> {
                        CaptureState.interrupted(event.cause)
                        updateNotification("Interrupted")
                    }
                    CaptureEvent.Resumed -> {
                        CaptureState.capturing(sessionId)
                        updateNotification("Capturing")
                    }
                    CaptureEvent.EndOfStream -> Unit
                }
            }
            // The flow completing means the source stopped for good — halted on a route mismatch
            // or an unrecoverable failure. Never leave the surface claiming "Capturing".
            if (CaptureState.isCapturing) CaptureState.failed("capture stopped unexpectedly")
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
        val sink = RealSegmentSink(filesDir, sessionId, db, queue, sampleClock, segmentConfig) {
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
                updateNotification("ASR unavailable")
                // audit F-013: no engine ran at all here, so the fingerprint's provider must say
                // so honestly ("none") rather than repeating the real engine's "cpu".
                Triple(UnavailableAsrEngine(availability.reason), org.ort.core.AssetRef("asr-unavailable", "0"), "none")
            }
        }
        val pass = PassBFactory.create(filesDir, db, engine, modelRef, provider)
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
     * reflect the same tick's shed level, not a stale one from before this tick ran.
     */
    private suspend fun runShedMonitor(signals: ShedSignals, controller: ShedController, relay: ShedEventRelay) {
        while (source != null) {
            // refreshBacklog() is AndroidShedSignals's own live-read step (queueBacklog() then
            // returns a cache); a test's ShedSignals fake exposes queueBacklog() directly with no
            // refresh needed, and the plain ShedSignals interface (this parameter's type, since
            // audit F-011) does not declare a refresh step at all.
            if (signals is AndroidShedSignals) signals.refreshBacklog()
            controller.sample()
            relay.drain()
            ShedStatus.update(controller.currentLevel, signals.queueBacklog())

            if (storageFloorBreached(signals.freeStorageBytes())) {
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
        updateNotification("Failed: storage exhausted")
        source?.stop()
    }

    private fun onHeartbeat() {
        // audit F-005: samplePosition used to be a fabricated 0L literal. The segmenter is the
        // one thing here that knows how much audio has actually been fed to it (Segmenter.position(),
        // "absolute sample position of the next sample to be fed" -- no :segment change needed). 0L
        // is honest, not fabricated, in the one case there is genuinely no sample yet: before the
        // segmenter has been built for this session (the field is only assigned once capture starts).
        heartbeatStore.write(buildHeartbeatRecord(sessionId) { segmenter?.position() ?: 0L })
        updateNotification("Capturing")
    }

    private fun stopCapture() {
        stopCaptureInternal(markClean = true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopCaptureInternal(markClean: Boolean) {
        source?.stop()
        source = null
        segmenter = null
        CaptureState.idle()
        if (markClean && sessionId.isNotEmpty()) heartbeatStore.markCleanShutdown(sessionId)
    }

    private fun notification(elapsedMillis: Long) = buildNotification(elapsedMillis)

    private fun buildNotification(elapsedMillis: Long): android.app.Notification {
        val content = CaptureNotificationBuilder.build("Capturing", elapsedMillis, transmissionCount)
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, RealCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification(state: String) {
        val elapsed = SystemClock.wallMillis() - startedAtWallMillis
        val content = CaptureNotificationBuilder.build(state, elapsed, transmissionCount)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(content.title)
                .setContentText(content.text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build(),
        )
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
                    frequencyHz = null,
                    frequencyProvenance = "unknown",
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
