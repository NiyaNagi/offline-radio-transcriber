package org.ort.pipeline.capture

import org.ort.capture.android.AudioDeviceDescriptor

/**
 * The selected audio input's live open/route state, readable by the status surface — the same
 * process-wide holder pattern [ShedStatus]/[ThermalStatus]/[RigStatus] use. Before this existed,
 * register R-113 found that `RealCaptureService` already knew the selected device, whether its
 * route was verified, the native rate and the resampler identity (FR-CAP-2a) at open time, but
 * never republished any of it — the Input row on `Capture-Status` read `not measured` and setup's
 * `Setup-Verify`/`Setup-Route-Mismatch` boards had no signal to render.
 *
 * **Never claims a route is verified before the OS has actually confirmed it.** `RealCaptureService`
 * publishes [opened] with `routeVerified = false` at the moment the device is selected (nothing has
 * been read yet), then republishes it with `routeVerified = true` only once `AudioRecordSource`'s
 * own first-read verification has actually succeeded — see that class's kdoc. A route that turns
 * out not to match is never silently substituted (constitution IV): [mismatch] is published
 * instead, and capture itself halts (see `RealCaptureService`'s own report on where that halt
 * already happens).
 *
 * This object carries no arithmetic and no Android dependency itself, beyond the plain
 * [AudioDeviceDescriptor] value type `:pipeline` already depends on through `:capture-android`.
 */
public object InputStatus {

    public sealed interface State {
        /** No input has been selected yet this session. */
        public data object None : State

        public data class Opened(
            public val descriptor: AudioDeviceDescriptor,
            public val nativeRateHz: Int,
            public val resamplerId: String,
            public val routeVerified: Boolean,
            public val routedDeviceMatches: Boolean,
            public val openedAtMillis: Long,
        ) : State

        /**
         * The device stopped delivering audio at [sinceMillis]; [lastKnown] is what was open
         * before. [attempt]/[ofTotal]/[nextRetryInMillis] (F23) are
         * [org.ort.pipeline.ReconnectLadderPosition]'s fields, computed from the real
         * `org.ort.capture.android.BackoffLadder` this route retries on — trailing, defaulted
         * `null` so every pre-existing caller of [lost] keeps compiling. `null` on all three means
         * "not computed for this loss", not "no retry is happening" — see [lost]'s own kdoc for
         * why only the *first* attempt's numbers are ever published here.
         */
        public data class Lost(
            public val lastKnown: Opened,
            public val sinceMillis: Long,
            public val attempt: Int? = null,
            public val ofTotal: Int? = null,
            public val nextRetryInMillis: Long? = null,
        ) : State

        /** The OS routed audio to a device other than [expected] — capture does not continue on it. */
        public data class Mismatch(
            public val expected: AudioDeviceDescriptor,
            public val actual: AudioDeviceDescriptor?,
        ) : State
    }

    @Volatile
    public var state: State = State.None
        private set

    public fun opened(
        descriptor: AudioDeviceDescriptor,
        nativeRateHz: Int,
        resamplerId: String,
        routeVerified: Boolean,
        routedDeviceMatches: Boolean,
        openedAtMillis: Long,
    ) {
        state = State.Opened(descriptor, nativeRateHz, resamplerId, routeVerified, routedDeviceMatches, openedAtMillis)
    }

    public fun mismatch(expected: AudioDeviceDescriptor, actual: AudioDeviceDescriptor?) {
        state = State.Mismatch(expected, actual)
    }

    /**
     * Only transitions when there is a real [State.Opened] to carry forward as [State.Lost.lastKnown]
     * — a loss with nothing previously open would have nothing honest to report as "last known", and
     * in practice never happens ([opened] always publishes before capture can produce any audio to
     * lose). Left unchanged rather than inventing a placeholder descriptor (constitution I).
     *
     * F23 (WPC3): [attempt]/[ofTotal]/[nextRetryInMillis] name the retry ladder position at the
     * moment the loss is first published. `RealCaptureService` (the only real caller) passes the
     * ladder's own first-attempt numbers, computed via [org.ort.pipeline.reconnectLadderPositionAt]
     * over `org.ort.capture.android.BackoffLadder.delayMillisFor` — the real function
     * [org.ort.capture.android.AudioRecordSource] itself retries on. `AudioRecordSource`'s own
     * retry loop is a private, unobservable suspend function with no per-attempt signal exposed
     * past its first (`:capture-android`/`:capture-api` are out of this package's file ownership —
     * see this package's report): these three fields are therefore a one-time, honest snapshot of
     * "the first retry's own numbers", not a live countdown that advances with each further
     * attempt during a long outage. `null` for a caller that does not have ladder facts to publish.
     */
    public fun lost(sinceMillis: Long, attempt: Int? = null, ofTotal: Int? = null, nextRetryInMillis: Long? = null) {
        val previous = state
        if (previous is State.Opened) {
            state = State.Lost(previous, sinceMillis, attempt, ofTotal, nextRetryInMillis)
        }
    }

    public fun reset() {
        state = State.None
    }
}
