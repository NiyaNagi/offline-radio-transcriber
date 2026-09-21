package org.ort.capture.android

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.captureapi.CaptureEvent
import org.ort.testing.Requirement
import org.ort.testing.TestClock
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder

/**
 * FR-CAP-3, FR-RUN-11 (register R-1113): end to end through the REAL [AndroidAudioIo] — not
 * [org.ort.capture.android.fake.FakeAudioIo] — proving the callback [AndroidAudioIoInterruptionTest]
 * shows now fires drives [AudioRecordSource]'s existing recovery policy and produces a
 * [GapRecord] whose bounds reflect the *true* outage duration, not a zero-length placeholder. Before
 * P34, this exact scenario could not happen in production: [AndroidAudioIo.setEventListener]'s
 * callback was stored and never invoked, so nothing upstream of a failed `read()` could ever open
 * this gap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
public class AndroidAudioIoInterruptionGapTest {

    @Test
    @Requirement("FR-CAP-3", "FR-RUN-11")
    fun `FR_RUN_11 a real device-removed callback opens a gap of the true outage length and capture resumes`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val shadowManager = shadowOf(audioManager)
        val info = AudioDeviceInfoBuilder.newBuilder()
            .setType(AudioDeviceInfo.TYPE_USB_DEVICE)
            .build()
        shadowManager.setInputDevices(listOf(info))

        val descriptor = AudioDeviceDescriptor(info.id.toString(), AudioDeviceKind.USB_DEVICE, "USB Audio Adapter")
        val io = AndroidAudioIo(context, sampleRateHz = 16_000)
        val clock = TestClock()
        val source = AudioRecordSource(io, selection = descriptor, clock = clock)
        val tracker = GapTracker(clock)

        val collected = mutableListOf<CaptureEvent>()
        val job = this.launch {
            source.start().collect { ev ->
                collected.add(ev)
                tracker.onEvent(ev)
                // Only once the source has actually started (past setEventListener/select/open,
                // i.e. it has already delivered a real frame) is it safe to fire the OS callback --
                // firing it before that would find no listener registered yet, exactly the race a
                // bare `launch { ... }` followed immediately by `removeInputDevice` would hit.
                if (ev is CaptureEvent.Frames && collected.count { it is CaptureEvent.Frames } == 1) {
                    shadowManager.removeInputDevice(info, true)
                }
                if (ev is CaptureEvent.Interrupted) {
                    // Real time passing while the USB adapter is physically unplugged.
                    clock.advance(7_000)
                    // The OS reports the adapter reconnected -- back in the enumeration before the
                    // backoff ladder's next retry reopens it.
                    shadowManager.addInputDevice(info, true)
                }
                if (ev is CaptureEvent.Resumed) {
                    source.stop()
                }
            }
        }

        job.join()

        assertTrue("a real callback-driven interruption must be reported", collected.any { it is CaptureEvent.Interrupted })
        assertTrue("capture must resume once the device is back", collected.any { it is CaptureEvent.Resumed })
        assertEquals("exactly one gap must be recorded", 1, tracker.gaps.size)
        val gap = tracker.gaps.single()
        assertEquals(
            "the recorded gap must span the real elapsed outage, not a zero-length placeholder",
            7_000L,
            gap.endWallMillis - gap.startWallMillis,
        )
        assertEquals("device removed", gap.cause)
    }
}
