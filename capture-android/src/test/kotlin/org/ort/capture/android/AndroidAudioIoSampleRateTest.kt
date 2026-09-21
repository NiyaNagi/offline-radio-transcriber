package org.ort.capture.android

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.util.ReflectionHelpers

/**
 * FR-CAP-2, FR-CAP-2a (register R-1114): [AndroidAudioIo] must negotiate the selected device's
 * real native sample rate rather than always opening at a fixed 48 000 Hz — the defect that made a
 * USB adapter offering only 44.1 kHz fail to open outright. [SampleRateNegotiatorTest] proves the
 * pure decision; this proves the wiring actually reads it from a real
 * `android.media.AudioDeviceInfo`, the one part of this fix Robolectric *can* prove without a
 * device — `AudioDeviceInfo.getSampleRates()` is ordinary framework Java code, not a native call,
 * so a real (Robolectric-constructed) instance answers it honestly. This is a different, narrower
 * boundary than `AudioRecord.getMinBufferSize`/`startRecording`, which run through Robolectric's
 * own shadow and cannot tell a real hardware failure from a fake success (see this module's
 * `AndroidAudioIoBluetoothTest` for that boundary) — whether `AndroidAudioIo.open()` actually
 * *succeeds* at the negotiated rate on real USB hardware remains hardware row H4's job, not this
 * test's.
 *
 * `AudioDeviceInfoBuilder` (Robolectric's own public test helper) has no way to set the rates
 * `getSampleRates()` actually reads: decompiling the real API 31 `android-all` jar Robolectric
 * ships shows that method is backed by the underlying `AudioPort.mSamplingRates` field, not by
 * `AudioDeviceInfoBuilder.setProfiles(...)`, which only feeds the newer `getAudioProfiles()`/
 * `getEncodings()` surface. So this builds a real device with Robolectric's own builder (correct
 * id/type/handle wiring) and then reaches one field further, through the same `ReflectionHelpers`
 * Robolectric's own builder uses internally, to set the rates the real framework method actually
 * returns — still a real `android.media.AudioDeviceInfo`, not a shadow or a hand-rolled fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
public class AndroidAudioIoSampleRateTest {

    private fun usbDeviceOffering(vararg sampleRates: Int): AudioDeviceInfo {
        val info = AudioDeviceInfoBuilder.newBuilder()
            .setType(AudioDeviceInfo.TYPE_USB_DEVICE)
            .build()
        val port: Any = ReflectionHelpers.getField(info, "mPort")
        ReflectionHelpers.setField(port, "mSamplingRates", sampleRates)
        return info
    }

    @Test
    @Requirement("FR-CAP-2", "FR-CAP-2a")
    fun `FR_CAP_2 a device that only offers 44_1kHz negotiates AndroidAudioIo's device sample rate down to it`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val deviceInfo = usbDeviceOffering(44_100)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        shadowOf(audioManager).setInputDevices(listOf(deviceInfo))
        val descriptor = AudioDeviceDescriptor(deviceInfo.id.toString(), AudioDeviceKind.USB_DEVICE, "USB adapter")

        val io = AndroidAudioIo(context, sampleRateHz = AndroidAudioIo.DEFAULT_SAMPLE_RATE)
        io.select(descriptor)

        assertEquals(
            "a device offering only 44.1kHz must be negotiated to, not left at the unreachable default",
            44_100,
            io.deviceSampleRate,
        )
    }

    @Test
    @Requirement("FR-CAP-2")
    fun `a device that offers the preferred rate keeps it unchanged`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val deviceInfo = usbDeviceOffering(44_100, 48_000)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        shadowOf(audioManager).setInputDevices(listOf(deviceInfo))
        val descriptor = AudioDeviceDescriptor(deviceInfo.id.toString(), AudioDeviceKind.USB_DEVICE, "USB adapter")

        val io = AndroidAudioIo(context, sampleRateHz = AndroidAudioIo.DEFAULT_SAMPLE_RATE)
        io.select(descriptor)

        assertEquals(AndroidAudioIo.DEFAULT_SAMPLE_RATE, io.deviceSampleRate)
    }

    @Test
    @Requirement("FR-CAP-2")
    fun `a device reporting no restriction at all keeps the preferred rate`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val deviceInfo = usbDeviceOffering() // empty -- Android's own "no restriction" signal
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        shadowOf(audioManager).setInputDevices(listOf(deviceInfo))
        val descriptor = AudioDeviceDescriptor(deviceInfo.id.toString(), AudioDeviceKind.USB_DEVICE, "USB adapter")

        val io = AndroidAudioIo(context, sampleRateHz = AndroidAudioIo.DEFAULT_SAMPLE_RATE)
        io.select(descriptor)

        assertEquals(AndroidAudioIo.DEFAULT_SAMPLE_RATE, io.deviceSampleRate)
    }
}
