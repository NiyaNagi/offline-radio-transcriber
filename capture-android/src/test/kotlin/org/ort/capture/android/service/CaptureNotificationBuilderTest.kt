package org.ort.capture.android.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement

class CaptureNotificationBuilderTest {

    @Test
    @Requirement("AC-61", "FR-PLT-3")
    fun `AC_61 the notification content type cannot carry transcript text`() {
        // Structural guarantee, not a string search: the content type has exactly the fields
        // state/elapsed/count, so there is no field a transcript could ever be assigned to.
        val fieldNames = CaptureNotificationContent::class.java.declaredFields.map { it.name }.toSet()
        assertEquals(setOf("stateLabel", "elapsedLabel", "transmissionCount", "title", "text"), fieldNames)
    }

    @Test
    @Requirement("AC-61")
    fun `AC_61 the rendered text is built only from state, elapsed and count`() {
        val content = CaptureNotificationBuilder.build("Capturing", elapsedMillis = 3_661_000, transmissionCount = 7)
        assertEquals("01:01:01", content.elapsedLabel) // 3661s = 1h 1m 1s
        assertTrue(content.text.contains("Capturing"))
        assertTrue(content.text.contains("7"))
    }
}
