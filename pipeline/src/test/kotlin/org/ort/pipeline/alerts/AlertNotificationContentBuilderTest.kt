package org.ort.pipeline.alerts

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.core.AttributionState

/**
 * Build-plan P31, FR-ALR-5, AC-196: "a CONFIRMED match's notification and an INFERRED match's
 * notification are visibly and textually distinct, and the INFERRED one never states or implies
 * the callsign was heard in that transmission." This is the single most important test in this
 * unit (constitution I) — it runs on a plain JVM against [AlertNotificationContentBuilder] alone,
 * no `Notification`/Robolectric needed, so it can never be skipped for lack of a device.
 */
class AlertNotificationContentBuilderTest {

    private val watch = AlertWatch.Callsign(id = "w1", callsign = "K7ABC")

    private fun firing(state: AttributionState, count: Int = 1, repeat: Boolean = false) = AlertFiring(
        watch = watch,
        input = AlertMatchInput(
            transmissionId = "TX1",
            attributionState = state,
            stationId = "K7ABC",
            transcriptText = null,
            frequencyHz = null,
        ),
        occurrenceCount = count,
        isRepeat = repeat,
    )

    @Test
    fun `AC_196 a CONFIRMED and an INFERRED notification for the same callsign are textually distinct`() {
        val confirmed = AlertNotificationContentBuilder.build(firing(AttributionState.CONFIRMED))
        val inferred = AlertNotificationContentBuilder.build(firing(AttributionState.INFERRED))

        assertNotEquals(confirmed.title, inferred.title)
        assertNotEquals(confirmed.text, inferred.text)
    }

    @Test
    fun `AC_196 FR_ALR_5 an INFERRED match never states or implies the callsign was heard`() {
        val inferred = AlertNotificationContentBuilder.build(firing(AttributionState.INFERRED))

        assertFalse(inferred.title.contains("heard", ignoreCase = true)) {
            "title must not claim 'heard': ${inferred.title}"
        }
        assertFalse(inferred.text.startsWith("Confirmed")) {
            "body must not assert 'heard' unqualified: ${inferred.text}"
        }
        // The hedge word must actually be present -- this is what makes the assertion above
        // discriminate against a body that merely omits "heard" by accident.
        assertTrue(inferred.text.contains("not confirmed heard", ignoreCase = true))
    }

    @Test
    fun `AC_196 AMBIGUOUS and UNKNOWN read as hedged, never as a confirmed hearing`() {
        listOf(AttributionState.AMBIGUOUS, AttributionState.UNKNOWN).forEach { state ->
            val content = AlertNotificationContentBuilder.build(firing(state))
            assertFalse(content.text.startsWith("Confirmed"))
            assertTrue(content.text.contains("not confirmed heard", ignoreCase = true))
        }
    }

    @Test
    fun `FR_ALR_5 a CONFIRMED match explicitly states it was heard`() {
        val content = AlertNotificationContentBuilder.build(firing(AttributionState.CONFIRMED))

        assertTrue(content.text.contains("heard", ignoreCase = true))
        assertTrue(content.title.contains("Heard now", ignoreCase = true))
    }

    @Test
    fun `a coalesced repeat firing states its own occurrence count`() {
        val content =
            AlertNotificationContentBuilder.build(firing(AttributionState.CONFIRMED, count = 5, repeat = true))

        assertTrue(content.title.contains("5")) { "expected the count in the title: ${content.title}" }
    }

    @Test
    fun `a keyword watch never asserts a callsign was heard when none is resolved`() {
        val firing = AlertFiring(
            watch = AlertWatch.Keyword(id = "w2", keyword = "SKYWARN"),
            input = AlertMatchInput(
                "TX1",
                AttributionState.UNKNOWN,
                stationId = null,
                transcriptText = "skywarn",
                frequencyHz = null,
            ),
            occurrenceCount = 1,
            isRepeat = false,
        )

        val content = AlertNotificationContentBuilder.build(firing)

        assertFalse(content.text.contains("heard", ignoreCase = true))
    }
}
