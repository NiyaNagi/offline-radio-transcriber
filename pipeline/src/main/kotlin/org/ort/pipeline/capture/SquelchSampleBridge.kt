package org.ort.pipeline.capture

import org.ort.core.SampleClock
import org.ort.pipeline.rig.RigSquelchTransition
import org.ort.segment.SquelchGate

/**
 * WPSQUELCH (FR-SEG-5 / FR-RUN-16/17): converts one [org.ort.pipeline.rig.RigSupervisor
 * .observeSquelchUnion] transition — timestamped on receipt, in the session's own *monotonic*
 * domain — to this session's *sample-position* domain, and pushes it into [gate]. This is the one
 * place the two clock domains actually meet; `:rig`/`:pipeline`'s [RigSquelchTransition] only ever
 * carries a monotonic reading (see that type's own kdoc), and `:segment`'s
 * [org.ort.segment.SquelchTransition] only ever carries a sample position (same reasoning,
 * mirrored) — neither side is allowed to guess the other's domain.
 *
 * [SampleClock.samplePositionAtMonotonic] already *is* FR-RUN-17's correlation mechanism — "the
 * sample position closest to a monotonic reading (e.g. a rig event timestamped on receipt)" per
 * its own kdoc — so no separate conversion is invented here. The one thing this function adds is
 * the clamp: a transition timestamped at or before the session's own audio anchor (the rig
 * connected and reported before the first audio frame was ever read, an ordering
 * [RealCaptureService.startCapture] does not otherwise guarantee) would make
 * [SampleClock.samplePositionAtMonotonic] throw on a negative delta — clamping to the anchor
 * (sample 0) instead is FR-RUN-1's "never blocks or crashes the audio path on the rig" applied to
 * this literal edge case, not a guessed sample: there is no more honest answer than "at or before
 * the first sample this session ever has".
 *
 * R-1062 follow-up: [RigSquelchTransition.open] `null` means squelch authority was lost (a
 * transport drop, a disconnect, or staleness — see [RigSquelchTransition]'s own kdoc), pushed
 * through as [SquelchGate.markUnknown] rather than [SquelchGate.push] — the same sample-position
 * conversion and the same clamp apply to it unchanged.
 */
internal fun pushSquelchTransition(gate: SquelchGate, sampleClock: SampleClock, transition: RigSquelchTransition) {
    val clampedNanos = maxOf(transition.timestampNanos, sampleClock.anchorMonotonicNanos)
    val samplePosition = sampleClock.samplePositionAtMonotonic(clampedNanos)
    val open = transition.open
    if (open == null) {
        gate.markUnknown(samplePosition)
    } else {
        gate.push(open, samplePosition)
    }
}
