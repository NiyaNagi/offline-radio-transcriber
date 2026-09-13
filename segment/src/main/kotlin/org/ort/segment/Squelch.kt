package org.ort.segment

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * FR-SEG-5: one update drained from a [SquelchGate], applied in arrival order. Either a genuine
 * open/close transition ([Transition]) or a loss-of-authority marker ([Loss],
 * [SquelchGate.markUnknown]) — the fix for register R-1062's follow-up finding: a rig that goes
 * silent (dropped, disconnected, or simply stale past FR-RUN-17's own correlation bound) must
 * revert [Segmenter] to VAD-only, never freeze the last-known squelch state forever.
 *
 * Every field is already expressed on **this session's own sample timeline** (FR-RUN-16). The
 * conversion from a rig's monotonic receipt timestamp to a sample position (FR-RUN-17), and the
 * decision to emit [Loss] at all, both happen one layer out — `:pipeline`'s job, never this
 * module's (`:segment` may depend on `:core` and `:onnx` only) — so by the time an update reaches
 * [SquelchGate], "where on the audio, and what happened" is already a settled fact, not a clock
 * reading or a health signal [Segmenter] has to interpret.
 */
public sealed interface SquelchUpdate {
    public val atSample: Long

    /** Squelch transitioned to [open] as of [atSample]. */
    public data class Transition(public val open: Boolean, override val atSample: Long) : SquelchUpdate

    /**
     * Squelch authority was lost as of [atSample] — the rig disconnected, its transport was
     * lost, or it went stale past the bound [SquelchGate]'s pipeline-side producer applies. From
     * this point, [Segmenter.squelchOpen] reverts to `null` ("not yet known") exactly as if no
     * transition had ever arrived, until a fresh [Transition] re-establishes it.
     */
    public data class Loss(override val atSample: Long) : SquelchUpdate
}

/**
 * FR-SEG-5's fusion input. Where present on a [Segmenter], **rig squelch is authoritative for
 * segment boundaries; VAD is authoritative only for whether the gated interval contains speech**
 * — exactly the split of authority the requirement states. A [Segmenter] built with no
 * [SquelchGate] (the default) is byte-for-byte the pre-FR-SEG-5 VAD-only segmenter; this type
 * changes nothing about that path, which is how "no squelch capability, or rig disconnected"
 * degrades honestly to VAD-only boundaries.
 *
 * [push] and [markUnknown] are called from the rig's own asynchronous observer — a different
 * thread/coroutine than the one driving [Segmenter.onAudio] — and **must never block or throw**
 * (constitution IV / FR-RUN-1: capture must never stall on the rig). A lock-free queue is enough
 * for that: the audio thread is the only reader, and it only ever drains updates it already knows
 * it has reached (by sample position), inside [Segmenter.onAudio] itself — never the other way
 * around, so there is no lock two threads could ever contend for.
 */
public class SquelchGate {
    private val pending = ConcurrentLinkedQueue<SquelchUpdate>()

    /** Never blocks; never throws; safe to call from any thread. */
    public fun push(open: Boolean, atSample: Long) {
        pending.add(SquelchUpdate.Transition(open, atSample))
    }

    /**
     * R-1062 follow-up (FR-SEG-5 / constitution IV): squelch authority is lost as of [atSample].
     * Applied in arrival order exactly like [push] — the same queue, the same non-blocking,
     * non-throwing discipline, callable from any thread. See [SquelchUpdate.Loss]'s own kdoc for
     * what this does once drained.
     */
    public fun markUnknown(atSample: Long) {
        pending.add(SquelchUpdate.Loss(atSample))
    }

    /**
     * Drains, in arrival order, every update whose [SquelchUpdate.atSample] is strictly before
     * [beforeSample]. Called only from the audio thread, inside [Segmenter.onAudio] — a plain,
     * non-suspending queue drain, so this can never itself block the frame path.
     */
    internal fun drainBefore(beforeSample: Long): List<SquelchUpdate> {
        // Single-reader queue (the audio thread, inside Segmenter.onAudio) -- peek() and poll()
        // can never disagree about what the head is, since nothing else ever removes from it.
        val out = ArrayList<SquelchUpdate>()
        var head = pending.peek()
        while (head != null && head.atSample < beforeSample) {
            out.add(pending.poll() ?: head)
            head = pending.peek()
        }
        return out
    }
}
