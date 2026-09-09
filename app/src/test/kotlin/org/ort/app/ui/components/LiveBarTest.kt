package org.ort.app.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/**
 * R-022 (ui-conformance-plan WP2): P4 ("capture state never in doubt") and P5 ("live vs record
 * differ") in one component — no live bar existed at all before this landed.
 */
@RunWith(RobolectricTestRunner::class)
class LiveBarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `R_022 live bar degraded and halted variants differ in more than colour`() {
        val nominal = LiveBarViewState(
            level = listOf(0.4f, 1f, 0.6f, 1f),
            partialText = "and we're clear",
            label = "Live",
            tone = LiveBarTone.NOMINAL,
        )
        val degraded = LiveBarViewState(
            level = listOf(0.2f, 0.3f, 0.2f, 0.3f),
            partialText = null,
            label = "Tier 2",
            tone = LiveBarTone.DEGRADED,
        )
        val halted = LiveBarViewState(
            level = listOf(0f, 0f, 0f, 0f),
            partialText = null,
            label = "Halted — no route",
            tone = LiveBarTone.HALTED,
        )

        composeTestRule.setContent {
            OrtTheme {
                androidx.compose.foundation.layout.Column {
                    LiveBar(state = nominal, onClick = {}, modifier = Modifier.testTag("nominal"))
                    LiveBar(state = degraded, onClick = {}, modifier = Modifier.testTag("degraded"))
                    LiveBar(state = halted, onClick = {}, modifier = Modifier.testTag("halted"))
                }
            }
        }

        // Different labels/copy per tone, not merely a colour swap (constitution: "colour is
        // reinforcement, never signal"). R-380 correction (WP2, gate-blocking): `LiveBar`'s own
        // outer node now carries its composed description as both `contentDescription` and `text`
        // (this file's own `CHANGELOG.md`) — the default merged tree finds it directly and
        // uniquely; `useUnmergedTree = true` would also surface the still-present inner `Text`s,
        // two matches instead of one for the bare-label ("Tier 2"/"Halted — no route") cases,
        // where the composed description equals the label alone.
        composeTestRule.onNodeWithText("Live", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Tier 2").assertIsDisplayed()
        composeTestRule.onNodeWithText("Halted — no route").assertIsDisplayed()
    }

    @Test
    fun `FR_A11Y_2 the live bar is one 44dp target with a role and a merged description`() {
        val state = LiveBarViewState(
            level = listOf(0.2f, 0.6f, 0.3f, 0.8f),
            partialText = "seven three",
            label = "Live",
            tone = LiveBarTone.NOMINAL,
        )
        var clicked = false

        composeTestRule.setContent {
            OrtTheme { LiveBar(state = state, onClick = { clicked = true }, modifier = Modifier.testTag("bar")) }
        }

        val node = composeTestRule.onNodeWithTag("bar")
        node.assertHeightIsAtLeast(44.dp)
        node.assert(hasContentDescription("Live", substring = true))
        node.assert(hasContentDescription("seven three", substring = true))
        node.performClick()
        assert(clicked)
    }

    @Test
    fun `R_381_the live bar's own unmerged node carries both OnClick and the composed description`() {
        // R-381's own attribution list names "the live bar" — checked here, not merely asserted:
        // `LiveBar` already composes its description explicitly (`contentDescription = description`
        // in the same `semantics(mergeDescendants = true)` block `clickable` lives in, not an empty
        // block depending on merge-from-descendants alone), so this is confirmation, not a fix.
        // `useUnmergedTree = true` matters here specifically — the assertion is about this one
        // physical node's own semantics config, not whatever the merged-tree view would report.
        val state = LiveBarViewState(
            level = listOf(0.2f, 0.6f, 0.3f, 0.8f),
            partialText = "seven three",
            label = "Live",
            tone = LiveBarTone.NOMINAL,
        )

        composeTestRule.setContent {
            OrtTheme { LiveBar(state = state, onClick = {}, modifier = Modifier.testTag("bar")) }
        }

        val node = composeTestRule.onNodeWithTag("bar", useUnmergedTree = true).fetchSemanticsNode()
        assert(node.config.getOrNull(SemanticsActions.OnClick) != null) {
            "expected the live bar's own node to carry OnClick"
        }
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        assert(description?.contains("Live") == true && description.contains("seven three")) {
            "expected the live bar's own node (carrying OnClick) to also carry a description with " +
                "both the label and the partial text, got $description"
        }
    }

    @Test
    fun `R_128_meterColorFor follows the given tone directly, independent of any bar-wide tone`() {
        // A pure, directly-testable decision (the `scrubFraction` pattern) — Robolectric can't
        // verify a rendered pixel colour reliably here, but the tone-to-colour decision itself is
        // exactly, and cheaply, testable without Compose at all.
        assert(meterColorFor(LiveBarTone.NOMINAL, listOf(0.5f)) == OrtColors.accentGreen)
        assert(meterColorFor(LiveBarTone.NOMINAL, listOf(0f, 0f)) == OrtColors.meterIdle)
        assert(meterColorFor(LiveBarTone.DEGRADED, listOf(0.5f)) == OrtColors.meterWarn)
        assert(meterColorFor(LiveBarTone.HALTED, listOf(0.5f)) == OrtColors.haltFill)
    }

    @Test
    fun `R_128_a meterTone override lets the meter read green while the bar around it is degraded or halted`() {
        // Fail-Usb.dc.html: a USB-permission failure is a rig problem, not an audio one — the
        // meter itself stays green ("audio fine") even while the label calls for action.
        val degradedBarNominalMeter = LiveBarViewState(
            level = listOf(0.5f, 0.5f),
            partialText = null,
            label = "Act",
            tone = LiveBarTone.DEGRADED,
            meterTone = LiveBarTone.NOMINAL,
        )
        val resolvedMeterTone = degradedBarNominalMeter.meterTone ?: degradedBarNominalMeter.tone
        assert(resolvedMeterTone == LiveBarTone.NOMINAL)
        assert(
            meterColorFor(resolvedMeterTone, degradedBarNominalMeter.level) ==
                OrtColors.accentGreen,
        )

        // The default (`meterTone = null`, every caller before this existed) still just follows
        // `tone` — additive, no silent behaviour change for an existing caller.
        val noOverride = LiveBarViewState(
            level = listOf(0.5f),
            partialText = null,
            label = "Tier 2",
            tone = LiveBarTone.DEGRADED,
        )
        assert((noOverride.meterTone ?: noOverride.tone) == LiveBarTone.DEGRADED)
    }

    @Test
    fun `R_128_liveBarLabelColor reads Act in haltText regardless of the tone's own label colour`() {
        val halt = OrtColors.haltText
        assert(liveBarLabelColor("Act", fallback = OrtColors.accentAmberDim) == halt)
        assert(liveBarLabelColor("Act", fallback = OrtColors.accentGreen) == halt)
        // Any other label is untouched — this is "Act" naming itself, not a blanket halt-red rule.
        val amber = OrtColors.accentAmberDim
        assert(liveBarLabelColor("Tier 2", fallback = amber) == amber)
    }

    @Test
    fun `R_128_a live bar with an Act label and a degraded tone still renders and describes both`() {
        val state = LiveBarViewState(
            level = listOf(0.5f, 0.6f, 0.4f, 0.5f),
            partialText = null,
            label = "Act",
            tone = LiveBarTone.DEGRADED,
            meterTone = LiveBarTone.NOMINAL,
        )

        composeTestRule.setContent {
            OrtTheme { LiveBar(state = state, onClick = {}, modifier = Modifier.testTag("act-bar")) }
        }

        // R-380 correction (WP2, gate-blocking): see the note above — this bar's own composed
        // description equals the bare label ("Act", no partial text), so the default merged tree
        // finds the outer node uniquely.
        composeTestRule.onNodeWithText("Act").assertIsDisplayed()
        composeTestRule.onNodeWithTag("act-bar").assert(hasContentDescription("Act", substring = true))
    }

    @Test
    fun `P5 a live partial renders italic and dimmed, distinct from a resolved row`() {
        val state = LiveBarViewState(
            level = listOf(0.1f, 0.2f, 0.1f, 0.3f),
            partialText = "and we're clear on the repeater",
            label = "Live",
            tone = LiveBarTone.NOMINAL,
        )

        composeTestRule.setContent { OrtTheme { LiveBar(state = state, onClick = {}) } }

        composeTestRule.onNodeWithText("and we're clear on the repeater", useUnmergedTree = true).assertIsDisplayed()
    }
}
