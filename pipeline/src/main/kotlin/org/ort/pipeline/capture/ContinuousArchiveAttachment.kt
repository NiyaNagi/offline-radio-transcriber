package org.ort.pipeline.capture

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * WPARC (constitution IV "capture never blocks, never drops, never lies"): attaches the
 * continuous archive to the audio frame path the same way
 * [org.ort.pipeline.diagnostics.DiagnosticsLog] attaches logging — one bounded [Channel], one
 * dedicated consumer coroutine off the frame thread entirely. [offer] is a plain, non-suspending
 * function: [Channel.trySend] never suspends, so a stalled, slow or failing archive writer
 * structurally cannot propagate backpressure onto the caller — the same audio-frame thread that
 * must also feed the segmenter and, unaffected either way, the over-audio
 * [org.ort.segment.SegmentSink].
 *
 * **Register R-1038: bounded, not unlimited.** The first version of this class used
 * [Channel.UNLIMITED] on the reasoning that a slow writer must never lose a frame — proven by the
 * capacity-1 revert in this class's own test history, which showed small buffers really do drop
 * data. But "never blocks" and "never dies" are different guarantees: a writer that *stalls*
 * outright (a wedged filesystem, a hung codec, storage I/O starved by the OS) makes an unlimited
 * queue grow on the heap without bound — 16 kHz mono PCM16 is 115.2 MB per hour — until the OS
 * kills the *process*, taking capture down with the archive that was supposed to be secondary to
 * it. [capacity] bounds that growth to a fixed, small footprint regardless of how long a stall
 * lasts; beyond it, [offer] drops the new frame instead of buffering it, and the dropped interval
 * is reported — coalesced, not one event per frame — through [onOverflow], never silently.
 *
 * A write that throws (as opposed to a queue that is merely full) is caught here, at the one
 * place this attachment owns, and reported through [onFailure] as a hole for that exact interval
 * — never rethrown (which would only kill this attachment's own consumer coroutine, not capture,
 * but would still silently stop archiving every later chunk) and never left unrecorded
 * (constitution IV: "record that the archive has a hole for that interval, the way a gap is a
 * record", FR-RUN-12). [append]'s own *expected* failure mode — a FLAC verification mismatch — is
 * already handled one layer down, inside
 * [org.ort.capture.android.archive.ContinuousArchiveWriter]'s own `onHole` callback, without ever
 * throwing; [onFailure] here exists for the genuinely unexpected case (a disk error, an I/O
 * exception) that writer does not itself catch.
 */
public class ContinuousArchiveAttachment(
    private val append: suspend (pcm: ShortArray, framePosition: Long) -> Unit,
    private val finishWriter: () -> Unit,
    private val onFailure: (startSample: Long, sampleCount: Int, reason: String) -> Unit = { _, _, _ -> },
    /** R-1038: a coalesced report of frames dropped because the queue was full — one call per
     * *span* of consecutive drops, not one per frame. Closed either when a later [offer] finally
     * succeeds again (the writer recovered) or when [finishAndAwait] runs (the session ended
     * while still stalled) — never left open past either of those. */
    private val onOverflow: (startSample: Long, sampleCount: Int) -> Unit = { _, _ -> },
    capacity: Int = DEFAULT_CAPACITY,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private sealed interface Message {
        data class Append(val pcm: ShortArray, val framePosition: Long) : Message
        data class Barrier(val ack: CompletableDeferred<Unit>) : Message
    }

    private val channel = Channel<Message>(capacity)
    private val scope = CoroutineScope(dispatcher + Job())
    private val consumerJob = scope.launch { drain() }

    // R-1038: coalesces a run of consecutive drops into one [onOverflow] report. Touched only
    // from [offer], which this class's whole contract assumes is called from a single audio-frame
    // thread/coroutine, never concurrently (the same single-writer assumption
    // GapTracker/DroppedSpanCause already make elsewhere in this codebase) -- no synchronization
    // needed.
    private var overflowStartSample: Long? = null
    private var overflowSampleCount: Int = 0

    /**
     * Never suspends, never blocks — see class kdoc. Safe to call from the audio frame thread on
     * every [org.ort.captureapi.CaptureEvent.Frames] event, unconditionally. On overflow, drops
     * [pcm] rather than buffering it — over audio and capture are completely unaffected either
     * way, since neither is fed through this call.
     */
    public fun offer(pcm: ShortArray, framePosition: Long) {
        val sent = channel.trySend(Message.Append(pcm, framePosition))
        if (sent.isSuccess) {
            closeOverflowSpanIfOpen()
        } else {
            if (overflowStartSample == null) overflowStartSample = framePosition
            overflowSampleCount += pcm.size
        }
    }

    private fun closeOverflowSpanIfOpen() {
        val start = overflowStartSample ?: return
        onOverflow(start, overflowSampleCount)
        overflowStartSample = null
        overflowSampleCount = 0
    }

    /**
     * Waits until every [offer] enqueued before this call has actually been applied by the
     * consumer, then flushes the writer's own trailing partial chunk via [finishWriter]. Called
     * once, at session end — never on the frame path. Any overflow span still open (the writer
     * was stalled and never recovered before the session ended) is closed here rather than lost.
     *
     * **R-1038: bounded by [timeoutMillis], never an indefinite wait.** A writer wedged badly
     * enough to still be stuck on its very first queued append would otherwise make this call —
     * which production calls from [RealCaptureService]'s own `runBlocking(Dispatchers.IO)` at
     * session end — hang forever, turning a stalled *archive* into a stalled *session teardown*.
     * On timeout this gives up on the writer (cancelling its consumer coroutine) rather than
     * ever finishing it; the session's own end-of-session bookkeeping proceeds regardless.
     */
    public suspend fun finishAndAwait(timeoutMillis: Long = FINISH_TIMEOUT_MILLIS) {
        closeOverflowSpanIfOpen()
        val ack = CompletableDeferred<Unit>()
        val sent = channel.trySend(Message.Barrier(ack))
        if (sent.isFailure) ack.complete(Unit)
        val acknowledged = withTimeoutOrNull(timeoutMillis) { ack.await() } != null
        if (!acknowledged) {
            // The consumer is stuck on a message enqueued before this barrier and will never
            // reach it -- give up on this session's archive rather than block session teardown.
            consumerJob.cancel()
            return
        }
        // The barrier above guarantees every prior Append has already run on the consumer, so
        // finishWriter can run right here, on the caller, with no second round trip needed.
        runCatching { finishWriter() }
    }

    /** Test/shutdown-only: stops the consumer without draining or flushing. */
    public fun shutdown() {
        consumerJob.cancel()
        channel.close()
    }

    private suspend fun drain() {
        for (message in channel) {
            when (message) {
                is Message.Barrier -> message.ack.complete(Unit)
                is Message.Append -> runCatching { append(message.pcm, message.framePosition) }
                    .onFailure { t ->
                        onFailure(message.framePosition, message.pcm.size, t::class.simpleName ?: "unknown")
                    }
            }
        }
    }

    public companion object {
        /**
         * R-1038: **derived**, not guessed. [org.ort.capture.android.AudioRecordSource] delivers
         * one [org.ort.captureapi.CaptureEvent.Frames] event per
         * [org.ort.capture.android.AudioRecordSource.DEFAULT_READ_BUFFER_FRAMES] samples at
         * [org.ort.segment.FrameSpec.SAMPLE_RATE] — 1,600 samples at 16 kHz, i.e. one message
         * every 100 ms, ten a second. [ARCHIVE_QUEUE_BOUND_SECONDS] (10) is roughly a hundred
         * times that ordinary per-frame cadence, comfortably absorbing the one recurring slower
         * step in this path — [org.ort.capture.android.archive.ContinuousArchiveWriter]'s FLAC
         * encode-and-verify, which runs only once per 30 s chunk flush, not per frame — while
         * still bounding worst-case heap growth to a fixed ~312 KB of raw PCM
         * (`DEFAULT_CAPACITY` × 1,600 shorts × 2 bytes) regardless of how long an actual stall
         * lasts. [RealCaptureService] passes this same derivation explicitly at the call site
         * rather than relying on this default silently matching it.
         */
        public const val ARCHIVE_QUEUE_BOUND_SECONDS: Int = 10
        private const val FRAMES_PER_SECOND: Int =
            org.ort.segment.FrameSpec.SAMPLE_RATE / org.ort.capture.android.AudioRecordSource.DEFAULT_READ_BUFFER_FRAMES
        public const val DEFAULT_CAPACITY: Int = ARCHIVE_QUEUE_BOUND_SECONDS * FRAMES_PER_SECOND

        /** R-1038: generous enough that a healthy writer's own [finishAndAwait] call never comes
         * close to it, short enough that a genuinely wedged writer cannot hang session teardown
         * for anything an operator would perceive as "the app is frozen". */
        public const val FINISH_TIMEOUT_MILLIS: Long = 5_000L
    }
}
