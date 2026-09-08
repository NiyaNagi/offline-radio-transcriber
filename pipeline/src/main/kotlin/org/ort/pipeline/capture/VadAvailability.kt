package org.ort.pipeline.capture

/**
 * Whether the segmenter is running against the real Silero VAD or the RMS-energy stand-in this
 * session — the same pattern [AsrAvailability] uses (constitution I: never let a screen read as
 * more capable than it is). Defaults to the stand-in: a status surface that has not yet checked
 * must not read as if Silero is running (constitution IV).
 */
public object VadAvailability {

    public sealed interface State {
        public data object Stub : State
        public data object Real : State
        public data class StubWithReason(val reason: String) : State
    }

    @Volatile
    public var state: State = State.Stub
        private set

    public fun real() {
        state = State.Real
    }

    public fun stub(reason: String) {
        state = State.StubWithReason(reason)
    }

    public fun reset() {
        state = State.Stub
    }

    public val statusLabel: String
        get() = when (val s = state) {
            State.Stub -> "VAD: RMS-energy stand-in (not yet checked for Silero)"
            State.Real -> "VAD: Silero (real)"
            is State.StubWithReason -> "VAD: RMS-energy stand-in (${s.reason})"
        }
}
