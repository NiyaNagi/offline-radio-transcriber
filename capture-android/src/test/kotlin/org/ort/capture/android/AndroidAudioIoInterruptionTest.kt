package org.ort.capture.android

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.util.ReflectionHelpers

/**
 * FR-RUN-11, FR-CAP-3 (register R-1113): before this, [AndroidAudioIo.setEventListener] stored the
 * callback and **nothing in production ever invoked it** — the only real interruption signal was a
 * read returning `< 0` after the device was already gone. This proves the real, non-fake half of
 * the fix: registering a genuine `android.media.AudioDeviceCallback` with a genuine `AudioManager`
 * and having it actually fire when the selected device disappears — not a
 * `FakeAudioIo.dropDeviceMidRead()` standing in for what the doc comment used to admit AndroidAudioIo
 * itself does not do. `ShadowAudioManager.removeInputDevice(info, true)` drives the real
 * `AudioDeviceCallback` dispatch path (verified against the API 30 `android-all` jar), so this is
 * proof the callback is wired, not merely present in source.
 *
 * `AudioRecordSource`'s own interruption-and-recovery policy (backoff, the resulting
 * [GapTracker] gap) is already exhaustively covered against [org.ort.capture.android.fake.FakeAudioIo]
 * in `AudioRecordSourceTest`; what was never covered — and what R-1113 is actually about — is
 * whether the real [AudioIo] can ever produce that signal proactively in the first place. That is
 * the one thing this test checks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
public class AndroidAudioIoInterruptionTest {

    private val usb = AudioDeviceDescriptor("usb-selected", AudioDeviceKind.USB_DEVICE, "USB Audio Adapter")

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    /**
     * `AudioDeviceInfoBuilder.build()` always assigns the underlying `AudioHandle` an id of `0`
     * (verified by decompiling the real API 30 `android-all` jar) — fine for a test with a single
     * device, but two devices built this way are otherwise indistinguishable by
     * [AudioDeviceInfo.getId], which is exactly the field [AndroidAudioIo] matches on. [id] forces
     * them apart the same way [AndroidAudioIoSampleRateTest] reaches past the builder for
     * `mSamplingRates`.
     */
    private fun deviceWithId(type: Int, id: Int): AudioDeviceInfo {
        val info = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
        val port: Any = ReflectionHelpers.getField(info, "mPort")
        val handle: Any = ReflectionHelpers.getField(port, "mHandle")
        ReflectionHelpers.setField(handle, "mId", id)
        return info
    }

    @Test
    @Requirement("FR-RUN-11", "FR-CAP-3")
    fun `FR_RUN_11 the OS removing the selected device raises a real interruption event`() {
        val ctx = context()
        val deviceInfo = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_USB_DEVICE).build()
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val shadowManager = shadowOf(audioManager)
        shadowManager.setInputDevices(listOf(deviceInfo))

        val descriptor = usb.copy(id = deviceInfo.id.toString())
        val io = AndroidAudioIo(ctx, sampleRateHz = 16_000)
        val events = mutableListOf<AudioIoEvent>()
        io.setEventListener { events.add(it) }
        io.select(descriptor)
        assertTrue("the device must open before it can be dropped", io.open())

        // The OS reports the selected device disappearing -- a USB adapter physically unplugged.
        shadowManager.removeInputDevice(deviceInfo, true)

        assertTrue(
            "a real AudioDeviceCallback firing for the selected device must surface as an interruption",
            events.any { it is AudioIoEvent.Interrupted },
        )
        io.close()
    }

    @Test
    @Requirement("FR-RUN-11")
    fun `removing a device other than the one selected raises nothing`() {
        val ctx = context()
        val selectedInfo = deviceWithId(AudioDeviceInfo.TYPE_USB_DEVICE, id = 1)
        val otherInfo = deviceWithId(AudioDeviceInfo.TYPE_BUILTIN_MIC, id = 2)
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val shadowManager = shadowOf(audioManager)
        shadowManager.setInputDevices(listOf(selectedInfo, otherInfo))

        val descriptor = usb.copy(id = selectedInfo.id.toString())
        val io = AndroidAudioIo(ctx, sampleRateHz = 16_000)
        val events = mutableListOf<AudioIoEvent>()
        io.setEventListener { events.add(it) }
        io.select(descriptor)
        assertTrue(io.open())

        // Ordinary background device churn -- some other input disappearing is not this capture's
        // own interruption and must never be reported as one.
        shadowManager.removeInputDevice(otherInfo, true)

        assertFalse(
            "removing an unrelated device must never be reported as this capture's own interruption",
            events.any { it is AudioIoEvent.Interrupted },
        )
        io.close()
    }
}
