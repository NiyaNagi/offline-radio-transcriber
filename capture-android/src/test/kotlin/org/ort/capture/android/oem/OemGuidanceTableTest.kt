package org.ort.capture.android.oem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement

class OemGuidanceTableTest {

    @Test
    @Requirement("AC-66", "FR-SVC-5a")
    fun `AC_66 Oppo gets the four ColorOS-specific steps`() {
        val guidance = OemGuidanceTable.forDevice(manufacturer = "OPPO", brand = "oppo")
        assertEquals(4, guidance.steps.size)
        assertEquals(guidance.steps, OemGuidanceTable.coloros)
    }

    @Test
    @Requirement("AC-66")
    fun `AC_66 Realme and OnePlus also resolve to the ColorOS steps post-merge`() {
        assertEquals(OemGuidanceTable.coloros, OemGuidanceTable.forDevice("realme", "realme").steps)
        assertEquals(OemGuidanceTable.coloros, OemGuidanceTable.forDevice("OnePlus", "OnePlus").steps)
    }

    @Test
    @Requirement("AC-66")
    fun `AC_66 a device with no known OEM quirks gets the generic steps`() {
        val guidance = OemGuidanceTable.forDevice(manufacturer = "Google", brand = "google")
        assertEquals(OemGuidanceTable.generic, guidance.steps)
        assertTrue(guidance.steps.isNotEmpty())
    }
}
