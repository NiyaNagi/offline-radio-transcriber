package org.ort.app.ui.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.components.LiveBarTone
import org.ort.app.ui.failures.CallViewState
import org.ort.app.ui.failures.ClockViewState
import org.ort.app.ui.failures.DebugFailureOverride
import org.ort.app.ui.failures.FailurePresentation
import org.ort.app.ui.failures.InterruptedViewState
import org.ort.app.ui.failures.KilledViewState
import org.ort.app.ui.failures.StorageAudioPausedViewState
import org.ort.app.ui.failures.UsbViewState
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioDeviceKind
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.ShedStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * WP11b (register R-100): the live bar's failure-variant labels — `Flow-Degrade.dc.html`'s own
 * words for each, verbatim, matching `FailureMapper`'s priority order so the live bar and a
 * banner never disagree about which one thing is currently wrong.
 */
@RunWith(RobolectricTestRunner::class)
class LiveBarPollingTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun resetHolders() {
        CaptureState.idle(clearSession = true)
        ShedStatus.reset()
        ThermalStatus.reset()
        RigStatus.reset()
        StorageForecast.reset()
        LevelStatus.reset()
        InputStatus.reset()
        DebugFailureOverride.clear()
    }

    @Test
    @Requirement("R-100")
    fun `R_100 nominal capture reads Live`() = runTest {
        CaptureState.capturing("s1")
        val state = LiveBarPolling.current(context, null)
        assertEquals(LiveBarTone.NOMINAL, state.tone)
        assertEquals("Live", state.label)
    }

    @Test
    @Requirement("F-002")
    fun `F2 an input Lost reads Gap`() = runTest {
        CaptureState.capturing("s1")
        val usbDevice = AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "USB Audio Device")
        InputStatus.opened(usbDevice, 48_000, "id", true, true, 0L)
        InputStatus.lost(sinceMillis = 0L)
        val state = LiveBarPolling.current(context, null)
        assertEquals(LiveBarTone.DEGRADED, state.tone)
        assertEquals("Gap", state.label)
    }

    @Test
    @Requirement("R-112")
    fun `R_112 a quiet level reads Quiet`() = runTest {
        CaptureState.capturing("s1")
        LevelStatus.update(
            LevelStatus.State.Measured(-34f, -40f, -58f, clipped = false, 0, 48_000, 0L),
            peakHistoryDbfs = emptyList(),
        )
        val state = LiveBarPolling.current(context, null)
        assertEquals(LiveBarTone.DEGRADED, state.tone)
        assertEquals("Quiet", state.label)
    }

    @Test
    @Requirement("R-112")
    fun `R_112 a clipped level reads Hot`() = runTest {
        CaptureState.capturing("s1")
        LevelStatus.update(
            LevelStatus.State.Measured(0f, -6f, -55f, clipped = true, 12, 48_000, 0L),
            peakHistoryDbfs = emptyList(),
        )
        val state = LiveBarPolling.current(context, null)
        assertEquals("Hot", state.label)
    }

    @Test
    @Requirement("R-105")
    fun `R_105 a storage warning reads Low storage`() = runTest {
        CaptureState.capturing("s1")
        StorageForecast.set(StorageForecast.State.OneNightLeft(1L, 1L, 0.5))
        val state = LiveBarPolling.current(context, null)
        assertEquals("Low storage", state.label)
    }

    @Test
    @Requirement("F-008")
    fun `F8 a growing backlog reads N behind`() = runTest {
        CaptureState.capturing("s1")
        ShedStatus.update(level = 0, backlog = 41)
        val state = LiveBarPolling.current(context, null)
        assertEquals("41 behind", state.label)
    }

    @Test
    @Requirement("F-009")
    fun `F9 a stale rig reads Rig lost`() = runTest {
        CaptureState.capturing("s1")
        RigStatus.stale(RigStatus.State.Connected("TH-D75A", emptyList()), sinceMillis = 0L)
        val state = LiveBarPolling.current(context, null)
        assertEquals("Rig lost", state.label)
    }

    @Test
    @Requirement("R-149")
    fun `R_149 a thermal-caused tier drop still outranks a stale rig`() = runTest {
        CaptureState.capturing("s1")
        ShedStatus.update(level = 1, backlog = 0)
        ThermalStatus.update(osThermalStatus = ThermalStatus.THERMAL_STATUS_MODERATE, realTimeFactor = 0.7)
        RigStatus.stale(RigStatus.State.Connected("TH-D75A", emptyList()), sinceMillis = 0L)
        val state = LiveBarPolling.current(context, null)
        assertEquals("Tier 2", state.label)
    }

    @Test
    @Requirement("R-149")
    fun `R_149 a bare tier drop with no thermal reason falls through to the next real signal, never Tier N`() =
        runTest {
            CaptureState.capturing("s1")
            ShedStatus.update(level = 1, backlog = 0)
            RigStatus.stale(RigStatus.State.Connected("TH-D75A", emptyList()), sinceMillis = 0L)
            val state = LiveBarPolling.current(context, null)
            assertEquals("Rig lost", state.label)
        }

    @Test
    @Requirement("R-149")
    fun `R_149 F8_backlog reads N behind, not Tier 0, when the shed level is also raised`() = runTest {
        CaptureState.capturing("s1")
        ShedStatus.update(level = 3, backlog = 112)
        val state = LiveBarPolling.current(context, null)
        assertEquals("112 behind", state.label)
    }

    @Test
    @Requirement("R-022")
    fun `R_022_live_bar_level_bars_follow_LevelStatus`() = runTest {
        CaptureState.capturing("s1")
        LevelStatus.update(
            // -60 dBFS is the chart floor (fraction 0), -30 dBFS is exactly its midpoint (fraction
            // 0.5) — round numbers chosen so the normalised fraction is exact, not just close.
            LevelStatus.State.Measured(
                peakDbfs = -30f,
                rmsDbfs = -60f,
                noiseFloorDbfs = -58f,
                clipped = false,
                clipCountLastSecond = 0,
                sampleRateHz = 16_000,
                updatedAtMillis = 0L,
            ),
            peakHistoryDbfs = emptyList(),
        )

        val state = LiveBarPolling.current(context, null)

        // rms, peak, rms, peak — never a fabricated 4-band spectrum from one meter tick's two real
        // numbers (see LiveBarPolling.levelBars()'s own kdoc).
        assertEquals(listOf(0f, 0.5f, 0f, 0.5f), state.level)
    }

    @Test
    @Requirement("R-022")
    fun `R_022 NotMeasured keeps the honest floor-height placeholder, never a fabricated waveform`() = runTest {
        CaptureState.capturing("s1")

        val state = LiveBarPolling.current(context, null)

        assertEquals(listOf(0f, 0f, 0f, 0f), state.level)
    }

    @Test
    @Requirement("R-022")
    fun `R_022 a clipped tick still reports its real peak and RMS bars, colour comes from tone alone`() = runTest {
        CaptureState.capturing("s1")
        LevelStatus.update(
            LevelStatus.State.Measured(
                peakDbfs = 0f,
                rmsDbfs = -30f,
                noiseFloorDbfs = -55f,
                clipped = true,
                clipCountLastSecond = 12,
                sampleRateHz = 48_000,
                updatedAtMillis = 0L,
            ),
            peakHistoryDbfs = emptyList(),
        )

        val state = LiveBarPolling.current(context, null)

        assertEquals(LiveBarTone.DEGRADED, state.tone)
        assertEquals(listOf(0.5f, 1f, 0.5f, 1f), state.level)
    }

    @Test
    @Requirement("R-128")
    fun `R_128 F16 the Usb debug override reads the red Act label with the audio-fine partial text`() = runTest {
        CaptureState.capturing("s1")
        DebugFailureOverride.show(FailurePresentation.Usb(UsbViewState("03:44", "03:47", 4)))

        val state = LiveBarPolling.current(context, null)

        assertEquals(LiveBarTone.HALTED, state.tone)
        assertEquals("Act", state.label)
        assertEquals("audio fine · radio needs permission", state.partialText)
    }

    @Test
    fun `F16 the meter itself stays green (NOMINAL) while the bar around it reads Act red`() = runTest {
        // Register F16: Fail-Usb.dc.html draws the meter bars green ("audio fine") even though
        // the rig needing USB permission halts the bar's own tone/label ("Act", halt-red).
        CaptureState.capturing("s1")
        DebugFailureOverride.show(FailurePresentation.Usb(UsbViewState("03:44", "03:47", 4)))

        val state = LiveBarPolling.current(context, null)

        assertEquals(LiveBarTone.NOMINAL, state.meterTone)
        assertEquals(LiveBarTone.HALTED, state.tone)
    }

    @Test
    @Requirement("R-128")
    fun `R_128 F6 the StorageAudioPaused debug override reads Text only`() = runTest {
        CaptureState.capturing("s1")
        DebugFailureOverride.show(
            FailurePresentation.StorageAudioPaused(StorageAudioPausedViewState("2.1 GB free", "05:20", 14)),
        )

        val state = LiveBarPolling.current(context, null)

        assertEquals(LiveBarTone.DEGRADED, state.tone)
        assertEquals("Text only", state.label)
        assertEquals(null, state.meterTone) // no board splits the meter from the tone here — F16 alone does
    }

    @Test
    @Requirement("R-128")
    fun `R_128 the Usb override wins outright over a real degradation, matching FailureMapper's own priority`() =
        runTest {
            CaptureState.capturing("s1")
            ShedStatus.update(level = 1, backlog = 0) // would otherwise read "Tier 2"
            DebugFailureOverride.show(FailurePresentation.Usb(UsbViewState("03:44", "03:47", 4)))

            val state = LiveBarPolling.current(context, null)

            assertEquals("Act", state.label)
        }

    @Test
    @Requirement("R-128")
    fun `R_128 F5 and F15's boards read Live on their own footer, so the Killed override falls through`() = runTest {
        CaptureState.capturing("s1")
        DebugFailureOverride.show(FailurePresentation.Killed(KilledViewState("03:12:40", "3 h 36 m")))

        val state = LiveBarPolling.current(context, null)

        assertEquals(LiveBarTone.NOMINAL, state.tone)
        assertEquals("Live", state.label)
    }

    @Test
    @Requirement("R-128")
    fun `R_128 F14 F17 F19-F22's boards show no live bar at all, so their overrides fall through too`() = runTest {
        CaptureState.capturing("s1")

        DebugFailureOverride.show(FailurePresentation.Clock(ClockViewState("PDT → PST", "8 h 30 m", "23:10", "06:40")))
        assertEquals("Live", LiveBarPolling.current(context, null).label)

        DebugFailureOverride.show(FailurePresentation.Interrupted(InterruptedViewState(3, "03:12 – 06:48")))
        assertEquals("Live", LiveBarPolling.current(context, null).label)
    }

    // -----------------------------------------------------------------------------------------
    // E2-G02 (N01b, FR-CAP-3a): the persistent room-audio mark, from the session's own v7 columns.
    // -----------------------------------------------------------------------------------------

    private fun session(id: String, captureMode: String?) = SessionEntity(
        id = id,
        startedAt = 0L,
        endedAt = null,
        profileId = null,
        deviceTier = null,
        appVersion = "test",
        terminationReason = null,
        sourceId = null,
        schemaVersion = OrtDatabase.SCHEMA_VERSION,
        captureMode = captureMode,
    )

    // checklist row E2-G02 (N01b's persistent room-audio disclosure).
    @Test
    @Requirement("FR-CAP-3a")
    fun `FR_CAP_3a a local-microphone session sets the room mark`() = runTest {
        val db = OrtDatabase.create(context)
        db.sessionDao().insert(session("ROOM-1", captureMode = "LOCAL_MICROPHONE"))
        CaptureState.capturing("ROOM-1")

        val state = LiveBarPolling.current(context, "ROOM-1")

        assertEquals(true, state.localMicrophone)
    }

    @Test
    @Requirement("FR-CAP-3a")
    fun `FR_CAP_3a a radio session never carries the room mark`() = runTest {
        val db = OrtDatabase.create(context)
        db.sessionDao().insert(session("RADIO-1", captureMode = "USB_RADIO"))
        CaptureState.capturing("RADIO-1")

        val state = LiveBarPolling.current(context, "RADIO-1")

        assertEquals(false, state.localMicrophone)
    }

    // checklist row E2-G05 (F23's live-bar label).
    @Test
    @Requirement("FR-CAP-5")
    fun `FR_CAP_5 a Bluetooth-audio input Lost reads Input lost, not the generic Gap`() = runTest {
        CaptureState.capturing("s1")
        val btDevice = AudioDeviceDescriptor("bt-1", AudioDeviceKind.BLUETOOTH, "Handheld BT")
        InputStatus.opened(btDevice, 16_000, "none", true, true, 0L)
        InputStatus.lost(sinceMillis = 0L)

        val state = LiveBarPolling.current(context, null)

        assertEquals(LiveBarTone.DEGRADED, state.tone)
        assertEquals("Input lost", state.label)
    }

    @Test
    @Requirement("FR-CAP-3a")
    fun `FR_CAP_3a no session id never carries the room mark, never a fabricated fact`() = runTest {
        CaptureState.capturing("s1")

        val state = LiveBarPolling.current(context, null)

        assertEquals(false, state.localMicrophone)
    }

    @Test
    @Requirement("R-128")
    fun `R_128 F15 a Call override also falls through, matching Fail-Call's own Live footer`() = runTest {
        CaptureState.capturing("s1")
        DebugFailureOverride.show(FailurePresentation.Call(CallViewState("38 s")))

        val state = LiveBarPolling.current(context, null)

        assertEquals("Live", state.label)
    }
}
