package org.ort.capture.android.fake

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioDeviceKind
import org.ort.core.capture.BluetoothAudioProfile

/**
 * FR-CAP-11, D34, FR-RIG-15: [FakeAudioIo] can present a Bluetooth device with a stated negotiated
 * profile, and can drop it mid-read exactly as a real disconnect would.
 */
class FakeAudioIoTest {

    private val bluetooth = AudioDeviceDescriptor(
        id = "bt-1",
        kind = AudioDeviceKind.BLUETOOTH,
        label = "Bluetooth Headset",
        bluetoothProfile = BluetoothAudioProfile.HFP_MSBC,
    )

    @Test
    fun `presents a bluetooth device with its stated negotiated profile once routed`() {
        val io = FakeAudioIo(deviceSampleRate = 16_000)
        io.select(bluetooth)
        io.open()
        io.forceRoutedDevice(bluetooth)

        val routed = io.routedDevice()
        assertEquals(AudioDeviceKind.BLUETOOTH, routed?.kind)
        assertEquals(BluetoothAudioProfile.HFP_MSBC, routed?.bluetoothProfile)
    }

    @Test
    fun `FR_CAP_11 dropping the device mid-read fails the next read and clears the routed device`() {
        val io = FakeAudioIo(deviceSampleRate = 16_000)
        io.select(bluetooth)
        io.open()
        io.forceRoutedDevice(bluetooth)
        io.enqueueFrames(ShortArray(160))
        assertTrue(io.read(ShortArray(160)) >= 0, "a normal read must succeed before the drop")

        io.dropDeviceMidRead()

        assertEquals(-1, io.read(ShortArray(160)), "the read the real device would fail on must fail here too")
        assertNull(io.routedDevice(), "a dropped device is no longer routed, exactly as a real disconnect reports")
    }

    @Test
    fun `a fresh open clears a prior mid-read drop, mirroring a real reconnect`() {
        val io = FakeAudioIo(deviceSampleRate = 16_000)
        io.select(bluetooth)
        io.open()
        io.forceRoutedDevice(bluetooth)
        io.dropDeviceMidRead()
        assertEquals(-1, io.read(ShortArray(160)))

        io.open() // simulates the OS having reconnected
        io.forceRoutedDevice(bluetooth)
        io.enqueueFrames(ShortArray(160))

        assertTrue(io.read(ShortArray(160)) >= 0, "reads must succeed again after a fresh open")
        assertEquals(bluetooth, io.routedDevice())
    }
}
