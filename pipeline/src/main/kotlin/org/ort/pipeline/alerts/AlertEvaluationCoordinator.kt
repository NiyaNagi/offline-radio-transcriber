package org.ort.pipeline.alerts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.ort.pipeline.diagnostics.DiagnosticsLog

/**
 * Build-plan P31 (FR-ALR-3, FR-ALR-4): the one call
 * [org.ort.pipeline.passb.DataPassBResultSink.record] makes after a transmission's attribution is
 * recorded. [fireAndForget] is deliberately **not** `suspend` — it returns the instant it has
 * launched the real evaluation onto [scope], never once it has finished, so a caller can call it
 * from an ordinary function with no coroutine of its own and never wait on anything this package
 * does (constitution IV, FR-ALR-4: "capture SHALL NEVER wait on alert evaluation or delivery" —
 * extended here to Pass B's own write transaction, which must not stall on it either).
 */
public fun interface AlertEvaluationTrigger {
    public fun fireAndForget(input: AlertMatchInput)
}

/** The default every [org.ort.pipeline.passb.DataPassBResultSink] construction gets until a real
 * [AlertEvaluationCoordinator] is wired in — an honest no-op, never a silently-broken real one. */
public object NoOpAlertEvaluationTrigger : AlertEvaluationTrigger {
    override fun fireAndForget(input: AlertMatchInput): Unit = Unit
}

/**
 * Build-plan P31 — the real [AlertEvaluationTrigger]: reads every enabled watch from [watchStore],
 * matches each against [AlertMatchInput] via [AlertMatcher], and hands every match to [dispatcher],
 * coalesced per watch.
 *
 * **Coalescing rule (functional spec §7.19: "a watch that matches fifty times should not produce
 * fifty notifications"):** per watch id, this class remembers only the wall time of its most
 * recent match. A match arriving within [coalesceWindowMillis] of the previous one for the *same*
 * watch is a "repeat" — [AlertFiring.isRepeat] is `true`, [AlertFiring.occurrenceCount] keeps
 * counting up, and [AndroidAlertNotificationDispatcher] reuses the same notification id with
 * `setOnlyAlertOnce(true)`, so the operator is alerted once and the notification's own text keeps
 * updating silently thereafter. A match arriving *after* the window has elapsed starts a fresh
 * episode (count resets to 1, alerts again) — an operator who steps away for an hour and comes back
 * to a watch matching again is meant to notice that, not have it silently folded into a stale
 * count. The default window (10 minutes) is chosen to span one ordinary QSO/net's own overs
 * without re-alerting on every individual transmission, while still being short enough that two
 * genuinely separate appearances hours apart read as separate events. State is **per process**,
 * not persisted — a fresh app process (a reboot, a force-stop) starts every watch's count over,
 * the same "no state survives between calls" discipline
 * [org.ort.pipeline.threading.ThreadGroupingCoordinator]'s own doc comment states for the
 * identical reason: nothing here needs to survive a restart to be correct, and not persisting it
 * keeps this class free of its own storage format to maintain.
 *
 * **FR-ALR-4 in practice:** [fireAndForget] only ever launches [evaluate] onto [scope]
 * (`Dispatchers.Default` by default, a `SupervisorJob` so one watch's own dispatcher exception
 * never cancels evaluation for the rest) — it never runs [evaluate] on the calling thread, and the
 * whole body of [evaluate] is wrapped in [runCatching] besides, so a hung or throwing
 * [AlertWatchStore]/[AlertNotificationDispatcher] can neither block nor crash the caller.
 */
public class AlertEvaluationCoordinator(
    private val watchStore: AlertWatchStore,
    private val dispatcher: AlertNotificationDispatcher,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val coalesceWindowMillis: Long = DEFAULT_COALESCE_WINDOW_MILLIS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AlertEvaluationTrigger {

    private val lastFired = mutableMapOf<String, WatchFireState>()
    private val lastFiredLock = Any()

    override fun fireAndForget(input: AlertMatchInput) {
        scope.launch { runCatching { evaluate(input) } }
    }

    /** `internal`, not `private`: a test drives this directly (no coroutine dispatch to await)
     * when it wants a synchronous assertion about *what* matched, keeping [fireAndForget]'s own
     * "never blocks the caller" contract as the only thing the async-specific tests need to prove. */
    internal fun evaluate(input: AlertMatchInput) {
        if (!watchStore.alertsEnabled) return
        watchStore.list()
            .filter { it.enabled }
            .filter { AlertMatcher.matches(it, input) }
            .forEach { watch -> dispatchCoalesced(watch, input) }
    }

    private fun dispatchCoalesced(watch: AlertWatch, input: AlertMatchInput) {
        val now = clockMillis()
        val firing = synchronized(lastFiredLock) {
            val prior = lastFired[watch.id]
            val isRepeat = prior != null && now - prior.atMillis < coalesceWindowMillis
            val count = if (isRepeat) prior!!.count + 1 else 1
            lastFired[watch.id] = WatchFireState(now, count)
            AlertFiring(watch, input, occurrenceCount = count, isRepeat = isRepeat)
        }
        // Register R-1097 (constitution I): the `Boolean` dispatch() returns is the platform's own
        // honest word on whether anything actually reached the operator (POST_NOTIFICATIONS denied
        // being the one real cause today) -- discarding it made a refused firing indistinguishable
        // from a delivered one, an "I told you" nobody was actually told. See
        // DiagnosticsLog.logAlertDeliveryFailed's own kdoc for why a durable, exportable trace, not
        // a coalescing-state change, is the fix at this layer.
        val delivered = dispatcher.dispatch(firing)
        if (!delivered) DiagnosticsLog.logAlertDeliveryFailed(watch.id)
    }

    private data class WatchFireState(val atMillis: Long, val count: Int)

    public companion object {
        /** functional spec §7.19 — see this class's own doc comment for why 10 minutes. */
        public const val DEFAULT_COALESCE_WINDOW_MILLIS: Long = 10 * 60 * 1000L
    }
}
