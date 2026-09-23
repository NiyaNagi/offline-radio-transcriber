package org.ort.capture.android

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder

/**
 * R-1169, technical design §5.1: the probe-and-fallback half, and the fact that **which source was
 * actually obtained is recorded** rather than assumed. [CaptureAudioSourceTest] proves the decision
 * itself for both branches with no device; what this adds is that [AndroidAudioIo] really asks
 * `AudioManager.getProperty(PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)` and really opens the source
 * that answer chooses — the wiring that did not exist at all before (the constant was hardcoded).
 *
 * A Robolectric device reports no unprocessed support, so this environment exercises the fallback
 * branch, which is the one that matters most here: it is what every device that lied about, or
 * simply does not offer, an unprocessed path must land on. Whether a real device that *does* report
 * support opens successfully at `UNPROCESSED` is hardware's to prove, exactly as
 * [AndroidAudioIoSampleRateTest] says of `startRecording` at a negotiated rate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class AndroidAudioIoAudioSourceTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun ioForUsbDevice(unprocessedSupported: (() -> Boolean)? = null): AndroidAudioIo {
        val ctx = context()
        val deviceInfo = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_USB_DEVICE).build()
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        shadowOf(audioManager).setInputDevices(listOf(deviceInfo))
        val descriptor = AudioDeviceDescriptor(deviceInfo.id.toString(), AudioDeviceKind.USB_DEVICE, "USB adapter")
        val io = AndroidAudioIo(ctx, sampleRateHz = 16_000, unprocessedSupported = unprocessedSupported)
        io.select(descriptor)
        return io
    }

    @Test
    @Requirement("FR-CAP-1", "R-1169")
    fun `FR_CAP_1 nothing is claimed about the audio source before the device has ever been opened`() {
        assertNull(
            "a source not yet obtained must read as unknown, never as a guessed default",
            ioForUsbDevice().audioSource,
        )
    }

    @Test
    @Requirement("FR-CAP-1", "R-1169")
    fun `FR_CAP_1 a device reporting no unprocessed support opens on voice recognition and records it`() {
        val io = ioForUsbDevice()
        assertTrue(io.open())

        assertEquals(
            "a device with no unprocessed path must fall back, and say which path it actually got",
            CaptureAudioSource.VOICE_RECOGNITION,
            io.audioSource,
        )
        io.close()
    }

    @Test
    @Requirement("FR-CAP-1", "R-1169")
    fun `FR_CAP_1 a device that does report unprocessed support is opened on it, not on the fallback`() {
        val io = ioForUsbDevice(unprocessedSupported = { true })
        assertTrue(io.open())

        assertEquals(
            "technical design §5.1 asks for UNPROCESSED wherever the device offers it — this is the " +
                "branch the hardcoded VOICE_RECOGNITION could never reach",
            CaptureAudioSource.UNPROCESSED,
            io.audioSource,
        )
        io.close()
    }

    @Test
    @Requirement("FR-CAP-1", "R-1169")
    fun `FR_CAP_1 the obtained source survives close, so a check that closes first can still report it`() {
        val io = ioForUsbDevice()
        assertTrue(io.open())
        io.close()

        assertEquals(
            "RealRouteCheck closes the device before it emits its result — the fact must outlive the open",
            CaptureAudioSource.VOICE_RECOGNITION,
            io.audioSource,
        )
    }
}
