package org.ort.capture.android

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.ort.captureapi.AudioFormat
import org.ort.captureapi.CaptureEvent
import org.ort.captureapi.CaptureSource
import org.ort.captureapi.PolyphaseResampler
import org.ort.captureapi.ResamplerIdentity
import org.ort.core.Clock
import org.ort.core.SystemClock
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The live-device [CaptureSource] (technical design §5.1, build-plan P8). Everything genuinely
 * OS-dependent — enumeration, `setPreferredDevice`, actually reading PCM — is behind [AudioIo];
 * everything this class is actually responsible for is policy, and that policy is exercised
 * against [org.ort.capture.android.fake.FakeAudioIo] in this module's tests:
 *
 * - The route is verified once after the **first** successful read, again on every
 *   [AudioIoEvent.RouteChanged], and periodically thereafter regardless of whether the OS ever
 *   raises an event at all (FR-CAP-3, FR-CAP-3a → AC-2, AC-98; register R-1113). That last part is
 *   P34: a route silently drifting away from the selected device is not guaranteed to produce any
 *   OS callback (see [AndroidAudioIo]'s own kdoc), so [routeProbe] is polled on [clock] time
 *   ([routeReverifyIntervalMillis]) as a background job that is only ever *launched*, never
 *   awaited by the read loop itself — constitution IV requires that a re-verification cannot cost
 *   capture a single frame or a millisecond of latency, so a stalled or slow [routeProbe] simply
 *   never reports back in time, rather than blocking anything (see
 *   `AudioRecordSourcePeriodicReverificationTest`). A mismatch, however it is discovered, halts —
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
    /** See the class kdoc's "periodically thereafter" paragraph and [routeProbe]'s own doc. */
    private val routeReverifyIntervalMillis: Long = ROUTE_REVERIFY_INTERVAL_MILLIS,
    /**
     * How the periodic re-verification job asks what the OS actually routed to — a **suspend**
     * function specifically so a real implementation (or, deliberately, a test) can take an
     * unbounded amount of time to answer without that time ever being charged to the read loop,
     * which only ever `launch`es this, never awaits it inline. The real default is
     * [AudioIo.routedDevice] itself, which today is a cheap, synchronous field read with nothing to
     * stall on — the suspend signature exists for the constitutional guarantee, not because the
     * real path is expected to be slow.
     */
    private val routeProbe: suspend () -> AudioDeviceDescriptor? = { io.routedDevice() },
) : CaptureSource {

    override val deviceFormat: AudioFormat = AudioFormat(io.deviceSampleRate, channels = 1)
    override val outputFormat: AudioFormat = AudioFormat(outputRate, channels = 1)

    private val resampler: PolyphaseResampler? =
        if (io.deviceSampleRate == outputRate) null else PolyphaseResampler(io.deviceSampleRate, outputRate)

    override val resamplerIdentity: ResamplerIdentity? = resampler?.identity

    /**
     * register R-112: the single tap point that hands every verified frame to the level meter —
     * see [LevelMeter]'s own kdoc for why this is safe to call synchronously here (O(n) arithmetic,
     * no allocation beyond one snapshot, no lock, single writer). [RealCaptureService] reads
     * [LevelMeter.snapshot] on the same frame path and republishes it through `LevelStatus`.
     */
    public val levelMeter: LevelMeter = LevelMeter(clock)

    @Volatile private var stopRequested = false

    @Volatile private var lastRouted: AudioDeviceDescriptor? = null

    override fun routedDevice(): String? = lastRouted?.id

    override fun stop() {
        stopRequested = true
    }

    override fun start(): Flow<CaptureEvent> = flow {
        stopRequested = false
        // A plain ArrayDeque would not be safe here any more: AndroidAudioIo's real
        // AudioDeviceCallback can fire from whatever thread the OS calls it on, and the periodic
        // re-verification job below is a second producer running alongside this loop's own
        // consumption of the queue (see the class kdoc's "periodically thereafter" paragraph).
        val pending = ConcurrentLinkedQueue<AudioIoEvent>()
        io.setEventListener { pending.add(it) }
        io.select(selection)
        if (!io.open()) {
            emit(CaptureEvent.Failed("device open failed"))
            return@flow
        }

        coroutineScope {
            var framePos = 0L
            var firstReadVerified = false
            val raw = ShortArray(readBufferFrames)
            // Read afresh whenever a real gap (route-verified recovery) closes, so the outage
            // already reported through AudioIoEvent.Interrupted/Resumed is never also counted as an
            // undetected drop below.
            var lastFrameWallMillis = clock.wallMillis()

            // P34 (register R-1113, constitution IV): the periodic re-verification's own bookkeeping.
            // [reverificationJob] guards against launching a second probe while one is still
            // outstanding (a stalled routeProbe must not accumulate an unbounded number of orphaned
            // jobs), and is explicitly cancelled in the `finally` below on every exit path — a
            // launched child of `coroutineScope` would otherwise be *awaited*, not merely started,
            // which would hang this flow forever behind a stalled probe the moment capture itself
            // is done. Gated on `clock`, not `delay()`: the check is inline in the read loop's own
            // cadence and never itself suspends, so it cannot turn into the kind of perpetually
            // self-rescheduling coroutine that would defeat `advanceUntilIdle()` in a test.
            var lastRouteVerifyWallMillis = clock.wallMillis()
            var reverificationJob: Job? = null

            try {
                while (!stopRequested) {
                    var haltedByMismatch = false
                    while (true) {
                        val ev = pending.poll() ?: break
                        when (ev) {
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
                                    return@coroutineScope
                                }
                                emit(CaptureEvent.Resumed)
                                firstReadVerified = false // re-verify the route on the next read after recovery
                                // The outage just closed is already reported as its own gap; do not
                                // let the elapsed time it took also read as an undetected drop below.
                                lastFrameWallMillis = clock.wallMillis()
                            }
                        }
                        if (haltedByMismatch) {
                            io.close()
                            return@coroutineScope
                        }
                    }

                    // P34: fire-and-forget only — this never suspends the loop below waiting for
                    // the probe. A verifier that never returns simply never reports back; it costs
                    // this loop nothing (see `AudioRecordSourcePeriodicReverificationTest`).
                    if (firstReadVerified &&
                        reverificationJob?.isActive != true &&
                        clock.wallMillis() - lastRouteVerifyWallMillis >= routeReverifyIntervalMillis
                    ) {
                        lastRouteVerifyWallMillis = clock.wallMillis()
                        // UNDISPATCHED: starts synchronously on this same call (so the probe is
                        // genuinely invoked before this loop moves on, not merely queued behind it —
                        // see AudioRecordSourcePeriodicReverificationTest for why that distinction
                        // matters under a cooperative test scheduler) and then suspends like any
                        // other coroutine the instant routeProbe() itself does. The real routeProbe
                        // (`{ io.routedDevice() }`) never actually suspends, so in production this
                        // is indistinguishable from a plain synchronous call that merely happens not
                        // to block the flow's own suspension point.
                        reverificationJob = launch(start = CoroutineStart.UNDISPATCHED) {
                            val routed = routeProbe()
                            if (routed != null && RouteVerifier.verify(selection, routed) is RouteVerdict.Mismatch) {
                                pending.add(AudioIoEvent.RouteChanged)
                            }
                        }
                    }

                    val n = io.read(raw)
                    when {
                        // The device signalled an error without an explicit event — treat it the
                        // same as an interruption rather than silently stopping (constitution IV).
                        n < 0 -> pending.add(AudioIoEvent.Interrupted("read error"))
                        n == 0 -> Unit
                        else -> {
                            if (!firstReadVerified) {
                                lastRouted = io.routedDevice()
                                val verdict = RouteVerifier.verify(selection, lastRouted)
                                if (verdict is RouteVerdict.Mismatch) {
                                    emit(CaptureEvent.Failed("route mismatch: ${verdict.reason}"))
                                    io.close()
                                    return@coroutineScope
                                }
                                firstReadVerified = true
                            }

                            levelMeter.onFrame(raw, n, deviceFormat.sampleRate)

                            val now = clock.wallMillis()
                            val elapsedMillis = (now - lastFrameWallMillis).coerceAtLeast(0)
                            val deviceRate = deviceFormat.sampleRate
                            val expectedSamplesForElapsed = elapsedMillis * deviceRate / MILLIS_PER_SECOND
                            val toleranceSamples = readBufferFrames.toLong() * OVERRUN_TOLERANCE_READ_BUFFERS
                            val dropped = (expectedSamplesForElapsed - n - toleranceSamples).coerceAtLeast(0)
                            if (dropped > 0) {
                                // We could not keep up: real audio time passed (a slow downstream
                                // collector suspended us in `emit`, or the device outran us) that
                                // this read's frames do not account for. Report it, rather than
                                // letting the span vanish — constitution IV.
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
            } finally {
                // Never awaited by structured concurrency's own completion — a probe stalled
                // forever must not keep this flow, or its caller's `collect`, alive after capture
                // itself is done (constitution IV).
                reverificationJob?.cancel()
            }
        }
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
         * FR-CAP-3, register R-1113: how often the route is re-verified even when the OS raises no
         * event at all. Thirty seconds trades detection latency against overhead the same way the
         * capture service's own heartbeat interval does — a route silently drifting away is bad for
         * as long as it goes unnoticed, but this is a poll of a cheap, synchronous field
         * ([AudioIo.routedDevice]), not a reason to run it many times a second.
         */
        public const val ROUTE_REVERIFY_INTERVAL_MILLIS: Long = 30_000L

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
