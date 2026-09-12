package org.ort.pipeline.capture

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * WPARC (constitution IV "capture never blocks, never drops, never lies"): attaches the
 * continuous archive to the audio frame path the same way
 * [org.ort.pipeline.diagnostics.DiagnosticsLog] attaches logging — one unbounded [Channel], one
 * dedicated consumer coroutine off the frame thread entirely. [offer] is a plain, non-suspending
 * function: [Channel.trySend] on an [Channel.UNLIMITED] channel never suspends and never fails
 * for capacity, so a stalled, slow or failing archive writer structurally cannot propagate
 * backpressure onto the caller — the same audio-frame thread that must also feed the segmenter
 * and, unaffected either way, the over-audio [org.ort.segment.SegmentSink].
 *
 * A write that throws is caught here, at the one place this attachment owns, and reported through
 * [onFailure] as a hole for that exact interval — never rethrown (which would only kill this
 * attachment's own consumer coroutine, not capture, but would still silently stop archiving every
 * later chunk) and never left unrecorded (constitution IV: "record that the archive has a hole
 * for that interval, the way a gap is a record", FR-RUN-12). [append]'s own *expected* failure
 * mode — a FLAC verification mismatch — is already handled one layer down, inside
 * [org.ort.capture.android.archive.ContinuousArchiveWriter]'s own `onHole` callback, without ever
 * throwing; [onFailure] here exists for the genuinely unexpected case (a disk error, an I/O
 * exception) that writer does not itself catch.
 */
public class ContinuousArchiveAttachment(
    private val append: suspend (pcm: ShortArray, framePosition: Long) -> Unit,
    private val finishWriter: () -> Unit,
    private val onFailure: (startSample: Long, sampleCount: Int, reason: String) -> Unit = { _, _, _ -> },
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private sealed interface Message {
        data class Append(val pcm: ShortArray, val framePosition: Long) : Message
        data class Barrier(val ack: CompletableDeferred<Unit>) : Message
    }

    private val channel = Channel<Message>(Channel.UNLIMITED)
    private val scope = CoroutineScope(dispatcher + Job())
    private val consumerJob = scope.launch { drain() }

    /**
     * Never suspends, never blocks — see class kdoc. Safe to call from the audio frame thread on
     * every [org.ort.captureapi.CaptureEvent.Frames] event, unconditionally.
     */
    public fun offer(pcm: ShortArray, framePosition: Long) {
        channel.trySend(Message.Append(pcm, framePosition))
    }

    /**
     * Waits until every [offer] enqueued before this call has actually been applied by the
     * consumer, then flushes the writer's own trailing partial chunk via [finishWriter]. Called
     * once, at session end — never on the frame path.
     */
    public suspend fun finishAndAwait() {
        val ack = CompletableDeferred<Unit>()
        val sent = channel.trySend(Message.Barrier(ack))
        if (sent.isFailure) ack.complete(Unit)
        ack.await()
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
}
