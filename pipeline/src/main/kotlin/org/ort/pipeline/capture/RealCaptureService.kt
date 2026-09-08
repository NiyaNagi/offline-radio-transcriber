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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.ort.capture.android.AndroidAudioIo
import org.ort.capture.android.AudioRecordSource
import org.ort.capture.android.codec.DeflatePredictiveCodec
import org.ort.capture.android.codec.FlacStore
import org.ort.capture.android.heartbeat.FileHeartbeatStore
import org.ort.capture.android.heartbeat.HeartbeatRecord
import org.ort.capture.android.service.CaptureNotificationBuilder
import org.ort.captureapi.CaptureEvent
import org.ort.core.AttributionState
import org.ort.core.PassId
import org.ort.core.SystemClock
import org.ort.core.TransmissionState
import org.ort.core.Ulid
import org.ort.data.OrtDatabase
import org.ort.data.WorkQueue
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TransmissionEntity
import org.ort.pipeline.CaptureProcessingLoop
import org.ort.pipeline.PassDrainRunner
import org.ort.pipeline.passb.AsrEngineAvailability
import org.ort.pipeline.passb.PassBFactory
import org.ort.pipeline.passb.RealAsrEngineProvider
import org.ort.pipeline.passb.UnavailableAsrEngine
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

    private var wakeLock: PowerManager.WakeLock? = null
    private var source: AudioRecordSource? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var sessionId: String = ""
    private var transmissionCount = 0
    private var startedAtWallMillis = 0L
    private lateinit var heartbeatStore: FileHeartbeatStore

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
        startedAtWallMillis = SystemClock.wallMillis()
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
        val db = OrtDatabase.create(applicationContext)
        val queue = WorkQueue(db, SystemClock)
        val io = AndroidAudioIo(applicationContext)
        // A real enumerated device — never a fabricated descriptor, which RouteVerifier would
        // (correctly) reject on the first read, halting capture. See defaultInputDevice()'s kdoc.
        val device = io.defaultInputDevice()
        if (device == null) {
            CaptureState.failed("no audio input device is available")
            updateNotification("Failed: no audio input device")
            return
        }
        io.select(device)
        val audioSource = AudioRecordSource(io, device)
        source = audioSource
        CaptureState.capturing(sessionId)

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

            val sink = RealSegmentSink(filesDir, sessionId, db, queue) {
                transmissionCount++
                onHeartbeat()
            }
            // Real Silero VAD when a model is installed, the RMS-energy stand-in otherwise --
            // never silently one pretending to be the other (build-plan P12; see VadAvailability).
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
            val vad = SileroVad(vadModel)
            val segmenter = Segmenter(SegmentConfig(), vad, sink)

            var lastHeartbeatAt = 0L
            audioSource.start().collect { event ->
                when (event) {
                    is CaptureEvent.Frames -> {
                        segmenter.onAudio(shortsToFloats(event.pcm))
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
        val availability = RealAsrEngineProvider(filesDir).provide()
        val (engine, modelRef) = when (availability) {
            is AsrEngineAvailability.Available -> {
                AsrAvailability.available(availability.modelRef.canonical)
                availability.engine to availability.modelRef
            }
            is AsrEngineAvailability.Unavailable -> {
                AsrAvailability.unavailable(availability.reason)
                updateNotification("ASR unavailable")
                UnavailableAsrEngine(availability.reason) to org.ort.core.AssetRef("asr-unavailable", "0")
            }
        }
        val pass = PassBFactory.create(filesDir, db, engine, modelRef)
        CaptureProcessingLoop(PassDrainRunner(queue, runId = sessionId), pass).runForever()
    }

    private fun onHeartbeat() {
        heartbeatStore.write(
            HeartbeatRecord(sessionId, SystemClock.monotonicNanos(), SystemClock.wallMillis(), 0L),
        )
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
    private val onSegmentPersisted: () -> Unit,
) : SegmentSink {

    private val flacStore = FlacStore(DeflatePredictiveCodec())

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
                if (record.outcome != SegmentOutcome.SPEECH) {
                    staged.delete() // rejected segment: this smoke test keeps SPEECH only, real product retains it
                    return record
                }
                val transmissionId = "$sessionId-${record.id.index}"
                val entity = TransmissionEntity(
                    id = transmissionId,
                    sessionId = sessionId,
                    threadId = null,
                    startedAtUtc = 0L,
                    endedAtUtc = null,
                    durationMs = (record.endSample - record.startSample) * 1000 / FrameSpec.SAMPLE_RATE,
                    audioFormat = "flac/16k/mono",
                    preRollMs = 1200,
                    postRollMs = 400,
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
                    processingState = TransmissionState.CAPTURED,
                    rejectionReason = null,
                    samplePosition = record.startSample,
                    monotonicStartNanos = 0L,
                    utcOffsetMinutes = 0,
                    calibrationId = null,
                    executionProvider = null,
                )
                val encoded = File(filesDir, entity.audioPath())

                val result = flacStore.encodeAndVerify(staged, encoded)
                if (result !is org.ort.capture.android.codec.FlacEncodeResult.Success) return record

                runBlocking {
                    db.transmissionDao().insert(entity)
                    queue.enqueue(transmissionId, PassId.B_OFFLINE)
                }
                onSegmentPersisted()
                return record
            }
        }
    }
}
