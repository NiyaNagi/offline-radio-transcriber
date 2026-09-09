package org.ort.app.ui.navigation

import android.content.Context
import org.ort.app.ui.data.TimeWindow
import org.ort.core.SystemClock
import org.ort.data.OrtDatabase

/**
 * Register R-432: `Frequency-Change.dc.html`'s second bottom pill, "The activation thread" —
 * `FrequencyChangeScreen.onOpenThread` (WP8) carries no arguments at all (that parameter's own doc
 * comment: "opens T02 for the busiest thread in this window ... WP3 routes the destination once it
 * can identify that thread"), so this resolves the destination independently rather than threading
 * a new argument through WP8's file.
 *
 * The window mirrors `FrequencyPolling.frequencyChange`'s own `tonightWindow` exactly — tonight's
 * (the most recent session's) start to end — without re-running that whole read path (`causes`,
 * the hourly chart, the explanation paragraph) purely to recover one field this package's own row
 * cannot import (`StationPolling.kt`'s `SharedDatabase` is `private` to that file, and the row this
 * package owns is the whole `ui/navigation` tree, not `ui/data` — see this file's own placement).
 * "The thread that contains the first over on [frequencyHz] after the change time": the earliest
 * transmission on that frequency within tonight's window, then that transmission's own `threadId`
 * — `null` when it has not been grouped into a thread yet (pre-M6, or this exact transmission never
 * joined one), which [resolveActivationThread]'s caller (`OrtNavHost.kt`) treats as "no thread
 * exists" and falls through to the Log filtered to the same window, rather than a no-op.
 */
internal object ActivationThreadRouting {

    /** [threadId] is `null` exactly when no thread exists to route to — see this object's own doc
     * comment. [window] is returned regardless, so a `null` [threadId] still carries what the Log
     * fallback (`NavHostCallbacks.onOpenOvers`) needs. */
    internal data class Resolution(val threadId: String?, val window: TimeWindow)

    internal suspend fun resolveActivationThread(context: Context, frequencyHz: Long): Resolution {
        val db = OrtDatabase.create(context.applicationContext)
        val latestSession = db.sessionDao().listAll().firstOrNull()
        val window = TimeWindow(
            startMillis = latestSession?.startedAt ?: 0L,
            endMillis = latestSession?.endedAt ?: SystemClock.wallMillis(),
        )
        val firstOver = db.activityDao().transmissionsForFrequency(frequencyHz)
            .filter { it.startedAtUtc in window.startMillis until window.endMillis }
            .minByOrNull { it.startedAtUtc }
        return Resolution(threadId = firstOver?.threadId, window = window)
    }
}
