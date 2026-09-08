package org.ort.app.ui.setup

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
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

        composeTestRule.onNodeWithTag("setup-notify-preview").assertIsDisplayed()
        composeTestRule.onNode(hasContentDescription("Capturing", substring = true)).assertIsDisplayed()
        composeTestRule.onNode(hasContentDescription("412 overs", substring = true)).assertIsDisplayed()
        composeTestRule.onNode(hasContentDescription("145.230 and 146.960", substring = true)).assertIsDisplayed()
    }

    /** R-280 (validator pass 3): a ghost, semi-transparent duplicate of the bottom bar's secondary
     * action was reported rendering near the status bar on S03's own screenshot too (`setup/
     * S03-notify@2x.png`) — this asserts exactly one "Skip" exists in the semantics tree, the same
     * proof `MicrophoneScreensTest`'s own `R_220` test already carries for S02b's "Check again".
     * The preview card's own `secondaryActionLabel = "Stop"` (`NotificationCard`, composed as part
     * of this screen's content, not [SetupScaffold]'s bar) uses a different label, so it cannot be
     * mistaken for this one -- confirmed by the assertion below counting "Skip" specifically. */
    @Test
    fun `R_280 exactly one Skip renders, never a ghost duplicate`() {
        composeTestRule.setContent {
            OrtTheme { NotificationsScreen(onAllow = {}, onSkip = {}) }
        }
        composeTestRule.onAllNodesWithText("Skip").assertCountEquals(1)
    }

    /** R-281 (validator pass 3): at font scale 2.0 the closing paragraph's last line and the
     * preview card's third line were clipped by the scroll viewport above the fixed bar
     * (`setup/S03-notify@2x.png`) — `SetupScaffold`'s own shared `weight(1f)` + `verticalScroll`
     * mechanism (its own R-123 doc comment) already covers this screen like every other; this is
     * the per-screen regression proof `SetupScaffoldTest`'s generic version cannot stand in for,
     * matching `WelcomeScreenTest`'s/`InputScreenTest`'s own R-123 tests for S01/S04. */
    @Test
    fun `R_281 the closing footer scrolls clear of the fixed bar at font scale 2_0`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme { NotificationsScreen(onAllow = {}, onSkip = {}) }
            }
        }

        composeTestRule.onNodeWithText(
            "You can skip this. Capture still runs, but you will not see it is running without " +
                "opening the app, and the OS is more likely to end it.",
        ).performScrollTo().assertIsDisplayed()
    }
}
