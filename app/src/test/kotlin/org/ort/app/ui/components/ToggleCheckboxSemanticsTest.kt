package org.ort.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/**
 * P32 follow-up — split out of `ControlsTest.kt` (detekt's own `LargeClass` finding, the same
 * reason `HeaderTouchTargetTest.kt` exists as its own file).
 *
 * Gate-blocking regression found on `main`: `LogFilterSheetTest.R_042`,
 * `SearchFiltersSheetTest.R_061/R_500/R_064`, `ModelsScreenProseDigestTest.E2_F05`. A first fix
 * for [ControlsTest]'s own `R_380` gap (no `ContentDescription` on `CheckboxRow`/`ToggleRow`'s
 * own clickable node — real-device uiautomator evidence: `content-desc=""`) used
 * `clearAndSetSemantics`, the pattern every *button* in `Controls.kt` uses for the identical
 * shape ([NavRow]/[TextAction]/[FilterChip]). That regressed the four tests named above:
 * `clearAndSetSemantics` wipes every descendant's own semantics from the *merged* tree wholesale,
 * and unlike a button, a checkbox/toggle row's own label and count are each independently queried
 * elsewhere by real callers (`onNodeWithText("Confirmed")`, `onNodeWithText("291")`), and its
 * checked state is read by real callers too — a button has neither contract.
 *
 * The fix landed instead ([CheckboxRow]/[ToggleRow]'s own doc comments) is a plain
 * `Modifier.semantics(mergeDescendants = true)` merge boundary — it adds a [contentDescription]
 * without erasing anything `.toggleable()`'s own `toggleableState`/`role`/click action or a
 * descendant `Text` already contributes.
 *
 * This test guards **both** regression directions on the same node, not just one — a plain
 * `.toggleable()` alone (the original bug: real-device `content-desc=""`) still passes
 * `assertIsOn`/`assertIsOff` and `onNodeWithText` in Robolectric (proven directly while writing
 * this: reverting to it leaves this test green, which is exactly why the `ContentDescription`
 * check below — not `onNodeWithText`/`assertIsOn` alone — is what catches that direction), and
 * `clearAndSetSemantics` fails the state/text checks the way the four consumer tests above did.
 * Both directions were reproduced directly (production code reverted each way in turn, this test
 * run, restored) before this file was written down.
 */
@RunWith(RobolectricTestRunner::class)
class ToggleCheckboxSemanticsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `P32 a checkbox and a toggle each expose their label, description and checked state on the same node`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    CheckboxRow(
                        label = "Confirmed",
                        checked = true,
                        onCheckedChange = {},
                        count = "291",
                        modifier = Modifier.testTag("p32-checkbox"),
                    )
                    ToggleRow(
                        label = "Usage and quality (tier 1)",
                        checked = false,
                        onCheckedChange = {},
                        modifier = Modifier.testTag("p32-toggle"),
                    )
                }
            }
        }

        fun descriptionOf(tag: String) = composeTestRule.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString()

        // The real-device finding this whole unit chased: a non-empty ContentDescription on the
        // exact node carrying the toggle/checkbox action — not merely text somewhere beneath it.
        assert(descriptionOf("p32-checkbox")?.contains("Confirmed") == true) {
            "expected 'p32-checkbox' to carry a ContentDescription containing 'Confirmed', got " +
                descriptionOf("p32-checkbox")
        }
        assert(descriptionOf("p32-toggle")?.contains("Usage and quality (tier 1)") == true) {
            "expected 'p32-toggle' to carry a ContentDescription containing 'Usage and quality " +
                "(tier 1)', got ${descriptionOf("p32-toggle")}"
        }

        // Findable by its own visible label and count, on the default MERGED tree —
        // `clearAndSetSemantics` could not do this; a plain merge boundary can, because it
        // aggregates descendant `Text` rather than replacing it.
        composeTestRule.onNodeWithText("Confirmed").assertIsOn()
        composeTestRule.onNodeWithText("291").assertExists()
        composeTestRule.onNodeWithText("Usage and quality (tier 1)").assertIsOff()
    }
}
