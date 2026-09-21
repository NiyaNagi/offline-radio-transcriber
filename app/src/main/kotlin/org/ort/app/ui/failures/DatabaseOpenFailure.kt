package org.ort.app.ui.failures

/**
 * Register R-1120 (halt), FR-STO-7, constitution III ("never delete quietly") and constitution I
 * ("uncertainty is content"): the safety net behind
 * [org.ort.data.OrtDatabase]'s own `applyHandWrittenSchema` dedup guard (see that function's own
 * kdoc for the actual fix — resolving a pre-existing partial-unique-index violation before the
 * index is built, so [org.ort.data.OrtDatabase.create] does not throw for that reason at all).
 * This holder exists for the case the guard does not cover: any *other* throw out of [create] —
 * real corruption, a disk fault, a future hand-written statement this guard does not anticipate.
 * Before this task, such a throw propagated out of whichever call site hit it first (one of dozens
 * across `:app`), crashing the process outright, on every subsequent launch, with no in-app route
 * out — the exact "permanently unlaunchable app" register R-1120 names.
 *
 * [org.ort.app.DatabaseStartupWiring] is the one writer, called once from
 * [org.ort.app.OrtApplication.onCreate]; [FailureSignalsPolling] is the one reader, feeding
 * [FailureMapper.map] the real signal that routes to `Fail-Migration.dc.html`
 * ([FailurePresentation.Migration]) instead of the process crashing again. Process-wide state, the
 * same shape as every other real-signal holder this package already reads
 * ([org.ort.pipeline.capture.CaptureState], [org.ort.pipeline.capture.InputStatus], ...) —
 * deliberately not cleared by a poll tick: a database that failed to open at startup does not
 * un-fail itself just because time passed.
 */
public object DatabaseOpenFailure {

    @Volatile
    public var reason: String? = null
        private set

    /** [org.ort.app.DatabaseStartupWiring]'s own write on a caught throw — [message] is the real
     * exception message or class name, never invented. */
    public fun record(message: String) {
        reason = message
    }

    /** Test/scenario seam, and [org.ort.app.DatabaseStartupWiring]'s own write on a successful
     * open (so a later, successful retry clears a stale failure rather than latching it forever). */
    public fun clear() {
        reason = null
    }
}
