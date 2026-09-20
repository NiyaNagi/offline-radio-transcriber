package org.ort.app.ui.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * R-1087 (register): P22's [SetupStep.MODELS] and P28's [SetupStep.ANALYTICS_CONSENT] were each
 * given [SetupStep.READY]'s own [indicatorIndex] rather than one of their own — the lead's
 * side-by-side capture of `setup-models/S11a-models` and
 * `setup-analytics-consent/S11b-analytics-consent` showed both rendering an identical, full
 * "8 of 8" segment bar, telling the operator twice that they were on the last step.
 *
 * Fix: every step [SetupStateMachine.stepFor] can return, and every transient live-only step
 * ([SetupStep.VERIFY], [SetupStep.ROUTE_MISMATCH], [SetupStep.RADIO_USB],
 * [SetupStep.RADIO_VERIFIED]) that still needs an indicator of its own, has a distinct position —
 * except for genuine sub-screens of one stage (the mic group, the radio group) and the two pre-flow
 * notices with no indicator at all ([SetupStep.WELCOME], [SetupStep.JURISDICTION_NOTICE]).
 * [SETUP_TOTAL_STEPS] is a fixed total (every reachable stage, not the subset a given run walks) —
 * see that constant's own doc comment for why a per-run denominator would be its own dishonesty.
 */
class SetupStepTest {

    /**
     * The declared grouping this codebase's own design intends: which [SetupStep]s share a stage,
     * and which stage each one lights up. Written out explicitly (not derived) so that adding a
     * future [SetupStep] forces a deliberate decision here — silently falling into an existing
     * branch of [indicatorIndex]'s `when` compiles fine (Kotlin's exhaustiveness check does not
     * catch it), but this table then disagrees with the real groups and the test below fails.
     */
    private val expectedGroups: Map<Int?, Set<SetupStep>> = mapOf(
        null to setOf(SetupStep.WELCOME, SetupStep.JURISDICTION_NOTICE),
        1 to setOf(SetupStep.MODE),
        2 to setOf(SetupStep.MICROPHONE, SetupStep.MICROPHONE_DENIED, SetupStep.BLUETOOTH_PERMISSION),
        3 to setOf(SetupStep.NOTIFICATIONS),
        4 to setOf(SetupStep.INPUT, SetupStep.VERIFY, SetupStep.ROUTE_MISMATCH),
        5 to setOf(SetupStep.LEVEL),
        6 to setOf(SetupStep.OVERNIGHT),
        7 to setOf(
            SetupStep.RADIO,
            SetupStep.RIG_TRANSPORT,
            SetupStep.RADIO_USB,
            SetupStep.RIG_BLUETOOTH,
            SetupStep.RADIO_VERIFIED,
        ),
        8 to setOf(SetupStep.MODELS),
        9 to setOf(SetupStep.ANALYTICS_CONSENT),
        10 to setOf(SetupStep.READY),
    )

    @Test
    fun `R_1087 every SetupStep's indicator position matches the declared stage groups, with no accidental sharing`() {
        val actualGroups = SetupStep.entries.groupBy { it.indicatorIndex() }.mapValues { it.value.toSet() }
        assertEquals(expectedGroups, actualGroups)
    }

    @Test
    fun `R_1087 the declared groups account for every SetupStep exactly once`() {
        assertEquals(SetupStep.entries.toSet(), expectedGroups.values.flatten().toSet())
        assertEquals(SetupStep.entries.size, expectedGroups.values.sumOf { it.size })
    }

    @Test
    fun `R_1087 MODELS, ANALYTICS_CONSENT and READY no longer share a segment`() {
        val models = SetupStep.MODELS.indicatorIndex()
        val analytics = SetupStep.ANALYTICS_CONSENT.indicatorIndex()
        val ready = SetupStep.READY.indicatorIndex()
        assertNotEquals(models, analytics, "MODELS and ANALYTICS_CONSENT must not share a segment")
        assertNotEquals(analytics, ready, "ANALYTICS_CONSENT and READY must not share a segment")
        assertNotEquals(models, ready, "MODELS and READY must not share a segment")
    }

    @Test
    fun `R_1087 WELCOME and JURISDICTION_NOTICE remain the only steps with no indicator segment`() {
        assertNull(SetupStep.WELCOME.indicatorIndex())
        assertNull(SetupStep.JURISDICTION_NOTICE.indicatorIndex())
        val everyoneElseHasOne = SetupStep.entries
            .filterNot { it == SetupStep.WELCOME || it == SetupStep.JURISDICTION_NOTICE }
            .all { it.indicatorIndex() != null }
        assertTrue(everyoneElseHasOne, "every other step must carry an indicator position")
    }

    @Test
    fun `R_1087 the reachable indicator positions run from 1 to SETUP_TOTAL_STEPS with no gaps`() {
        val positionsInUse = SetupStep.entries.mapNotNull { it.indicatorIndex() }.toSet()
        assertEquals((1..SETUP_TOTAL_STEPS).toSet(), positionsInUse)
    }
}
