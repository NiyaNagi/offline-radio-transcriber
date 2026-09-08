package org.ort.app.ui.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.components.LiveBarTone
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioDeviceKind
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
    @Requirement("R-100")
    fun `R_100 a tier drop still outranks a stale rig, matching the prior priority order`() = runTest {
        CaptureState.capturing("s1")
        ShedStatus.update(level = 1, backlog = 0)
        RigStatus.stale(RigStatus.State.Connected("TH-D75A", emptyList()), sinceMillis = 0L)
        val state = LiveBarPolling.current(context, null)
        assertEquals("Tier 2", state.label)
    }
}
