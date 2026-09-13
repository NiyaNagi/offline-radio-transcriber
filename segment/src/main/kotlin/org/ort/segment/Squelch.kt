package org.ort.segment

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * FR-SEG-5: one rig squelch transition, already expressed on **this session's own sample
 * timeline** (FR-RUN-16). The conversion from a rig's monotonic receipt timestamp to a sample
 * position (FR-RUN-17), including whatever latency rule applies, happens one layer out —
 * `:pipeline`'s job, never this module's (`:segment` may depend on `:core` and `:onnx` only) —
 * so by the time a [SquelchTransition] reaches [SquelchGate], "where on the audio" is already a
 * settled fact, not a clock reading [Segmenter] has to interpret.
 */
public data class SquelchTransition(public val open: Boolean, public val atSample: Long)

/**
 * FR-SEG-5's fusion input. Where present on a [Segmenter], **rig squelch is authoritative for
 * segment boundaries; VAD is authoritative only for whether the gated interval contains speech**
 * — exactly the split of authority the requirement states. A [Segmenter] built with no
 * [SquelchGate] (the default) is byte-for-byte the pre-FR-SEG-5 VAD-only segmenter; this type
 * changes nothing about that path, which is how "no squelch capability, or rig disconnected"
 * degrades honestly to VAD-only boundaries.
 *
 * [push] is called from the rig's own asynchronous observer — a different thread/coroutine than
 * the one driving [Segmenter.onAudio] — and **must never block or throw**
 * (constitution IV / FR-RUN-1: capture must never stall on the rig). A lock-free queue is enough
 * for that: the audio thread is the only reader, and it only ever drains transitions it already
 * knows it has reached (by sample position), inside [Segmenter.onAudio] itself — never the other
 * way around, so there is no lock two threads could ever contend for.
 */
public class SquelchGate {
    private val pending = ConcurrentLinkedQueue<SquelchTransition>()

    /** Never blocks; never throws; safe to call from any thread. */
    public fun push(open: Boolean, atSample: Long) {
        pending.add(SquelchTransition(open, atSample))
    }

    /**
     * Drains, in arrival order, every transition whose [SquelchTransition.atSample] is strictly
     * before [beforeSample]. Called only from the audio thread, inside [Segmenter.onAudio] — a
     * plain, non-suspending queue drain, so this can never itself block the frame path.
     */
    internal fun drainBefore(beforeSample: Long): List<SquelchTransition> {
        // Single-reader queue (the audio thread, inside Segmenter.onAudio) -- peek() and poll()
        // can never disagree about what the head is, since nothing else ever removes from it.
        val out = ArrayList<SquelchTransition>()
        var head = pending.peek()
        while (head != null && head.atSample < beforeSample) {
            out.add(pending.poll() ?: head)
            head = pending.peek()
        }
        return out
    }
}
