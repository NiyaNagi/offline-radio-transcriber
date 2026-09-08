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

        /** The device stopped delivering audio at [sinceMillis]; [lastKnown] is what was open before. */
        public data class Lost(public val lastKnown: Opened, public val sinceMillis: Long) : State

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
     */
    public fun lost(sinceMillis: Long) {
        val previous = state
        if (previous is State.Opened) state = State.Lost(previous, sinceMillis)
    }

    public fun reset() {
        state = State.None
    }
}
