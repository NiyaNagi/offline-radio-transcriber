package org.ort.llm

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * E2-I02, FR-DIG-4, D5: [CallsignShapeFilter] rejects any callsign-shaped token not present in
 * the resolved set supplied for the thread. `FR_DIG_4_filter_rejects_invented_callsign` is the
 * named test the build-plan/checklist cite — it runs the filter against
 * [FakeLlmEngine]'s deliberately-hallucinating script, so the rejection is proven against a real
 * (fake) engine output, not a hand-written string.
 */
class CallsignShapeFilterTest {

    @Test
    fun `filter passes text whose callsign-shaped tokens are all in the allowed set`() {
        val result = CallsignShapeFilter.filter("Worked W1AW on 20 meters, said 73.", setOf("W1AW"))

        assertEquals(LlmResult.Text("Worked W1AW on 20 meters, said 73."), result)
    }

    @Test
    fun `filter rejects a callsign-shaped token outside the allowed set and names it`() {
        val result = CallsignShapeFilter.filter("Worked K9ZZZ on the repeater.", setOf("W1AW"))

        assertTrue(result is LlmResult.Refused)
        assertTrue((result as LlmResult.Refused).reason.contains("K9ZZZ"))
    }

    @Test
    fun `filter is case-insensitive when matching against the allowed set`() {
        val result = CallsignShapeFilter.filter("worked w1aw on the net", setOf("W1AW"))

        assertTrue(result is LlmResult.Text)
    }

    @Test
    fun `filter does not flag ordinary prose with no digit`() {
        val result = CallsignShapeFilter.filter("Said they were working on an antenna.", emptySet())

        assertTrue(result is LlmResult.Text)
    }

    @Test
    fun `filter treats a band abbreviation as callsign-shaped, a documented conservative false positive`() {
        // FR-DIG-4 / constitution I: sacrificing recall (occasionally refusing a harmless
        // sentence) is the correct direction to be wrong in, never the reverse.
        val result = CallsignShapeFilter.filter("Busy afternoon on 20m today.", emptySet())

        assertTrue(result is LlmResult.Refused)
    }

    @Test
    fun `FR_DIG_4_filter_rejects_invented_callsign`() = runTest {
        val engine = FakeLlmEngine().apply {
            generateBehavior = FakeLlmEngine.GenerateBehavior.INVENT_CALLSIGN
            inventedCallsign = "K9ZZZ"
        }
        val allowedCallsigns = setOf("W1AW")

        val generated = engine.generate(LlmRequest("summarize this thread", 128, allowedCallsigns)) as LlmResult.Text
        val filtered = CallsignShapeFilter.filter(generated.text, allowedCallsigns)

        assertTrue(filtered is LlmResult.Refused, "expected the invented callsign to be refused, got $filtered")
        assertTrue((filtered as LlmResult.Refused).reason.contains("K9ZZZ"))
    }
}
