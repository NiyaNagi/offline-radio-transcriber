package org.ort.app.ui.failures

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioDeviceKind
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus
import org.ort.testing.Requirement

/**
 * R-103, `Flow-Degrade.dc.html`'s own closing note: recovery is announced too. [RecoveryAnnouncer.diff]
 * is tested the same way [FailureMapper.map] is — a pure function of two consecutive [FailureSignals],
 * fed sequences the way Turbine would feed a Flow (this package's brief: "tested with Turbine-style
 * sequences" — no Android/Flow dependency needed for a function this shape, so plain sequential
 * calls prove the same thing Turbine would).
 */
class RecoveryAnnouncerTest {

    private val usbDevice = AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "USB Audio Device")

    @Suppress("LongParameterList")
    private fun signals(
        captureState: CaptureState.State = CaptureState.State.Capturing,
        inputStatus: InputStatus.State = InputStatus.State.None,
        thermalStatus: ThermalStatus.State = ThermalStatus.State.Nominal(0, null),
        rigStatus: RigStatus.State = RigStatus.State.Absent,
        storageForecast: StorageForecast.State = StorageForecast.State.NotYetMeasured(0L, 0L),
        shedLevel: Int = 0,
    ) = FailureSignals(
        captureState, inputStatus, LevelStatus.State.NotMeasured, thermalStatus, rigStatus, storageForecast,
        shedLevel, 0, null, 0L, null,
    )

    @Test
    @Requirement("R-103")
    fun `R_103 a tier recovery from a degraded shed level announces Back to tier 3`() {
        val toasts = RecoveryAnnouncer.diff(previous = signals(shedLevel = 3), current = signals(shedLevel = 0))
        assertTrue(toasts.any { it.id == "tier" && it.message == "Back to tier 3" })
    }

    @Test
    @Requirement("R-103")
    fun `R_103 a tier recovery states the improvable count when one is given`() {
        val toasts = RecoveryAnnouncer.diff(
            previous = signals(shedLevel = 3),
            current = signals(shedLevel = 0),
            improvableCount = 38,
        )
        assertTrue(toasts.any { it.message == "Back to tier 3 · 38 overs can be improved" })
    }

    @Test
    @Requirement("R-103")
    fun `R_103 a thermal recovery with the shed already at zero also announces tier restored`() {
        val toasts = RecoveryAnnouncer.diff(
            previous = signals(thermalStatus = ThermalStatus.State.Warm(2, 0.9)),
            current = signals(),
        )
        assertTrue(toasts.any { it.id == "tier" })
    }

    @Test
    @Requirement("R-103")
    fun `R_103 no toast when nothing changed`() {
        val steady = signals(shedLevel = 3)
        val toasts = RecoveryAnnouncer.diff(previous = steady, current = steady)
        assertTrue(toasts.isEmpty())
    }

    @Test
    @Requirement("R-103")
    fun `R_103 a rig reconnecting from stale announces Radio reconnected`() {
        val connected = RigStatus.State.Connected("TH-D75A", emptyList())
        val toasts = RecoveryAnnouncer.diff(
            previous = signals(rigStatus = RigStatus.State.Stale(connected, 0L)),
            current = signals(rigStatus = connected),
        )
        assertEquals(listOf(RecoveryToast("rig", "Radio reconnected")), toasts)
    }

    @Test
    @Requirement("R-103")
    fun `R_103 an input coming back from Lost announces Input back`() {
        val opened = InputStatus.State.Opened(usbDevice, 48_000, "id", true, true, 0L)
        val toasts = RecoveryAnnouncer.diff(
            previous = signals(inputStatus = InputStatus.State.Lost(opened, 0L)),
            current = signals(inputStatus = opened),
        )
        assertEquals(listOf(RecoveryToast("input", "Input back")), toasts)
    }

    @Test
    @Requirement("R-103")
    fun `R_103 an input recovering from a Mismatch also announces Input back`() {
        val actual = AudioDeviceDescriptor("mic-0", AudioDeviceKind.BUILT_IN_MIC, "Built-in microphone")
        val opened = InputStatus.State.Opened(usbDevice, 48_000, "id", true, true, 0L)
        val toasts = RecoveryAnnouncer.diff(
            previous = signals(inputStatus = InputStatus.State.Mismatch(usbDevice, actual)),
            current = signals(inputStatus = opened),
        )
        assertEquals(listOf(RecoveryToast("input", "Input back")), toasts)
    }

    @Test
    @Requirement("R-103")
    fun `R_103 storage recovering from OneNightLeft to Fine announces storage above the floor`() {
        val toasts = RecoveryAnnouncer.diff(
            previous = signals(storageForecast = StorageForecast.State.OneNightLeft(1L, 1L, 0.5)),
            current = signals(storageForecast = StorageForecast.State.Fine(9_000_000_000L, 1L, 9.0)),
        )
        assertEquals(listOf(RecoveryToast("storage", "Storage back above the floor")), toasts)
    }

    @Test
    @Requirement("R-103")
    fun `R_103 several recoveries at once each get their own toast`() {
        val connected = RigStatus.State.Connected("TH-D75A", emptyList())
        val toasts = RecoveryAnnouncer.diff(
            previous = signals(shedLevel = 3, rigStatus = RigStatus.State.Stale(connected, 0L)),
            current = signals(rigStatus = connected),
        )
        assertEquals(2, toasts.size)
        assertTrue(toasts.any { it.id == "tier" })
        assertTrue(toasts.any { it.id == "rig" })
    }
}
