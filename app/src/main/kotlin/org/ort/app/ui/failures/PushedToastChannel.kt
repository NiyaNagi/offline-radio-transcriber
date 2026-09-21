package org.ort.app.ui.failures

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel

/**
 * Register R-1008 ("app-wide", `Feedback.dc.html`): the one missing piece for surfacing the
 * result of an action taken on a sub-screen once the operator has already navigated away from it.
 * [FailureHost] (this package's own class kdoc: "mounted once, in `ReaderActivity.kt`, above
 * `OrtNavHost`") and [org.ort.app.ui.components.Toast] already exist and already survive a
 * destination change on their own — what was missing was a way for a screen that is *not*
 * [FailureHost] itself to hand it a [RecoveryToast] at all. Before this, the only toasts
 * [FailureHost] ever showed were ones its own polling loop derived from two consecutive
 * [FailureSignals] snapshots ([RecoveryAnnouncer]) — never a screen's own, already-known, one-shot
 * result ("6 overs updated").
 *
 * **Why a [Channel] and not a `StateFlow`/plain `var`** (the [org.ort.pipeline.capture.CaptureState]/
 * [org.ort.pipeline.capture.LevelStatus] shape this codebase otherwise prefers for process-wide,
 * in-memory state): a toast is a one-shot *event*, and this row's two requirements are exactly
 * what that shape cannot give at once. A plain mutable holder is *state* — a second push before
 * the first is read overwrites it, silently dropping one (violates "must not be lost"). A
 * `StateFlow`/`SharedFlow(replay = 1)` fixes that but then *re-delivers* its last value to every
 * new collector that starts afterwards — a recomposition, a config change — so a toast already
 * shown once would show again (violates "must not be shown twice"). A [Channel] is a queue:
 * [Channel.UNLIMITED] never drops a [push] (this package's own [FailureHost] is a permanent
 * collector for the process's lifetime — see [receive] — so nothing here needs to outlive a
 * process death, no worse a guarantee than [org.ort.pipeline.capture.CaptureState]'s own
 * documented "process-wide holder ... not a substitute for" durable storage), and each pushed
 * value is handed to exactly one [receive] call, exactly once — the "not lost, not duplicated"
 * pair this row actually asks for.
 *
 * **The undo contract** ([RecoveryToast.onUndo]): this channel is what makes it *possible* for an
 * undo to survive a destination change, but not by itself *safe* — that depends entirely on how
 * the caller builds the closure. Safe: a closure over data the action's own result already
 * produced by value (an id, a captured outcome) at the moment [push] is called. Unsafe, and
 * indistinguishable from safe at the type level: a closure that instead reads an *ambient*
 * "current selection" or "the active screen" — ids that this object has already, correctly,
 * outlived by the time `onUndo` actually runs, but which now refer to whatever the operator has
 * since navigated to, not the thing the toast is actually about. Nothing here can enforce that at
 * compile time; it is a contract on the caller, stated here because this is the one place a future
 * caller will read before wiring one in.
 */
public object PushedToastChannel {
    private var channel = Channel<RecoveryToast>(Channel.UNLIMITED)

    /**
     * Enqueues [toast] for [FailureHost]'s own toast slot to show next, however many destinations
     * away the operator has since navigated. Never suspends — [Channel.UNLIMITED] has no capacity
     * to block on — so a caller mid-navigation (a back-stack pop, a `finish()`) is never delayed
     * by this call.
     */
    public fun push(toast: RecoveryToast) {
        channel.trySend(toast)
    }

    /**
     * Register R-1139: the scope a pushed toast's own [RecoveryToast.onUndo] runs its (suspending)
     * work on. [RecoveryToast.onUndo] is a plain, non-suspending `() -> Unit` — [FailureHost]'s own
     * [ToastSlot] calls it directly from a click, exactly as
     * `org.ort.app.ui.components.Toast`'s own `onUndo` always has — so whichever screen *builds*
     * that closure is the one place a suspend write like
     * [org.ort.app.ui.data.CorrectionPolling.undoAll] can be launched from, and this file's own
     * class kdoc already names the trap: a producer's own `rememberCoroutineScope()` is cancelled
     * the instant the screen that pushed the toast leaves composition — which for a toast pushed
     * specifically because the operator is about to navigate away is not "eventually", it is
     * "immediately". This scope, like [channel] itself, lives for the process — never cancelled,
     * [SupervisorJob] so one undo's failure cannot poison another's — the identical "no worse a
     * guarantee than a process-wide holder" reasoning this file's own class kdoc already gives for
     * [Channel.UNLIMITED].
     */
    public val undoScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * [FailureHost]'s own collector: suspends until the next pushed toast. Exactly one caller
     * should ever run this in a loop for the process's lifetime — a second, concurrent collector
     * would each receive a disjoint subset of pushes ([Channel]'s own fan-out semantics between
     * multiple receivers), silently splitting the stream rather than duplicating it.
     */
    public suspend fun receive(): RecoveryToast = channel.receive()

    /**
     * Test-only (see [PushedToastChannelTest]/`PushedToastFailureHostTest`): this object is a
     * plain Kotlin `object`, so [channel] is a single, process-lifetime instance shared by every
     * test that runs in the same JVM fork — a test that pushes and never drains left that fact
     * readable by whichever unrelated test happens to run next in the same fork. Replacing the
     * channel outright (rather than draining the old one) also discards anything left mid-flight
     * from a failed prior test, so a fresh test never inherits a stale toast. Production code never
     * calls this; a fresh process already starts with an empty channel.
     */
    internal fun resetForTest() {
        channel = Channel(Channel.UNLIMITED)
    }

    /** Test-only: a non-suspending drain used to prove a channel that has genuinely delivered
     * everything already pushed to it reports nothing further, rather than eventually redelivering
     * — see [PushedToastChannelTest]'s own "not shown twice" case. */
    internal fun tryReceiveForTest(): RecoveryToast? = channel.tryReceive().getOrNull()
}
