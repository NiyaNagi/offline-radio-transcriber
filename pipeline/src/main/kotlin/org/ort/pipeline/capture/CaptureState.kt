package org.ort.pipeline.capture

/**
 * What capture is *actually* doing right now, readable by the status surface.
 *
 * **Why this exists**: the v0 status surface passed `isCapturing = true` unconditionally, so a
 * capture that had halted still read "Capturing" — precisely the silent failure constitution IV
 * forbids, in the one screen whose entire job is to report it. On a real device this hid a
 * halted capture behind a confident "Capturing" for as long as the user cared to watch.
 *
 * Deliberately a process-wide holder rather than a Room table: the service and the status screen
 * are in the same process, and this is live state, not a record — persisting it would invite the
 * far worse bug of a stale row outliving the process that wrote it and claiming capture is
 * running when nothing is. Liveness that must survive a process death is the heartbeat's job
 * (`FileHeartbeatStore`, AC-65), and this is not a substitute for it.
 */
public object CaptureState {

    public sealed interface State {
        public data object Idle : State
        public data object Capturing : State

        /** Capture stopped and will not resume on its own. [reason] is shown to the user verbatim. */
        public data class Failed(val reason: String) : State

        /** Capture is interrupted and retrying (FR-RUN-11); a gap is open. */
        public data class Interrupted(val cause: String) : State
    }

    @Volatile
    public var state: State = State.Idle
        private set

    /** The session the current [state] belongs to, so a stale state from an older session is visible as such. */
    @Volatile
    public var sessionId: String? = null
        private set

    public fun capturing(sessionId: String) {
        this.sessionId = sessionId
        state = State.Capturing
    }

    public fun failed(reason: String) {
        state = State.Failed(reason)
    }

    public fun interrupted(cause: String) {
        state = State.Interrupted(cause)
    }

    /**
     * audit F-022: [clearSession] is `true` only for a deliberate, clean stop (`ACTION_STOP`) --
     * that is the one case where a relaunching `MainActivity` must not mistake this session for
     * one still running. An unclean stop (process death, `onDestroy` without a prior
     * `ACTION_STOP`) leaves [sessionId] as-is: the last known session stays visible on a
     * [State.Idle]/[State.Failed] read, and in-process callers only ever treat a session as live
     * when [isCapturing] is also true, so this cannot resurrect a dead session.
     */
    public fun idle(clearSession: Boolean = false) {
        state = State.Idle
        if (clearSession) sessionId = null
    }

    /** True only while capture is genuinely running — never optimistic. */
    public val isCapturing: Boolean get() = state is State.Capturing

    /** A short, user-facing reason when capture is not running, or `null` when it is. */
    public val failureReason: String? get() = when (val s = state) {
        is State.Failed -> s.reason
        is State.Interrupted -> "interrupted: ${s.cause}"
        State.Capturing, State.Idle -> null
    }
}
