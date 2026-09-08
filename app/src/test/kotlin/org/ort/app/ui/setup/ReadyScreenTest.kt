package org.ort.app.ui.setup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/** R-080..R-084 (ui-conformance-plan WP9) — `Setup-Done.dc.html` (S12). */
@RunWith(RobolectricTestRunner::class)
class ReadyScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `R_080 Start capture invokes its callback`() {
        var started = false
        val rows = listOf(ReadyRow("Input", "USB Audio Device", ok = true, statusText = "verified", actionLabel = null))
        composeTestRule.setContent {
            OrtTheme { ReadyScreen(state = ReadyViewState(rows), onStartCapture = { started = true }) }
        }

        composeTestRule.onNodeWithTag("setup-ready-start-capture").performClick()
        assert(started)
    }

    @Test
    fun `R_080 an amber row's Fix action invokes onAction, not onStartCapture`() {
        var fixed = false
        val rows = listOf(
            ReadyRow(
                "Overnight",
                "Battery exemption skipped",
                ok = false,
                statusText = null,
                actionLabel = "Fix",
                onAction = { fixed = true },
            ),
        )
        composeTestRule.setContent {
            OrtTheme { ReadyScreen(state = ReadyViewState(rows), onStartCapture = {}) }
        }

        composeTestRule.onNodeWithTag("setup-ready-row-overnight").assertIsDisplayed()
        composeTestRule.onNodeWithText("Fix").performClick()
        assert(fixed)
    }
}
