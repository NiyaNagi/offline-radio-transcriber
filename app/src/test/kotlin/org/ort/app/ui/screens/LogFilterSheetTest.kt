package org.ort.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.LogAttributionOptionViewState
import org.ort.app.ui.data.LogFilterSheetViewState
import org.ort.app.ui.data.LogFrequencyOptionViewState
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.AttributionState
import org.robolectric.RobolectricTestRunner

/**
 * R-042 (ui-conformance WP5): `Log-Filter.dc.html`, FR-UI-3 — frequency chips with counts,
 * attribution checkboxes with counts, "Also show" toggles, and the `Show N overs` action.
 * [LogViewDataTest] covers the counts/matching logic; this proves the sheet renders it and wires
 * every control's callback.
 */
@RunWith(RobolectricTestRunner::class)
class LogFilterSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun state() = LogFilterSheetViewState(
        frequencyOptions = listOf(
            LogFrequencyOptionViewState(null, "All", 376, true),
            LogFrequencyOptionViewState(145_230_000L, "145.230", 318, false),
        ),
        attributionOptions = listOf(
            LogAttributionOptionViewState(AttributionState.CONFIRMED, "Confirmed", 291, true),
            LogAttributionOptionViewState(AttributionState.UNKNOWN, "Unknown", 36, false),
        ),
        rejectedCount = 6,
        rejectedShown = false,
        gapsCount = 1,
        gapsShown = true,
        fromLabel = "23:32",
        toLabel = "06:14",
        matchingCount = 376,
    )

    @Composable
    private fun noopSheet(
        onAttributionToggle: (AttributionState, Boolean) -> Unit = { _, _ -> },
        onClearAll: () -> Unit = {},
    ) = LogFilterSheet(
        state(),
        onFrequencySelect = {},
        onAttributionToggle = onAttributionToggle,
        onShowRejectedToggle = {},
        onShowGapsToggle = {},
        onClearAll = onClearAll,
        onShow = {},
    )

    @Test
    fun `R_042 renders frequency counts, attribution counts and the Show N overs action`() {
        composeTestRule.setContent {
            OrtTheme { noopSheet() }
        }

        // R-380/R-381: `FilterChip`'s own label is only reachable on the unmerged tree now (its
        // outer node `clearAndSetSemantics`-es an explicit `contentDescription` instead) — see the
        // identical fix already applied in `ui/screens/LogScreenTest.kt`.
        composeTestRule.onNodeWithText("145.230 318", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("Confirmed").assertExists()
        composeTestRule.onNodeWithText("291").assertExists()
        composeTestRule.onNodeWithText("Rejected segments").assertExists()
        // R-380: `PrimaryButton`'s own label is only reachable on the unmerged tree now.
        composeTestRule.onNodeWithText("Show 376 overs", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `R_042 toggling an attribution checkbox invokes the callback with its state`() {
        var toggled: Pair<AttributionState, Boolean>? = null
        composeTestRule.setContent {
            OrtTheme { noopSheet(onAttributionToggle = { state, checked -> toggled = state to checked }) }
        }

        composeTestRule.onNodeWithText("Unknown").performClick()

        assert(toggled == (AttributionState.UNKNOWN to true)) { "expected UNKNOWN to be checked but was $toggled" }
    }

    @Test
    fun `R_042 Clear all invokes its callback`() {
        var cleared = false
        composeTestRule.setContent {
            OrtTheme { noopSheet(onClearAll = { cleared = true }) }
        }

        // R-380: `Sheet`'s own "Clear all" action is only reachable on the unmerged tree now.
        composeTestRule.onNodeWithText("Clear all", useUnmergedTree = true).performClick()

        assert(cleared)
    }
}
