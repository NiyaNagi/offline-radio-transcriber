package org.ort.app.ui.setup

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioDeviceKind
import org.ort.capture.android.fake.FakeAudioIo
import org.ort.pipeline.capture.InputStatus
import org.ort.testing.Requirement

/**
 * R-081 (ui-conformance-plan WP9): [RealRouteCheck] runs the exact same
 * [org.ort.capture.android.RouteVerifier]/`getRoutedDevice()` capture itself checks (FR-CAP-2a,
 * FR-CAP-3 → AC-2, AC-97, AC-98), driven here against
 * [org.ort.capture.android.fake.FakeAudioIo] (constitution II — no second, drifting fake).
 */
class RouteCheckTest {

    private val usb = AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "USB Audio Device")
    private val builtIn = AudioDeviceDescriptor("mic-0", AudioDeviceKind.BUILT_IN_MIC, "Built-in microphone")

    private fun loudFrame(n: Int = 1_600): ShortArray = ShortArray(n) { 20_000 }

    // InputStatus is a process-wide singleton (like CaptureState/RigStatus elsewhere in this
    // suite) -- reset on both sides so no other test's InputStatus.opened(...) leaks in here, and
    // nothing this class sets leaks out.
    @BeforeEach
    fun resetInputStatusBefore() = InputStatus.reset()

    @AfterEach
    fun resetInputStatusAfter() = InputStatus.reset()

    @Test
    @Requirement("AC-2", "FR-CAP-3")
    fun `R_081 a matching route with signal reaches Passed with every stage checked off`() = runTest {
        val io = FakeAudioIo(deviceSampleRate = 16_000)
        io.forceRoutedDevice(usb)
        io.enqueueFrames(loudFrame())

        val states = RealRouteCheck(pollIntervalMillis = 10L).run(io, usb).toList()

        val last = states.last()
        assertTrue(last is RouteCheckState.Passed, "expected Passed, got $last")
        last as RouteCheckState.Passed
        assertEquals(16_000, last.nativeRateHz)
        assertNull(last.resamplerDescription, "a native 16kHz device resamples nothing")
        assertTrue(
            states.filterIsInstance<RouteCheckState.InProgress>().any { RouteCheckStage.ROUTE_MATCH in it.passed },
            "route-match stage must have been reported before Passed",
        )
    }

    @Test
    @Requirement("AC-97", "FR-CAP-2a")
    fun `R_081 a 48kHz device records the resampler identity used to reach 16kHz`() = runTest {
        val io = FakeAudioIo(deviceSampleRate = 48_000)
        io.forceRoutedDevice(usb)
        io.enqueueFrames(loudFrame())

        val states = RealRouteCheck(pollIntervalMillis = 10L).run(io, usb).toList()

        val passed = states.last() as RouteCheckState.Passed
        assertEquals(48_000, passed.nativeRateHz)
        assertTrue(passed.resamplerDescription != null, "a rate-changing device must record that it resampled")
    }

    @Test
    @Requirement("AC-2", "FR-CAP-3")
    fun `R_081 a route the OS silently sent elsewhere reports Mismatch, never Passed`() = runTest {
        val io = FakeAudioIo(deviceSampleRate = 16_000)
        io.forceRoutedDevice(builtIn) // operator selected USB; OS silently routed the built-in mic
        io.enqueueFrames(loudFrame())

        val states = RealRouteCheck(pollIntervalMillis = 10L).run(io, usb).toList()

        val mismatch = states.last() as RouteCheckState.Mismatch
        assertEquals(usb, mismatch.selected)
        assertEquals(builtIn, mismatch.routed)
        assertTrue(states.none { it is RouteCheckState.Passed })
        assertTrue(
            states.dropLast(1).all { it is RouteCheckState.InProgress },
            "only in-progress states may precede the mismatch",
        )
    }

    @Test
    fun `R_081 a device that will not open reports OpenFailed`() = runTest {
        val io = FakeAudioIo(deviceSampleRate = 16_000)
        io.openSucceeds = false

        val states = RealRouteCheck().run(io, usb).toList()

        assertTrue(states.single() is RouteCheckState.OpenFailed)
    }

    @Test
    fun `R_081 no signal within the listening window times out rather than hanging or passing`() = runTest {
        val io = FakeAudioIo(deviceSampleRate = 16_000)
        io.forceRoutedDevice(usb) // route matches; nothing is ever heard

        val states = RealRouteCheck(listenTimeoutMillis = 500L, pollIntervalMillis = 100L).run(io, usb).toList()

        assertEquals(RouteCheckState.TimedOut, states.last())
        assertTrue(states.none { it is RouteCheckState.Passed })
    }

    @Test
    fun `R_081 no device routed at all reports Mismatch with a null routed descriptor`() = runTest {
        val io = FakeAudioIo(deviceSampleRate = 16_000) // never forced -- routedDevice() stays null

        val states = RealRouteCheck().run(io, usb).toList()

        val mismatch = states.last() as RouteCheckState.Mismatch
        assertNull(mismatch.routed)
    }

    @Test
    @Requirement("AC-97", "FR-CAP-2a")
    fun `R_081 a live InputStatus for the same device supplies the pipeline's own resampler string`() = runTest {
        InputStatus.opened(
            descriptor = usb,
            nativeRateHz = 48_000,
            resamplerId = "polyphase/v1 48000->16000 (L=1 M=3 taps=64 a1b2c3d4e5f6)",
            routeVerified = true,
            routedDeviceMatches = true,
            openedAtMillis = 0L,
        )
        val io = FakeAudioIo(deviceSampleRate = 48_000)
        io.forceRoutedDevice(usb)
        io.enqueueFrames(loudFrame())

        val states = RealRouteCheck(pollIntervalMillis = 10L).run(io, usb).toList()

        val passed = states.last() as RouteCheckState.Passed
        assertEquals(
            "polyphase/v1 48000->16000 (L=1 M=3 taps=64 a1b2c3d4e5f6)",
            passed.resamplerDescription,
            "a live capture session already has this device open -- use its real identity string",
        )
    }

    /** R-224 (validator pass 2, register R-220..R-227): before this, the third check always ran
     * its own real, up-to-30s listening loop even when [InputStatus] already reported this exact
     * device open and route-verified -- against the validator's `input-verified` scenario (a live
     * capture session already has the device open, so nothing else is feeding this check's own raw
     * read loop) that always timed out. No frames are enqueued here at all, and the listen timeout
     * is effectively zero -- if the short-circuit were not wired in, this would time out exactly
     * like the validator's own screenshot, not reach [RouteCheckState.Passed]. */
    @Test
    fun `R_224 a route-verified InputStatus skips the raw signal probe entirely, never timing out`() = runTest {
        InputStatus.opened(
            descriptor = usb,
            nativeRateHz = 48_000,
            resamplerId = "polyphase/v1 48000->16000 (L=1 M=3 taps=64 a1b2c3d4e5f6)",
            routeVerified = true,
            routedDeviceMatches = true,
            openedAtMillis = 0L,
        )
        val io = FakeAudioIo(deviceSampleRate = 48_000)
        io.forceRoutedDevice(usb)
        // Deliberately no enqueued frames and a near-zero timeout -- the raw loop would time out
        // immediately if the short-circuit did not fire first.

        val states = RealRouteCheck(listenTimeoutMillis = 1L, pollIntervalMillis = 1L).run(io, usb).toList()

        assertTrue(states.none { it is RouteCheckState.TimedOut }, "must never reach the raw probe at all")
        val passed = states.last() as RouteCheckState.Passed
        assertEquals(
            "polyphase/v1 48000->16000 (L=1 M=3 taps=64 a1b2c3d4e5f6)",
            passed.resamplerDescription,
        )
    }

    /** A device [InputStatus] reports open but not yet route-verified (the moment right after
     * selection, before `AudioRecordSource`'s own first real-read check) must still run the real
     * probe -- `routeVerified` alone gates the short-circuit, not merely a matching device id. */
    @Test
    fun `R_224 an InputStatus that is open but not yet route-verified still runs the real probe`() = runTest {
        InputStatus.opened(
            descriptor = usb,
            nativeRateHz = 48_000,
            resamplerId = "polyphase/v1 48000->16000 (L=1 M=3 taps=64 a1b2c3d4e5f6)",
            routeVerified = false,
            routedDeviceMatches = true,
            openedAtMillis = 0L,
        )
        val io = FakeAudioIo(deviceSampleRate = 48_000)
        io.forceRoutedDevice(usb) // route matches; nothing is ever heard

        val states = RealRouteCheck(listenTimeoutMillis = 200L, pollIntervalMillis = 50L).run(io, usb).toList()

        assertEquals(RouteCheckState.TimedOut, states.last(), "an unverified InputStatus must not be borrowed")
    }

    @Test
    fun `R_081 a live InputStatus for a different device is never borrowed for this one`() = runTest {
        val otherDevice = AudioDeviceDescriptor("usb-2", AudioDeviceKind.USB_DEVICE, "A different adapter")
        InputStatus.opened(
            descriptor = otherDevice,
            nativeRateHz = 44_100,
            resamplerId = "this identity belongs to usb-2, not usb-1",
            routeVerified = true,
            routedDeviceMatches = true,
            openedAtMillis = 0L,
        )
        val io = FakeAudioIo(deviceSampleRate = 48_000)
        io.forceRoutedDevice(usb)
        io.enqueueFrames(loudFrame())

        val states = RealRouteCheck(pollIntervalMillis = 10L).run(io, usb).toList()

        val passed = states.last() as RouteCheckState.Passed
        assertTrue(
            passed.resamplerDescription?.contains("usb-2") != true,
            "must never attribute a different device's InputStatus to this one, got ${passed.resamplerDescription}",
        )
    }
}
