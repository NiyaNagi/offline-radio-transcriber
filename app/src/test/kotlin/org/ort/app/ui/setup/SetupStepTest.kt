package org.ort.app.ui.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The step ladder's own shape (P39, D58) and its indicator.
 *
 * R-1087 is the history this file carries: `MODELS` and `ANALYTICS_CONSENT` were once given
 * `READY`'s own [indicatorIndex], so two screens both rendered a full "8 of 8" and told the operator
 * twice that they were on the last step. Its fix — every stage gets a distinct position — is intact
 * and still tested below; what changed with P39 is that the **denominator is no longer fixed**. The
 * old `SETUP_TOTAL_STEPS` was deliberately larger than any real run because the ladder's length was
 * not knowable until several stages downstream; the rebuilt ladder has exactly one conditional stage
 * and its answer is known at launch, so [setupTotalSteps] returns a true count.
 */
class SetupStepTest {

    /**
     * The declared grouping this codebase's own design intends: which [SetupStep]s share a stage, and
     * which stage each one lights up. Written out explicitly (not derived) so that adding a future
     * [SetupStep] forces a deliberate decision here — silently falling into an existing branch of
     * [indicatorIndex]'s `when` compiles fine (Kotlin's exhaustiveness check does not catch it), but
     * this table then disagrees with the real groups and the test below fails.
     *
     * The `null` group is larger than it was, and every member earns it. [SetupStep.WELCOME] has no
     * indicator because the sequence has not begun. The rig branch has none because it is not a
     * numbered stage of a first run and is unreachable on current builds at all
     * ([SetupSnapshot.rigModuleAvailable]). [SetupStep.OVERNIGHT] has none because it is no longer
     * part of the sequence (AC-189 as amended) — it is reached only from outside it.
     */
    private val expectedGroups: Map<Int?, Set<SetupStep>> = mapOf(
        null to setOf(
            SetupStep.WELCOME,
            SetupStep.RADIO,
            SetupStep.RIG_TRANSPORT,
            SetupStep.RADIO_USB,
            SetupStep.RIG_BLUETOOTH,
            SetupStep.RADIO_VERIFIED,
            SetupStep.OVERNIGHT,
        ),
        1 to setOf(SetupStep.MODE, SetupStep.MICROPHONE_DENIED, SetupStep.BLUETOOTH_PERMISSION),
        2 to setOf(SetupStep.LISTEN, SetupStep.ROUTE_MISMATCH),
        3 to setOf(SetupStep.MODELS, SetupStep.READY),
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

    /**
     * R-1087's own defect, restated for the rebuilt ladder. `MODELS` and `READY` share position 3
     * **only** in the three-step run, where `MODELS` is not walked at all — a step that cannot be
     * shown cannot mislead anyone about which step they are on. In the run that actually shows both,
     * they are distinct, which is the property R-1087 was ever about.
     */
    @Test
    fun `R_1087 MODELS and READY never share a segment in a run that shows them both`() {
        val models = SetupStep.MODELS.indicatorIndex(SETUP_STEPS_WITH_DOWNLOAD)
        val ready = SetupStep.READY.indicatorIndex(SETUP_STEPS_WITH_DOWNLOAD)
        assertEquals(3, models)
        assertEquals(SETUP_STEPS_WITH_DOWNLOAD, ready)
        assertNotEquals(models, ready, "MODELS and READY must not share a segment when both are walked")
    }

    /** D58's 2026-09-23 resolution: three when nothing must be downloaded, four when something must,
     * and both knowable at launch so the denominator never moves under the operator mid-run. */
    @Test
    fun `AC_198 the indicator total is three with every model present and four with a download owed`() {
        assertEquals(3, setupTotalSteps(requiredModelsInstalled = true))
        assertEquals(4, setupTotalSteps(requiredModelsInstalled = false))
    }

    /** The terminal screen is always the last segment, whichever run this is — never a fixed number
     * that happens to be right in one of the two cases. */
    @Test
    fun `AC_198 READY is the last segment in both the three-step and the four-step run`() {
        assertEquals(3, SetupStep.READY.indicatorIndex(SETUP_STEPS_WITHOUT_DOWNLOAD))
        assertEquals(4, SetupStep.READY.indicatorIndex(SETUP_STEPS_WITH_DOWNLOAD))
    }

    @Test
    fun `R_1087 WELCOME carries no indicator segment, because the sequence has not begun`() {
        assertNull(SetupStep.WELCOME.indicatorIndex())
    }

    /** The numbered stages are contiguous from 1 to the total, in both runs — a gap would be a step
     * the operator is told exists and never sees. */
    @Test
    fun `R_1087 the numbered positions run from 1 to the total with no gaps, in both runs`() {
        listOf(SETUP_STEPS_WITHOUT_DOWNLOAD, SETUP_STEPS_WITH_DOWNLOAD).forEach { total ->
            val positionsInUse = SetupStep.entries.mapNotNull { it.indicatorIndex(total) }.toSet()
            assertEquals((1..total).toSet(), positionsInUse, "positions for a $total-step run")
        }
    }

    /**
     * R-1091 (register): `Setup-Mic-Denied.dc.html` draws its segment in `halt/text`, and
     * [MicrophoneDeniedScreen] renders [SetupHaltBanner] — the same halting-tone banner
     * [RouteMismatchScreen] uses — for its body. A permanently denied microphone is a real halt:
     * capture cannot start at all until the operator leaves the app for system settings and comes
     * back. P39 deleted the microphone *explainer* and changed nothing here.
     */
    @Test
    fun `R_1091 MICROPHONE_DENIED is halted, matching Setup-Mic-Denied dc html and its own halt banner`() {
        assertTrue(SetupStep.MICROPHONE_DENIED.isHalted())
    }

    @Test
    fun `R_1091 ROUTE_MISMATCH remains halted`() {
        assertTrue(SetupStep.ROUTE_MISMATCH.isHalted())
    }

    @Test
    fun `R_1091 no other SetupStep is halted`() {
        val halted = SetupStep.entries.filter { it.isHalted() }.toSet()
        assertEquals(setOf(SetupStep.MICROPHONE_DENIED, SetupStep.ROUTE_MISMATCH), halted)
    }
}
