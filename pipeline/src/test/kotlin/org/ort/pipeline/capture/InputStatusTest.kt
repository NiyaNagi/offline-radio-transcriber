package org.ort.pipeline.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioDeviceKind
import org.ort.testing.Requirement

/**
 * register R-113: before this existed, the selected input device, its route-verified state, the
 * native rate and the resampler identity were known inside `RealCaptureService` at open time but
 * never republished anywhere the status surface or setup sequence could read them.
 */
class InputStatusTest {

    private val usb = AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "USB Audio Device")
    private val builtIn = AudioDeviceDescriptor("mic-0", AudioDeviceKind.BUILT_IN_MIC, "Built-in microphone")

    @BeforeEach
    fun reset() {
        InputStatus.reset()
    }

    private fun openUsb(routeVerified: Boolean, routedDeviceMatches: Boolean) {
        InputStatus.opened(
            descriptor = usb,
            nativeRateHz = 48_000,
            resamplerId = "polyphase/v1 48000->16000",
            routeVerified = routeVerified,
            routedDeviceMatches = routedDeviceMatches,
            openedAtMillis = 1_000L,
        )
    }

    @Test
    @Requirement("R-113")
    fun `before anything is selected, the input is reported None`() {
        assertEquals(InputStatus.State.None, InputStatus.state)
    }

    @Test
    @Requirement("R-113", "FR-CAP-2a")
    fun `R_113_input_status_is_published_at_open_with_the_resampler_identity`() {
        InputStatus.opened(
            descriptor = usb,
            nativeRateHz = 48_000,
            resamplerId = "polyphase/v1 48000->16000",
            routeVerified = true,
            routedDeviceMatches = true,
            openedAtMillis = 1_000L,
        )

        val state = InputStatus.state
        assertTrue(state is InputStatus.State.Opened)
        state as InputStatus.State.Opened
        assertEquals(usb, state.descriptor)
        assertEquals(48_000, state.nativeRateHz)
        assertEquals("polyphase/v1 48000->16000", state.resamplerId)
        assertTrue(state.routeVerified)
        assertTrue(state.routedDeviceMatches)
        assertEquals(1_000L, state.openedAtMillis)
    }

    @Test
    @Requirement("R-113", "FR-CAP-3")
    fun `R_113_route_mismatch_publishes_Mismatch`() {
        openUsb(routeVerified = false, routedDeviceMatches = false)

        InputStatus.mismatch(expected = usb, actual = builtIn)

        val state = InputStatus.state
        assertTrue(state is InputStatus.State.Mismatch)
        state as InputStatus.State.Mismatch
        assertEquals(usb, state.expected)
        assertEquals(builtIn, state.actual)
    }

    @Test
    @Requirement("R-113", "FR-CAP-3")
    fun `R_113_a_mismatch_with_nothing_routed_carries_a_null_actual`() {
        InputStatus.mismatch(expected = usb, actual = null)

        val state = InputStatus.state
        assertTrue(state is InputStatus.State.Mismatch)
        assertNull((state as InputStatus.State.Mismatch).actual)
    }

    @Test
    @Requirement("R-113", "FR-RUN-11")
    fun `R_113_device_loss_publishes_Lost_with_last_known`() {
        openUsb(routeVerified = true, routedDeviceMatches = true)
        val opened = InputStatus.state as InputStatus.State.Opened

        InputStatus.lost(sinceMillis = 5_000L)

        val state = InputStatus.state
        assertTrue(state is InputStatus.State.Lost)
        state as InputStatus.State.Lost
        assertEquals(opened, state.lastKnown)
        assertEquals(5_000L, state.sinceMillis)
    }

    @Test
    @Requirement("R-113")
    fun `R_113_a_loss_with_nothing_ever_opened_leaves_state_unchanged_rather_than_inventing_a_descriptor`() {
        InputStatus.lost(sinceMillis = 5_000L)

        assertEquals(InputStatus.State.None, InputStatus.state)
    }

    @Test
    @Requirement("R-113")
    fun `reset returns to None`() {
        openUsb(routeVerified = true, routedDeviceMatches = true)
        InputStatus.reset()

        assertEquals(InputStatus.State.None, InputStatus.state)
    }
}
