package org.ort.pipeline.capture

/**
 * Whether real ASR is actually running this session, readable by the status surface — the same
 * pattern [CaptureState] uses, for the same reason (constitution I/IV: never let a screen read as
 * working when it isn't). Capture succeeding and ASR being available are independent facts: a
 * device can capture perfectly while carrying no installed model at all (build-plan P12's model-
 * fetch requirement — "do NOT let it silently look like transcription is working when no model is
 * present"), and this is how that state reaches anything that displays it.
 *
 * Deliberately a process-wide holder, not persisted, for the same reason as [CaptureState]: this
 * is live state about the current process's engine, not a record.
 */
public object AsrAvailability {

    public sealed interface State {
        public data object NotYetChecked : State
        public data class Available(val modelRef: String) : State
        public data class Unavailable(val reason: String) : State
    }

    @Volatile
    public var state: State = State.NotYetChecked
        private set

    public fun available(modelRef: String) {
        state = State.Available(modelRef)
    }

    public fun unavailable(reason: String) {
        state = State.Unavailable(reason)
    }

    public fun reset() {
        state = State.NotYetChecked
    }

    public val isAvailable: Boolean get() = state is State.Available

    /** A short, user-facing label — never silently optimistic (constitution I). */
    public val statusLabel: String
        get() = when (val s = state) {
            State.NotYetChecked -> "ASR: not yet checked"
            is State.Available -> "ASR: ${s.modelRef}"
            is State.Unavailable -> "ASR unavailable: ${s.reason}"
        }
}
