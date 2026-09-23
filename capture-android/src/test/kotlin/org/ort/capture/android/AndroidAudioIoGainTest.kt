package org.ort.capture.android

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAudioRecord

/**
 * R-1168: the gain seam itself. The multiply lives in [AndroidAudioIo.read] and **nowhere else**,
 * because that is the only point both the capture path ([AudioRecordSource]) and onboarding's own
 * level meter (`:app`'s `RealLevelCheck`, which opens the device itself and never touches
 * [AudioRecordSource]) actually cross — a multiply one layer up would leave the very slider the
 * operator is watching dead during first-run setup.
 *
 * Applying it here also means every level measurement downstream — [LevelMeter.onFrame] on the
 * capture path, `RealLevelCheck`'s own peak on the setup path — measures **post-gain** audio, so
 * gain-induced clipping is reported honestly instead of hidden. That is the property
 * `the meter sees the gained samples` pins.
 *
 * Robolectric's `ShadowAudioRecord` feeds real PCM through the real `AudioRecord.read(short[], …)`
 * path, which is exactly the call [AndroidAudioIo.read] makes — so this is proof of the wiring,
 * not of a fake standing in for it. [CaptureGainTest] proves the arithmetic separately.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class AndroidAudioIoGainTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    /** Feeds a fixed block of PCM to every read, so what comes back out can be compared exactly. */
    private fun feed(samples: ShortArray) {
        val source = object : ShadowAudioRecord.AudioRecordSource {
            override fun readInShortArray(
                audioData: ShortArray,
                offsetInShorts: Int,
                sizeInShorts: Int,
                isBlocking: Boolean,
            ): Int {
                val n = minOf(sizeInShorts, samples.size)
                System.arraycopy(samples, 0, audioData, offsetInShorts, n)
                return n
            }
        }
        ShadowAudioRecord.setSourceProvider { source }
    }

    private fun openedIo(): AndroidAudioIo {
        val ctx = context()
        val deviceInfo = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_USB_DEVICE).build()
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        shadowOf(audioManager).setInputDevices(listOf(deviceInfo))
        val descriptor = AudioDeviceDescriptor(deviceInfo.id.toString(), AudioDeviceKind.USB_DEVICE, "USB adapter")
        val io = AndroidAudioIo(ctx, sampleRateHz = 16_000)
        io.select(descriptor)
        assertTrue("the device must open before anything can be read from it", io.open())
        return io
    }

    @After
    fun tearDown() {
        ShadowAudioRecord.clearSource()
        CaptureGain.reset()
    }

    @Test
    @Requirement("FR-CAP-1", "R-1168")
    fun `FR_CAP_1 no gain set leaves the samples read bit identical to what the device delivered`() {
        val delivered = shortArrayOf(0, 1_000, -1_000, Short.MAX_VALUE, Short.MIN_VALUE)
        feed(delivered)
        val io = openedIo()

        val buffer = ShortArray(delivered.size)
        val n = io.read(buffer)
        io.close()

        assertEquals(delivered.size, n)
        assertArrayEquals(delivered, buffer)
    }

    @Test
    @Requirement("FR-CAP-1", "R-1168")
    fun `FR_CAP_1 the stored gain reaches the audio read and saturates rather than wrapping`() {
        val delivered = shortArrayOf(1_000, -1_000, 20_000, -20_000)
        feed(delivered)
        val io = openedIo()

        CaptureGain.setGainDb(6)
        val buffer = ShortArray(delivered.size)
        io.read(buffer)
        io.close()

        assertTrue("a 6 dB gain must roughly double a sample well inside full scale", buffer[0] in 1_900..2_100)
        assertTrue("and the negative one with it", buffer[1] in -2_100..-1_900)
        assertEquals("2x of 20 000 exceeds full scale: clamp, never wrap", Short.MAX_VALUE, buffer[2])
        assertEquals("and the negative one with it", Short.MIN_VALUE, buffer[3])
    }

    @Test
    @Requirement("FR-CAP-6", "R-1168")
    fun `FR_CAP_6 the level meter sees the gained samples, so gain induced clipping is reported`() {
        val delivered = ShortArray(160) { 20_000 }
        feed(delivered)
        val io = openedIo()
        val meter = LevelMeter(org.ort.core.SystemClock)

        CaptureGain.setGainDb(12)
        val buffer = ShortArray(delivered.size)
        val n = io.read(buffer)
        meter.onFrame(buffer, n, 16_000)
        io.close()

        assertTrue(
            "a frame gained into full scale must be reported as clipping, not silently hidden",
            meter.snapshot?.clipped == true,
        )
    }

    @Test
    @Requirement("FR-CAP-1", "R-1168")
    fun `FR_CAP_1 a gain set after the device is open takes effect on the very next read`() {
        val delivered = shortArrayOf(1_000)
        feed(delivered)
        val io = openedIo()

        val before = ShortArray(1)
        io.read(before)
        CaptureGain.setGainDb(12)
        val after = ShortArray(1)
        io.read(after)
        io.close()

        assertEquals("nothing set yet, so the first read is untouched", 1_000.toShort(), before[0])
        assertTrue("the next read must already carry the new gain", after[0] > 3_000)
    }
}
