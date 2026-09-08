package org.ort.capture.android.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement

class CaptureNotificationBuilderTest {

    private val defaultFacts = CaptureNotificationExpandedFacts.EMPTY.copy(storageLabel = "no budget set")

    private fun build(
        state: CaptureNotificationContent.State = CaptureNotificationContent.State.CAPTURING,
        elapsedMillis: Long = 0L,
        transmissionCount: Int = 0,
        facts: CaptureNotificationExpandedFacts = defaultFacts,
    ): CaptureNotificationContent = CaptureNotificationBuilder.build(state, elapsedMillis, transmissionCount, facts)

    @Test
    @Requirement("AC-61", "FR-PLT-3", "R-102")
    fun `AC_61 the notification content type cannot carry transcript text`() {
        // Structural guarantee, not a string search: the exact, closed, narrowly-named field set --
        // no field named or shaped for a transcript, matching this type's original discipline.
        val fieldNames = CaptureNotificationContent::class.java.declaredFields.map { it.name }.toSet()
        assertEquals(
            setOf(
                "state", "elapsedMillis", "transmissionCount", "secondLine",
                "lastOverCallsign", "lastOverAtWallMillis", "inputDeviceName", "inputVerified", "storageLabel",
            ),
            fieldNames,
        )
    }

    @Test
    @Requirement("AC-61", "R-102")
    fun `AC_61 title is built only from state, elapsed and count`() {
        val content = build(
            state = CaptureNotificationContent.State.CAPTURING,
            elapsedMillis = 3_661_000,
            transmissionCount = 7,
        )
        assertEquals("Capturing · 1:01 · 7 overs", content.title)
    }

    @Test
    @Requirement("R-102")
    fun `R_102 title elapsed is h_mm, matching the board -- not the old HH_MM_SS`() {
        assertEquals("6:42", elapsedHoursMinutes(6 * 3_600_000L + 42 * 60_000L))
        assertEquals("0:00", elapsedHoursMinutes(0L))
    }

    @Test
    @Requirement("R-102")
    fun `R_102 the current updateNotification strings become states, not free text`() {
        fun titleWord(state: CaptureNotificationContent.State) = build(state = state).title.substringBefore(" ·")
        assertEquals("Interrupted", titleWord(CaptureNotificationContent.State.INTERRUPTED))
        assertEquals("Failed", titleWord(CaptureNotificationContent.State.FAILED))
        // ASR unavailable is capture genuinely running -- the design still reads "Capturing".
        assertEquals("Capturing", titleWord(CaptureNotificationContent.State.ASR_UNAVAILABLE))
    }

    @Test
    @Requirement("R-102")
    fun `R_102 second line is frequencies dot tier when nothing is degraded`() {
        val facts = CaptureNotificationExpandedFacts.EMPTY.copy(frequenciesLabel = "145.230 and 146.960", tier = 3)
        val content = build(facts = facts)
        assertTrue(content.secondLine is CaptureNotificationContent.SecondLine.Normal)
        assertEquals("145.230 and 146.960 · tier 3", content.secondLine.text)
    }

    @Test
    @Requirement("R-102")
    fun `R_102_notification_second_line_switches_to_degraded_when_a_reason_is_given`() {
        val facts = CaptureNotificationExpandedFacts.EMPTY.copy(
            frequenciesLabel = "145.230 and 146.960",
            tier = 3,
            degradedReason = "Running warm — tier 2 · radio disconnected, frequency stale",
        )
        val content = build(facts = facts)
        assertTrue(content.secondLine is CaptureNotificationContent.SecondLine.Degraded)
        assertEquals("Running warm — tier 2 · radio disconnected, frequency stale", content.secondLine.text)
    }

    @Test
    @Requirement("R-102")
    fun `R_102 last-over, input and storage are carried for the expanded rows, never a transcript`() {
        val facts = CaptureNotificationExpandedFacts.EMPTY.copy(
            lastOverCallsign = "W7NPC",
            lastOverAtWallMillis = 1_000L,
            inputDeviceName = "USB Audio Device",
            inputVerified = true,
            storageLabel = "38.2 of 60 GB · 16 nights left",
        )
        val content = build(facts = facts)
        assertTrue(content.hasLastOver)
        assertEquals("W7NPC", content.lastOverCallsign)
        assertEquals("USB Audio Device", content.inputDeviceName)
        assertTrue(content.inputVerified)
        assertEquals("38.2 of 60 GB · 16 nights left", content.storageLabel)
    }
}
