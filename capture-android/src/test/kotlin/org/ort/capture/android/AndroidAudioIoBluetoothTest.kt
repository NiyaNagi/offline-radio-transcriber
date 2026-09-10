package org.ort.capture.android

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FR-CAP-11, D34: [AndroidAudioIo]'s Bluetooth SCO activation, on the one state a shadow
 * `AudioManager` can actually observe without a real Bluetooth stack — whether SCO routing was
 * requested. Pinned to API 30 so the legacy `startBluetoothSco`/`setBluetoothScoOn` path runs
 * (the API ≥ 31 `setCommunicationDevice` path needs a real matching `AudioDeviceInfo`, which is
 * hardware row H5's job, not a shadow's).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
public class AndroidAudioIoBluetoothTest {

    private val bluetooth = AudioDeviceDescriptor("bt-1", AudioDeviceKind.BLUETOOTH, "Bluetooth Headset")
    private val builtIn = AudioDeviceDescriptor("mic-0", AudioDeviceKind.BUILT_IN_MIC, "Built-in Microphone")

    private fun audioManager(context: Context): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Test
    @Requirement("FR-CAP-11", "D34")
    public fun `FR_CAP_11 opening a bluetooth selection activates SCO routing`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val io = AndroidAudioIo(context, sampleRateHz = 16_000)
        io.select(bluetooth)

        assertTrue("a bluetooth selection must open successfully", io.open())
        assertTrue("SCO routing must be requested for a bluetooth selection", audioManager(context).isBluetoothScoOn)

        io.close()
    }

    @Test
    @Requirement("FR-CAP-11", "D34")
    public fun `FR_CAP_11 closing undoes exactly the SCO activation this instance made`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val io = AndroidAudioIo(context, sampleRateHz = 16_000)
        io.select(bluetooth)
        io.open()

        io.close()

        assertFalse("closing must undo SCO activation", audioManager(context).isBluetoothScoOn)
    }

    @Test
    @Requirement("FR-CAP-11")
    public fun `a non-bluetooth selection never touches SCO routing at all`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val io = AndroidAudioIo(context, sampleRateHz = 16_000)
        io.select(builtIn)

        io.open()

        assertFalse(
            "a built-in mic selection must never request bluetooth SCO routing",
            audioManager(context).isBluetoothScoOn,
        )
        io.close()
    }
}
