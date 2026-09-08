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

/** R-080 (ui-conformance-plan WP9) — `Setup-Notify.dc.html` (S03): `Allow` / `Skip`, skip never
 * gates capture. */
@RunWith(RobolectricTestRunner::class)
class NotificationsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `R_080 Allow and Skip both invoke their own callback`() {
        var allowed = false
        var skipped = false
        composeTestRule.setContent {
            OrtTheme { NotificationsScreen(onAllow = { allowed = true }, onSkip = { skipped = true }) }
        }

        composeTestRule.onNodeWithText("Notifications").assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-notify-allow").performClick()
        assert(allowed)
        composeTestRule.onNodeWithTag("setup-notify-skip").performClick()
        assert(skipped)
    }

    @Test
    fun `R_080 the notification preview card shows the persistent-notification copy`() {
        composeTestRule.setContent {
            OrtTheme { NotificationsScreen(onAllow = {}, onSkip = {}) }
        }

        composeTestRule.onNodeWithText("Capturing · 6:42 · 412 overs").assertIsDisplayed()
    }
}
