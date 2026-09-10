package org.ort.core.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** FR-CAP-8: capture mode is a closed set of exactly three values, each with an operator-facing label. */
class CaptureModeTest {

    @Test
    fun `FR_CAP_8 capture mode is a closed set of exactly three values`() {
        assertEquals(
            setOf("LOCAL_MICROPHONE", "USB_RADIO", "BLUETOOTH_RADIO"),
            CaptureMode.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `FR_CAP_8 each mode carries the exact operator-facing label from the design copy`() {
        assertEquals("Local microphone", CaptureMode.LOCAL_MICROPHONE.operatorLabel)
        assertEquals("USB-connected radio", CaptureMode.USB_RADIO.operatorLabel)
        assertEquals("Bluetooth-connected radio", CaptureMode.BLUETOOTH_RADIO.operatorLabel)
    }
}
