package org.ort.app

import android.content.Context
import android.util.Log
import org.ort.app.ui.failures.DatabaseOpenFailure
import org.ort.data.OrtDatabase

/**
 * Register R-1120 (halt), FR-STO-7, constitution III: [OrtApplication.onCreate]'s own call site
 * for opening the database exactly once, early, at a controlled point where a throw can actually
 * be caught. Every other caller across `:app` — dozens of `OrtDatabase.create(context
 * .applicationContext)` call sites, one per polling/coordinator class — already assumes opening
 * never throws; before this task, nothing made that true, and whichever call site happened to run
 * first after a violating pre-existing database crashed the process outright, on every subsequent
 * launch, with no in-app route out (register R-1120).
 *
 * This is deliberately the **safety net**, not the fix: [OrtDatabase]'s own hand-written-schema
 * guard (see its kdoc) resolves the one known way a partial unique index can already be violated
 * by a pre-existing row, so [OrtDatabase.create] should not throw for *that* reason again at all.
 * What this object catches is anything else — real corruption, a disk fault, a future schema
 * change this guard does not anticipate — the residual, structurally-impossible-to-fully-close
 * risk every one of the other `OrtDatabase.create()` call sites still carries (this task's scope
 * is the app startup path and the failure screen's wiring, not rewriting every existing call site
 * to expect a throw). [OrtDatabase.create] memoizes its instance per on-disk path (that function's
 * own kdoc), so a successful call here means every later caller this process gets the same,
 * already-open instance back — this really does run "once, early" in the sense that matters.
 *
 * [opener] is a test seam — production always calls the default, a real [OrtDatabase.create].
 */
public object DatabaseStartupWiring {

    public fun openOrRecordFailure(
        context: Context,
        opener: () -> OrtDatabase = { OrtDatabase.create(context.applicationContext) },
    ): OrtDatabase? = try {
        opener().also { DatabaseOpenFailure.clear() }
    } catch (error: Exception) {
        // Deliberately Exception, not Throwable: an Error (OutOfMemoryError, StackOverflowError)
        // is not this object's to swallow and re-present as a recoverable, in-app failure state.
        Log.e(TAG, "database failed to open at startup", error)
        DatabaseOpenFailure.record(error.message ?: error::class.java.simpleName)
        null
    }

    private const val TAG = "DatabaseStartupWiring"
}
