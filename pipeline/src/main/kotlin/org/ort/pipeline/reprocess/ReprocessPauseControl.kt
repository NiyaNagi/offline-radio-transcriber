package org.ort.pipeline.reprocess

/**
 * register R-1067: the operator's own Pause/Resume toggle on `Improve-Running`, now that the run
 * itself lives in [ReprocessWorker] rather than the screen's own coroutine. Before this, pausing
 * worked by stalling the screen's own collector — a cold `Flow`'s backpressure suspended
 * [ReprocessRunner]'s own producer at its next `emit()` (`org.ort.app.ui.improve.ImproveRunner`'s
 * own kdoc: "Pause/Cancel genuinely stop work, not just stop display"). That mechanism depended on
 * the run living inside the screen's composition — exactly what R-1067 removes. A collector that
 * merely stops observing [ReprocessWorker] must never stop the worker (that would resurrect
 * R-1067: the run must survive the screen going away); so an operator's *deliberate* pause needs a
 * signal that reaches the worker some other way.
 *
 * This is that signal — the same process-wide holder shape as [ReprocessStatus]/
 * `org.ort.pipeline.capture.ShedStatus`/`ThermalStatus` — read by [ReprocessWorker] as one more
 * yield condition alongside the engine's own capture-priority auto-pause (both publish the same
 * [ReprocessStatus.State.Paused]; `ImproveContent.kt`'s own `RunningPage` tells them apart because
 * it is the one that sets [paused] in the first place, so it always knows whether *it* asked).
 *
 * [reset] is called only when a genuinely new run starts (`ImproveContent.kt`'s Start/"Improve all"
 * actions) — never on every recomposition, so a real Activity recreation leaves an operator's own
 * pause exactly as they left it, the same "don't silently discard operator intent" standard R-1063
 * already established for the page itself.
 */
public object ReprocessPauseControl {

    @Volatile
    public var paused: Boolean = false

    public fun reset() {
        paused = false
    }
}
