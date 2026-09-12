package org.ort.app.ui.settings

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Save file", substring = false).fetchSemanticsNodes().isNotEmpty()
        }

        // `export-screen-scroll` names the screen's own outer, vertical scroll container
        // unambiguously — `SettingsExportScreen.kt`'s own doc comment on that tag explains why a
        // bare `hasScrollAction()` matches two nodes here (the Format row nests a second, horizontal
        // one).
        composeTestRule.onNodeWithTag("export-screen-scroll").performScrollToNode(hasContentDescription("Save file"))
        composeTestRule.onNodeWithText("Save file").performClick()

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
    @Requirement("R-1009")
    fun `WPW Save debug dump launches a real SAF CreateDocument intent naming an NDJSON file`() {
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
            composeTestRule.onAllNodesWithText("Save bundle", substring = false).fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(DEBUG_DUMP_SAVE_TEST_TAG))
        composeTestRule.onNodeWithTag(DEBUG_DUMP_SAVE_TEST_TAG).performClick()

        val started = shadowOf(composeTestRule.activity).nextStartedActivityForResult
        assertNotNull("expected Save debug dump to actually launch a SAF picker intent", started)
        val intent = started.intent
        assertTrue(Intent.ACTION_CREATE_DOCUMENT == intent.action)
        assertTrue("application/x-ndjson" == intent.type)
        val title = intent.getStringExtra(Intent.EXTRA_TITLE)
        assertNotNull(title)
        assertTrue(
            "expected a debug-dump-shaped NDJSON file name, got '$title'",
            title!!.startsWith("debug-dump-") && title.endsWith(".ndjson"),
        )
    }
}
