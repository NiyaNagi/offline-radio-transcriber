package org.ort.core.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** CON-CAP-1 (amended by D34), FR-CAP-11: the negotiated Bluetooth codec is a closed, disclosed set. */
class BluetoothAudioProfileTest {

    @Test
    fun `the bluetooth audio profile set is exactly the three closed values`() {
        assertEquals(
            setOf("HFP_MSBC", "HFP_CVSD", "UNKNOWN"),
            BluetoothAudioProfile.entries.map { it.name }.toSet(),
        )
    }
}
