package org.ort.app.ui.settings

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * WPW (register R-1009 follow-up, WPX's own report): [ExportCoordinator]/[DebugDumpBuilder] existed
 * and were tested, but nothing called either — `SettingsExportScreen.onSaveFile` and
 * `SettingsDiagnosticsScreen`'s own bundle actions defaulted to no-ops. Asserts the state that
 * survives a real tap — the launcher actually invoked, with the right MIME type and a real,
 * format-correct suggested file name — never a toast's wording (this file's own working agreement).
 *
 * `createAndroidComposeRule<ComponentActivity>()`, not the bare `createComposeRule()`
 * `SettingsContentTest.kt` otherwise uses — [rememberLauncherForActivityResult] needs a real
 * `ComponentActivity` behind it to register against, and this suite needs that same activity
 * afterward to read back the `Intent` Robolectric's own shadow recorded for it.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsContentExportAndDebugDumpTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Requirement("R-1009")
    fun `WPW Save file launches a real SAF CreateDocument intent naming the real suggested export file`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsContent(
                    context = composeTestRule.activity,
                    onDrawer = {},
                    initialScreen = SettingsScreenId.EXPORT,
                )
            }
        }
        // R-1045(b): the button's own real text now reads `Save file · <filename> · <size>` (the
        // artboard's own shape) rather than the bare word — every match below is `substring = true`
        // for that reason, never an exact match on "Save file" alone.
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Save file", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // `export-screen-scroll` names the screen's own outer, vertical scroll container
        // unambiguously — `SettingsExportScreen.kt`'s own doc comment on that tag explains why a
        // bare `hasScrollAction()` matches two nodes here (the Format row nests a second, horizontal
        // one).
        composeTestRule.onNodeWithTag("export-screen-scroll")
            .performScrollToNode(hasContentDescription("Save file", substring = true))
        composeTestRule.onNodeWithText("Save file", substring = true).performClick()

        val started = shadowOf(composeTestRule.activity).nextStartedActivityForResult
        assertNotNull("expected Save file to actually launch a SAF picker intent", started)
        val intent = started.intent
        assertTrue(Intent.ACTION_CREATE_DOCUMENT == intent.action)
        assertTrue("*/*" == intent.type)
        val title = intent.getStringExtra(Intent.EXTRA_TITLE)
        assertNotNull("expected a real suggested file name, never a null title", title)
        assertTrue(
            "expected the real ExportCoordinator.suggestedFileName shape, got '$title'",
            title!!.startsWith("ort-export-tonight-") && title.endsWith(".adi"),
        )
    }

    @Test
    @Requirement("FR-OBS-3")
    fun `WPDUMP Save launches a real SAF CreateDocument intent naming one unified zip`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsContent(
                    context = composeTestRule.activity,
                    onDrawer = {},
                    initialScreen = SettingsScreenId.DIAGNOSTICS,
                )
            }
        }
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithTag(LOCAL_SAVE_SAVE_BUTTON_TEST_TAG).fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(LOCAL_SAVE_SAVE_BUTTON_TEST_TAG))
        composeTestRule.onNodeWithTag(LOCAL_SAVE_SAVE_BUTTON_TEST_TAG).performClick()

        val started = shadowOf(composeTestRule.activity).nextStartedActivityForResult
        assertNotNull("expected Save to actually launch a SAF picker intent", started)
        val intent = started.intent
        assertTrue(Intent.ACTION_CREATE_DOCUMENT == intent.action)
        assertTrue("application/zip" == intent.type)
        val title = intent.getStringExtra(Intent.EXTRA_TITLE)
        assertNotNull(title)
        assertTrue(
            "expected the unified ort-debug-dump-shaped zip file name, got '$title'",
            title!!.startsWith("ort-debug-dump-") && title.endsWith(".zip"),
        )
    }
}
