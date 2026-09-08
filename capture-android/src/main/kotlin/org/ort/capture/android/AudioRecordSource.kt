package org.ort.capture.android

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.ort.captureapi.AudioFormat
import org.ort.captureapi.CaptureEvent
import org.ort.captureapi.CaptureSource
import org.ort.captureapi.PolyphaseResampler
import org.ort.captureapi.ResamplerIdentity
import org.ort.core.Clock
import org.ort.core.SystemClock

/**
 * The live-device [CaptureSource] (technical design §5.1, build-plan P8). Everything genuinely
 * OS-dependent — enumeration, `setPreferredDevice`, actually reading PCM — is behind [AudioIo];
 * everything this class is actually responsible for is policy, and that policy is exercised
 * against [org.ort.capture.android.fake.FakeAudioIo] in this module's tests:
 *
 * - The route is verified once after the **first** successful read, and again on every
 *   [AudioIoEvent.RouteChanged] (FR-CAP-3, FR-CAP-3a → AC-2, AC-98). A mismatch halts —
 *   [CaptureEvent.Failed] — and capture never falls back to whatever the OS routed to instead.
 * - [AudioIoEvent.Interrupted] opens a gap: [CaptureEvent.Interrupted] is emitted, then the
 *   source retries on [BackoffLadder] until the device reopens, emitting [CaptureEvent.Resumed]
 *   the moment it does — never giving up (FR-RUN-11 → AC-48).
 * - [resamplerIdentity] is recorded whenever [deviceFormat] differs from [outputFormat]
 *   (FR-CAP-2a → AC-97), reusing `:capture-api`'s [PolyphaseResampler] — the same one
 *   `WavFileSource` uses, so a number measured through one is measured through the same filter
 *   as the other.
 * - A stalled downstream collector or an `AudioRecord` shortfall is **detected, never silent**
 *   (AC-3, constitution IV): each successful read compares the real wall time elapsed since the
 *   previous one (via [clock]) against the audio actually delivered. When the gap between them
 *   exceeds [OVERRUN_TOLERANCE_READ_BUFFERS] read-buffers' worth of slack, the excess is reported
 *   as dropped samples through [DroppedSpanCause], which [GapTracker] turns into a real gap row
 *   without needing a live device to reproduce an actual overrun.
 */
public class AudioRecordSource(
    private val io: AudioIo,
    private val selection: AudioDeviceDescriptor,
    outputRate: Int = AudioFormat.OUTPUT_SAMPLE_RATE,
    private val readBufferFrames: Int = DEFAULT_READ_BUFFER_FRAMES,
    private val clock: Clock = SystemClock,
) : CaptureSource {

    override val deviceFormat: AudioFormat = AudioFormat(io.deviceSampleRate, channels = 1)
    override val outputFormat: AudioFormat = AudioFormat(outputRate, channels = 1)

    private val resampler: PolyphaseResampler? =
        if (io.deviceSampleRate == outputRate) null else PolyphaseResampler(io.deviceSampleRate, outputRate)

    override val resamplerIdentity: ResamplerIdentity? = resampler?.identity

    @Volatile private var stopRequested = false

    @Volatile private var lastRouted: AudioDeviceDescriptor? = null

    override fun routedDevice(): String? = lastRouted?.id

    override fun stop() {
        stopRequested = true
    }

    override fun start(): Flow<CaptureEvent> = flow {
        stopRequested = false
        val pending = ArrayDeque<AudioIoEvent>()
        io.setEventListener { pending.addLast(it) }
        io.select(selection)
        if (!io.open()) {
            emit(CaptureEvent.Failed("device open failed"))
            return@flow
        }

        var framePos = 0L
        var firstReadVerified = false
        val raw = ShortArray(readBufferFrames)
        // Read afresh whenever a real gap (route-verified recovery) closes, so the outage
        // already reported through AudioIoEvent.Interrupted/Resumed is never also counted as an
        // undetected drop below.
        var lastFrameWallMillis = clock.wallMillis()

        while (!stopRequested) {
            var haltedByMismatch = false
            while (pending.isNotEmpty()) {
                when (val ev = pending.removeFirst()) {
                    AudioIoEvent.RouteChanged -> {
                        emit(CaptureEvent.RouteChanged)
                        lastRouted = io.routedDevice()
                        val verdict = RouteVerifier.verify(selection, lastRouted)
                        if (verdict is RouteVerdict.Mismatch) {
                            emit(CaptureEvent.Failed("route mismatch: ${verdict.reason}"))
                            haltedByMismatch = true
                        }
                    }
                    is AudioIoEvent.Interrupted -> {
                        emit(CaptureEvent.Interrupted(ev.cause))
                        if (!recoverFromInterruption()) {
                            // stop() was called while recovering — a clean stop, not a failure.
                            io.close()
                            return@flow
                        }
                        emit(CaptureEvent.Resumed)
                        firstReadVerified = false // re-verify the route on the next read after recovery
                        // The outage just closed is already reported as its own gap; do not let
                        // the elapsed time it took also read as an undetected drop below.
                        lastFrameWallMillis = clock.wallMillis()
                    }
                }
                if (haltedByMismatch) {
                    io.close()
                    return@flow
                }
            }

            val n = io.read(raw)
            when {
                // The device signalled an error without an explicit event — treat it the same
                // as an interruption rather than silently stopping (constitution IV).
                n < 0 -> pending.addLast(AudioIoEvent.Interrupted("read error"))
                n == 0 -> Unit
                else -> {
                    if (!firstReadVerified) {
                        lastRouted = io.routedDevice()
                        val verdict = RouteVerifier.verify(selection, lastRouted)
                        if (verdict is RouteVerdict.Mismatch) {
                            emit(CaptureEvent.Failed("route mismatch: ${verdict.reason}"))
                            io.close()
                            return@flow
                        }
                        firstReadVerified = true
                    }

                    val now = clock.wallMillis()
                    val elapsedMillis = (now - lastFrameWallMillis).coerceAtLeast(0)
                    val deviceRate = deviceFormat.sampleRate
                    val expectedSamplesForElapsed = elapsedMillis * deviceRate / MILLIS_PER_SECOND
                    val toleranceSamples = readBufferFrames.toLong() * OVERRUN_TOLERANCE_READ_BUFFERS
                    val dropped = (expectedSamplesForElapsed - n - toleranceSamples).coerceAtLeast(0)
                    if (dropped > 0) {
                        // We could not keep up: real audio time passed (a slow downstream
                        // collector suspended us in `emit`, or the device outran us) that this
                        // read's frames do not account for. Report it, rather than letting the
                        // span vanish — constitution IV.
                        val droppedMillis = dropped * MILLIS_PER_SECOND / deviceRate
                        emit(CaptureEvent.Interrupted(DroppedSpanCause.encode(dropped, droppedMillis)))
                        emit(CaptureEvent.Resumed)
                    }
                    lastFrameWallMillis = now

                    val block = raw.copyOf(n)
                    val out = resampler?.resample(block) ?: block
                    emit(CaptureEvent.Frames(out, framePos))
                    framePos += out.size
                }
            }
        }
        io.close()
    }

    /** Retries on [BackoffLadder] until the device reopens with a matching route, or [stop] is called. */
    private suspend fun recoverFromInterruption(): Boolean {
        var attempt = 1
        while (!stopRequested) {
            delay(BackoffLadder.delayMillisFor(attempt))
            attempt++
            io.close()
            if (io.open()) {
                lastRouted = io.routedDevice()
                if (RouteVerifier.verify(selection, lastRouted) is RouteVerdict.Ok) return true
                // A mismatch surfacing during recovery is still a halt, handled by the caller
                // re-checking lastRouted; returning true here lets the read loop's own
                // first-read verification catch and report it precisely.
                return true
            }
        }
        return false
    }

    public companion object {
        public const val DEFAULT_READ_BUFFER_FRAMES: Int = 1_600

        /**
         * Slack, in multiples of [readBufferFrames], allowed before an elapsed-time/frames-
         * delivered mismatch is reported as dropped samples rather than ordinary scheduling
         * jitter (startup latency, a slightly late coroutine dispatch). Generous on purpose —
         * false positives make every recording look faulty; false negatives just mean a very
         * short overrun goes unreported, which the pre-existing behaviour already accepted.
         */
        internal const val OVERRUN_TOLERANCE_READ_BUFFERS: Long = 4L

        private const val MILLIS_PER_SECOND: Long = 1_000L
    }
}
