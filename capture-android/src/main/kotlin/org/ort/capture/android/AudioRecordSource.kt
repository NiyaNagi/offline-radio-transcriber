package org.ort.capture.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
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
                    val drained = drainPendingEvents(pending, firstReadVerified, lastFrameWallMillis)
                    firstReadVerified = drained.firstReadVerified
                    lastFrameWallMillis = drained.lastFrameWallMillis
                    if (drained.shouldStop) {
                        // Either a route mismatch (FR-CAP-3) or a clean stop() requested mid-
                        // recovery — drainPendingEvents' own kdoc has already emitted whichever of
                        // Failed/nothing applies; both close the device the same way.
                        io.close()
                        return@coroutineScope
                    }

                    // P34: see maybeStartRouteReverification's own kdoc — fire-and-forget only,
                    // never suspends this loop waiting for the probe.
                    val (updatedJob, updatedVerifyMillis) = maybeStartRouteReverification(
                        active = reverificationJob,
                        lastVerifyWallMillis = lastRouteVerifyWallMillis,
                        firstReadVerified = firstReadVerified,
                        pending = pending,
                    )
                    reverificationJob = updatedJob
                    lastRouteVerifyWallMillis = updatedVerifyMillis

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

    /** [drainPendingEvents]'s own kdoc explains each field. */
    private data class DrainResult(
        val firstReadVerified: Boolean,
        val lastFrameWallMillis: Long,
        val shouldStop: Boolean,
    )

    /**
     * Gate fallout (detekt `LongMethod` on [start], its companion extraction alongside
     * [maybeStartRouteReverification]): drains every [AudioIoEvent] already queued, applying
     * exactly the same policy [start] always has — a route mismatch halts (FR-CAP-3), an
     * interruption recovers via [recoverFromInterruption] or, failing that (a clean [stop] mid-
     * recovery), also halts, and a successful recovery clears [DrainResult.firstReadVerified] so
     * the very next read re-verifies the route.
     *
     * Returns rather than mutates [start]'s own `var`s directly — the two exit points that used to
     * flip `haltedByMismatch` now describe a single [DrainResult.shouldStop] that the caller acts
     * on with `start`'s own `io.close(); return@coroutineScope`, unchanged from before this
     * extraction. When the queue is already empty this returns immediately, `shouldStop = false`,
     * exactly as falling straight through the old inline `while (true) { ... ?: break }` did.
     */
    private suspend fun FlowCollector<CaptureEvent>.drainPendingEvents(
        pending: ConcurrentLinkedQueue<AudioIoEvent>,
        firstReadVerified: Boolean,
        lastFrameWallMillis: Long,
    ): DrainResult {
        var verified = firstReadVerified
        var frameWallMillis = lastFrameWallMillis
        while (true) {
            val ev = pending.poll() ?: return DrainResult(verified, frameWallMillis, shouldStop = false)
            when (ev) {
                AudioIoEvent.RouteChanged -> {
                    emit(CaptureEvent.RouteChanged)
                    lastRouted = io.routedDevice()
                    val verdict = RouteVerifier.verify(selection, lastRouted)
                    if (verdict is RouteVerdict.Mismatch) {
                        emit(CaptureEvent.Failed("route mismatch: ${verdict.reason}"))
                        return DrainResult(verified, frameWallMillis, shouldStop = true)
                    }
                }
                is AudioIoEvent.Interrupted -> {
                    emit(CaptureEvent.Interrupted(ev.cause))
                    if (!recoverFromInterruption()) {
                        // stop() was called while recovering — a clean stop, not a failure.
                        return DrainResult(verified, frameWallMillis, shouldStop = true)
                    }
                    emit(CaptureEvent.Resumed)
                    verified = false // re-verify the route on the next read after recovery
                    // The outage just closed is already reported as its own gap; do not let the
                    // elapsed time it took also read as an undetected drop below.
                    frameWallMillis = clock.wallMillis()
                }
            }
        }
    }

    /**
     * P34 (register R-1113, constitution IV, gate fallout from detekt's `CyclomaticComplexMethod`/
     * `LongMethod` on [start] — extracted, not merely reshuffled, so the guarantees below stay in
     * one named, testable place rather than inline in an already-long loop): starts the periodic
     * route re-verification job when [firstReadVerified], none is already [active], and
     * [routeReverifyIntervalMillis] has elapsed since [lastVerifyWallMillis] — otherwise returns
     * [active] and [lastVerifyWallMillis] unchanged, so the call site can assign the result back
     * to its own `var`s uniformly whether or not a new job actually started.
     *
     * The three properties `AudioRecordSourcePeriodicReverificationTest` pins all live here:
     * - **Launched, never awaited** — this function returns the [Job] immediately; [start]'s own
     *   read loop never suspends on it, so a stalled [routeProbe] costs nothing.
     * - **`CoroutineStart.UNDISPATCHED`** — the probe is genuinely *invoked* before this function
     *   returns (not merely queued behind the read loop), which is what lets the "never blocks"
     *   test observe the probe having started even though it then stalls forever.
     * - **Cancelled in a `finally` on every exit path** — deliberately *not* this function's job:
     *   the returned [Job] is still owned by [start]'s own `try`/`finally`, which is where a probe
     *   stalled forever must be cut loose so it can never keep the flow alive after capture itself
     *   is done. Moving the cancellation in here as well would hide that guarantee behind a second
     *   call site instead of the one `finally` already visible in [start].
     */
    private fun CoroutineScope.maybeStartRouteReverification(
        active: Job?,
        lastVerifyWallMillis: Long,
        firstReadVerified: Boolean,
        pending: ConcurrentLinkedQueue<AudioIoEvent>,
    ): Pair<Job?, Long> {
        val dueForReverification = clock.wallMillis() - lastVerifyWallMillis >= routeReverifyIntervalMillis
        if (!firstReadVerified || active?.isActive == true || !dueForReverification) {
            return active to lastVerifyWallMillis
        }
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            val routed = routeProbe()
            if (routed != null && RouteVerifier.verify(selection, routed) is RouteVerdict.Mismatch) {
                pending.add(AudioIoEvent.RouteChanged)
            }
        }
        return job to clock.wallMillis()
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
