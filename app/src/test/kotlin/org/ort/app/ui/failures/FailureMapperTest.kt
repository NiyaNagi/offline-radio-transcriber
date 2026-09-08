package org.ort.app.ui.failures

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioDeviceKind
import org.ort.data.entity.CaptureGapCause
import org.ort.data.entity.CaptureGapEntity
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus
import org.ort.testing.Requirement

/**
 * WP11b, register R-100/R-101: [FailureMapper.map] is pure — no Android dependency, no Robolectric
 * needed — so every priority rule this package's brief asks for ("at most one failure to show")
 * is asserted directly against plain `:pipeline`/`:data` values.
 */
class FailureMapperTest {

    private val usbDevice = AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "USB Audio Device")
    private val builtInMic = AudioDeviceDescriptor("mic-0", AudioDeviceKind.BUILT_IN_MIC, "Built-in microphone")

    private fun gap(startedAt: Long, endedAt: Long?, cause: CaptureGapCause) =
        CaptureGapEntity("gap1", "s1", startedAt, endedAt, cause, recoveredAutomatically = true)

    @Suppress("LongParameterList")
    private fun signals(
        captureState: CaptureState.State = CaptureState.State.Capturing,
        inputStatus: InputStatus.State = InputStatus.State.None,
        levelStatus: LevelStatus.State = LevelStatus.State.NotMeasured,
        thermalStatus: ThermalStatus.State = ThermalStatus.State.Nominal(0, null),
        rigStatus: RigStatus.State = RigStatus.State.Absent,
        storageForecast: StorageForecast.State = StorageForecast.State.NotYetMeasured(0L, 0L),
        shedLevel: Int = 0,
        shedBacklog: Int = 0,
        newestGap: CaptureGapEntity? = null,
        nowMillis: Long = 1_000_000L,
        debugOverride: FailurePresentation? = null,
        sessionStartedAtMillis: Long? = null,
        sessionTransmissionCount: Int = 0,
    ) = FailureSignals(
        captureState, inputStatus, levelStatus, thermalStatus, rigStatus, storageForecast,
        shedLevel, shedBacklog, newestGap, nowMillis, debugOverride,
        sessionStartedAtMillis, sessionTransmissionCount,
    )

    @Test
    @Requirement("R-101")
    fun `R_101 an input mismatch is a takeover, F1, no matter what else is true`() {
        val presentation = FailureMapper.map(
            signals(
                inputStatus = InputStatus.State.Mismatch(usbDevice, builtInMic),
                shedLevel = 3, // would otherwise read as a thermal/backlog banner
            ),
        )
        assertTrue(presentation is FailurePresentation.Route)
        presentation as FailurePresentation.Route
        assertEquals("USB Audio Device", presentation.state.expectedLabel)
        assertEquals("Built-in microphone", presentation.state.actualLabel)
    }

    @Test
    @Requirement("R-126")
    fun `R_126 a route mismatch computes the session's elapsed time and overs kept`() {
        val presentation = FailureMapper.map(
            signals(
                inputStatus = InputStatus.State.Mismatch(usbDevice, builtInMic),
                nowMillis = 1_000_000L + (3 * 3_600_000L + 9 * 60_000L + 40_000L),
                sessionStartedAtMillis = 1_000_000L,
                sessionTransmissionCount = 188,
            ),
        )
        assertTrue(presentation is FailurePresentation.Route)
        presentation as FailurePresentation.Route
        assertEquals("3:09:40", presentation.state.elapsedLabel)
        assertEquals(188, presentation.state.oversKeptCount)
        assertTrue(presentation.state.sessionElapsedKnown)
    }

    @Test
    @Requirement("R-126")
    fun `R_126 a route mismatch with no session to measure against reports elapsed as unknown, never invented`() {
        val presentation = FailureMapper.map(
            signals(
                inputStatus = InputStatus.State.Mismatch(usbDevice, builtInMic),
                sessionStartedAtMillis = null,
            ),
        )
        assertTrue(presentation is FailurePresentation.Route)
        presentation as FailurePresentation.Route
        assertTrue(!presentation.state.sessionElapsedKnown)
    }

    @Test
    @Requirement("R-100")
    fun `R_100 storage at the floor with capture failed is the storage takeover, not a banner`() {
        val presentation = FailureMapper.map(
            signals(
                captureState = CaptureState.State.Failed("storage exhausted"),
                storageForecast = StorageForecast.State.AtFloor(50_000_000L, 900_000_000L),
            ),
        )
        assertTrue(presentation is FailurePresentation.StorageHalt)
    }

    @Test
    @Requirement("R-100")
    fun `R_100 storage at the floor without a Failed capture state is not shown as a takeover`() {
        // AtFloor alone (a stale read racing the real stop) must not claim capture halted.
        val presentation = FailureMapper.map(
            signals(storageForecast = StorageForecast.State.AtFloor(50_000_000L, 900_000_000L)),
        )
        assertTrue(presentation !is FailurePresentation.StorageHalt)
    }

    @Test
    @Requirement("R-100")
    fun `R_100 an input lost is the disconnect banner`() {
        val opened = InputStatus.State.Opened(usbDevice, 48_000, "id", true, true, 0L)
        val presentation = FailureMapper.map(
            signals(inputStatus = InputStatus.State.Lost(opened, sinceMillis = 900_000L)),
        )
        assertTrue(presentation is FailurePresentation.Disconnect)
        assertEquals("USB Audio Device", (presentation as FailurePresentation.Disconnect).state.deviceLabel)
    }

    @Test
    @Requirement("R-112")
    fun `R_112 a quiet level below the threshold is the level banner`() {
        val presentation = FailureMapper.map(
            signals(
                levelStatus = LevelStatus.State.Measured(-34f, -40f, -58f, clipped = false, 0, 48_000, 900_000L),
            ),
        )
        assertTrue(presentation is FailurePresentation.Level)
        assertEquals(LevelProblem.QUIET, (presentation as FailurePresentation.Level).state.problem)
    }

    @Test
    @Requirement("R-112")
    fun `R_112 a level within the band is not shown as a failure`() {
        val presentation = FailureMapper.map(
            signals(
                levelStatus = LevelStatus.State.Measured(-14f, -18f, -58f, clipped = false, 0, 48_000, 900_000L),
            ),
        )
        assertTrue(presentation is FailurePresentation.None)
    }

    @Test
    @Requirement("R-112")
    fun `R_112 clipping outranks a merely-quiet reading`() {
        val presentation = FailureMapper.map(
            signals(
                levelStatus = LevelStatus.State.Measured(0f, -6f, -55f, clipped = true, 12, 48_000, 900_000L),
            ),
        )
        assertTrue(presentation is FailurePresentation.Level)
        assertEquals(LevelProblem.CLIPPING, (presentation as FailurePresentation.Level).state.problem)
    }

    @Test
    @Requirement("F-005")
    fun `F5 a recently-closed OS_STOPPED gap is the killed banner`() {
        val theGap = gap(940_000L, 950_000L, CaptureGapCause.OS_STOPPED)
        val presentation = FailureMapper.map(signals(newestGap = theGap, nowMillis = 960_000L))
        assertTrue(presentation is FailurePresentation.Killed)
    }

    @Test
    @Requirement("F-005")
    fun `F5 an old OS_STOPPED gap outside the recent window is not shown`() {
        val theGap = gap(0L, 1_000L, CaptureGapCause.OS_STOPPED)
        val presentation = FailureMapper.map(signals(newestGap = theGap, nowMillis = 999_999_999L))
        assertTrue(presentation is FailurePresentation.None)
    }

    @Test
    @Requirement("R-105")
    fun `R_105 one night left is the storage warning banner`() {
        val presentation = FailureMapper.map(
            signals(storageForecast = StorageForecast.State.OneNightLeft(2_000_000_000L, 900_000_000L, 0.8)),
        )
        assertTrue(presentation is FailurePresentation.StorageWarning)
        assertEquals("1", (presentation as FailurePresentation.StorageWarning).state.nightsLeftLabel)
    }

    @Test
    @Requirement("R-104")
    fun `F7 a warm thermal state is the thermal banner, computed tier from shed level`() {
        val presentation = FailureMapper.map(
            signals(thermalStatus = ThermalStatus.State.Warm(2, 0.9), shedLevel = 1),
        )
        assertTrue(presentation is FailurePresentation.Thermal)
        assertEquals(2, (presentation as FailurePresentation.Thermal).state.tier)
    }

    @Test
    @Requirement("F-008")
    fun `F8 a growing backlog is the backlog banner, when thermal is nominal`() {
        val presentation = FailureMapper.map(signals(shedBacklog = 41))
        assertTrue(presentation is FailurePresentation.Backlog)
        assertEquals(41, (presentation as FailurePresentation.Backlog).state.waitingCount)
    }

    @Test
    @Requirement("F-008")
    fun `F8 a small backlog under the threshold is not shown`() {
        val presentation = FailureMapper.map(signals(shedBacklog = 2))
        assertTrue(presentation is FailurePresentation.None)
    }

    @Test
    @Requirement("F-007")
    fun `F7 thermal outranks backlog when both are true, matching the thermal scenario`() {
        val presentation = FailureMapper.map(
            signals(thermalStatus = ThermalStatus.State.Warm(2, 0.9), shedLevel = 3, shedBacklog = 112),
        )
        assertTrue(presentation is FailurePresentation.Thermal)
    }

    @Test
    @Requirement("F-009")
    fun `F9 a stale rig is the rig banner`() {
        val connected = RigStatus.State.Connected("TH-D75A", emptyList())
        val presentation = FailureMapper.map(
            signals(rigStatus = RigStatus.State.Stale(connected, sinceMillis = 900_000L)),
        )
        assertTrue(presentation is FailurePresentation.Rig)
        assertEquals("TH-D75A", (presentation as FailurePresentation.Rig).state.deviceLabel)
    }

    @Test
    @Requirement("F-015")
    fun `F15 a recently-closed CALL gap while capturing is the call banner`() {
        val theGap = gap(940_000L, 950_000L, CaptureGapCause.CALL)
        val presentation = FailureMapper.map(signals(newestGap = theGap, nowMillis = 960_000L))
        assertTrue(presentation is FailurePresentation.Call)
    }

    @Test
    @Requirement("F-015")
    fun `F15 a CALL gap is not shown when capture is not live`() {
        val theGap = gap(940_000L, 950_000L, CaptureGapCause.CALL)
        val presentation = FailureMapper.map(
            signals(captureState = CaptureState.State.Idle, newestGap = theGap, nowMillis = 960_000L),
        )
        assertTrue(presentation is FailurePresentation.None)
    }

    @Test
    @Requirement("R-100")
    fun `R_100 nothing wrong maps to None`() {
        assertTrue(FailureMapper.map(signals()) is FailurePresentation.None)
    }

    @Test
    @Requirement("R-100")
    fun `R_100 a debug override wins outright, over every real signal`() {
        val override = FailurePresentation.Clock(ClockViewState("PDT → PST", "8 h 30 m", "23:10", "06:40"))
        val presentation = FailureMapper.map(
            signals(inputStatus = InputStatus.State.Mismatch(usbDevice, builtInMic), debugOverride = override),
        )
        assertEquals(override, presentation)
    }
}
