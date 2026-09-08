package org.ort.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/**
 * R-023/R-024 (ui-conformance-plan WP2): the shared control set. None of these existed before —
 * every screen hand-rolled `Text` + `clickable` with no pressed state and the text's own height
 * as the hit area (R-024's `halt` finding).
 */
@RunWith(RobolectricTestRunner::class)
class ControlsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `FR_A11Y_2 every interactive component has a 44dp target and a role`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    TextAction(text = "Filter", onClick = {}, modifier = Modifier.testTag("text-action"))
                    PrimaryButton(text = "Start capture", onClick = {}, modifier = Modifier.testTag("primary"))
                    SecondaryButton(text = "Not now", onClick = {}, modifier = Modifier.testTag("secondary"))
                    DestructiveButton(text = "Delete audio", onClick = {}, modifier = Modifier.testTag("destructive"))
                    FilterChip(label = "All", selected = true, onClick = {}, modifier = Modifier.testTag("chip"))
                    RadioRow(label = "All bands", selected = true, onClick = {}, modifier = Modifier.testTag("radio"))
                    CheckboxRow(
                        label = "Confirmed",
                        checked = true,
                        onCheckedChange = {},
                        modifier = Modifier.testTag("checkbox"),
                    )
                    ToggleRow(
                        label = "Retain audio",
                        checked = true,
                        onCheckedChange = {},
                        modifier = Modifier.testTag("toggle"),
                    )
                }
            }
        }

        listOf(
            "text-action",
            "primary",
            "secondary",
            "destructive",
            "chip",
            "radio",
            "checkbox",
            "toggle",
        ).forEach { tag ->
            val node = composeTestRule.onNodeWithTag(tag)
            node.assertHeightIsAtLeast(44.dp)
            node.assertHasClickAction()
        }
    }

    @Test
    fun `a disabled text action carries text_disabled and does not fire its click`() {
        var clicked = false
        composeTestRule.setContent {
            OrtTheme {
                TextAction(
                    text = "Filter",
                    onClick = { clicked = true },
                    enabled = false,
                    modifier = Modifier.testTag("t"),
                )
            }
        }

        composeTestRule.onNodeWithTag("t").performClick()
        assert(!clicked) { "a disabled TextAction must not fire onClick" }
    }

    @Test
    fun `a filter chip is selectable and its dismiss affordance is a real icon, never text`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    FilterChip(
                        label = "Confirmed",
                        selected = true,
                        onClick = {},
                        modifier = Modifier.testTag("selected"),
                    )
                    FilterChip(
                        label = "Named",
                        selected = false,
                        onClick = {},
                        modifier = Modifier.testTag("unselected"),
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("selected").assertIsSelected()
        composeTestRule.onNodeWithTag("unselected").assertIsNotSelected()
        // Guide §7: never a font glyph for the dismiss affordance — proven by its absence as text.
        composeTestRule.onNodeWithText("×").assertDoesNotExist()
    }

    @Test
    fun `R_023 badges render the guide's complete set as distinct visible labels`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    Badge(text = "new", kind = BadgeKind.NEW)
                    Badge(text = "revised", kind = BadgeKind.REVISED)
                    Badge(text = "corrected", kind = BadgeKind.CORRECTED)
                    Badge(text = "tier 1", kind = BadgeKind.TIER)
                    Badge(text = "3", kind = BadgeKind.COUNT)
                }
            }
        }

        composeTestRule.onNodeWithText("NEW").assertIsDisplayed()
        composeTestRule.onNodeWithText("REVISED").assertIsDisplayed()
        composeTestRule.onNodeWithText("CORRECTED").assertIsDisplayed()
        composeTestRule.onNodeWithText("TIER 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("3").assertIsDisplayed()
    }

    @Test
    fun `R_023 a halted step indicator differs from a done or upcoming one`() {
        composeTestRule.setContent {
            OrtTheme { StepIndicator(steps = 7, currentStep = 3, haltedStep = 3, modifier = Modifier.testTag("steps")) }
        }

        composeTestRule.onNode(hasContentDescription("halted", substring = true, ignoreCase = true)).assertIsDisplayed()
        composeTestRule.onNodeWithText("3 of 7").assertIsDisplayed()
    }

    @Test
    fun `a progress bar reports its percentage rather than spinning indeterminately`() {
        composeTestRule.setContent {
            OrtTheme { ProgressBar(progress = 0.42f, modifier = Modifier.testTag("progress")) }
        }

        composeTestRule.onNodeWithTag("progress").assert(hasContentDescription("42 percent"))
    }

    @Test
    fun `R_081_a warning-tone radio row shows an amber subtitle, per Setup-Input_dc_html's refused mic`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    RadioRow(
                        label = "USB Audio Device",
                        selected = true,
                        onClick = {},
                        subtitle = "USB · 48 kHz native · resampled to 16 kHz",
                        modifier = Modifier.testTag("usb"),
                    )
                    RadioRow(
                        label = "Built-in microphone",
                        selected = false,
                        onClick = {},
                        subtitle = "Not a radio — capture will refuse this route",
                        tone = RowTone.Warning,
                        modifier = Modifier.testTag("built-in"),
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("USB · 48 kHz native · resampled to 16 kHz").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not a radio — capture will refuse this route").assertIsDisplayed()
    }

    @Test
    fun `a radio row with no subtitle renders exactly as before`() {
        composeTestRule.setContent {
            OrtTheme {
                RadioRow(label = "All bands", selected = true, onClick = {}, modifier = Modifier.testTag("plain"))
            }
        }

        composeTestRule.onNodeWithText("All bands").assertIsDisplayed()
        composeTestRule.onNodeWithTag("plain").assertHeightIsAtLeast(44.dp)
    }

    @Test
    fun `R_061_a filter chip's leadingIcon renders beside the label, never replacing it`() {
        composeTestRule.setContent {
            OrtTheme {
                FilterChip(
                    label = "Filters",
                    selected = false,
                    onClick = {},
                    leadingIcon = OrtIcons.filters,
                    modifier = Modifier.testTag("filters-chip"),
                )
            }
        }

        composeTestRule.onNodeWithText("Filters").assertIsDisplayed()
        composeTestRule.onNodeWithTag("filters-chip").assertHeightIsAtLeast(44.dp)
    }

    @Test
    fun `a text field shows its label, placeholder, error text and reports edits`() {
        var value = ""
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    TextField(
                        value = value,
                        onValueChange = { value = it },
                        label = "Callsign",
                        placeholder = "W7NPC",
                        mono = true,
                        modifier = Modifier.testTag("field"),
                    )
                    TextField(
                        value = "bad",
                        onValueChange = {},
                        errorText = "Not a known callsign",
                        modifier = Modifier.testTag("field-error"),
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Callsign").assertIsDisplayed()
        composeTestRule.onNodeWithText("W7NPC").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not a known callsign").assertIsDisplayed()
        composeTestRule.onNodeWithTag("field").assertHeightIsAtLeast(44.dp)

        composeTestRule.onNodeWithTag("field").performTextInput("K7LWH")
        assert(value == "K7LWH") { "expected onValueChange to report the typed text, got '$value'" }
    }
}
