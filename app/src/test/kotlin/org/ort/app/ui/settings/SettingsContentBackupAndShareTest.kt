package org.ort.app.ui.settings

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * P30 (FR-EXP-3, FR-STO-6, FR-STO-9). [org.ort.app.export.ExportCoordinator.buildPotaActivity]/
 * [org.ort.app.backup.BackupBundleBuilder] all existed and were unit-tested in isolation but had
 * no caller anywhere before this unit — these tests are what makes each one reachable, following
 * [SettingsContentExportAndDebugDumpTest]'s own idiom exactly: assert the real launched [Intent],
 * never a toast's wording.
 *
 * **R-1096:** this class used to also cover "Share the most recent over's audio" — that action
 * moved off this screen entirely (Settings has no open over to share; see
 * [org.ort.app.export.ShareCoordinator]'s own kdoc), so the equivalent real-`ACTION_SEND`-intent
 * proof now lives with the screen that replaced it:
 * `TransmissionDetailContentTest`'s own
 * `R_1096 the header share action shares this exact over's own audio, not a more recent one`.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsContentBackupAndShareTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `AC_169 Export POTA activity launches a real SAF CreateDocument intent naming a real csv file`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalSettingsSaveIoDispatcher provides Dispatchers.Unconfined) {
                OrtTheme {
                    SettingsContent(
                        context = composeTestRule.activity,
                        onDrawer = {},
                        initialScreen = SettingsScreenId.EXPORT,
                    )
                }
            }
        }
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Export POTA activity", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("export-screen-scroll")
            .performScrollToNode(hasTestTag("export-pota-button"))
        composeTestRule.onNodeWithTag("export-pota-button").performClick()

        val started = shadowOf(composeTestRule.activity).nextStartedActivityForResult
        assertNotNull("expected Export POTA activity to actually launch a SAF picker intent", started)
        val intent = started.intent
        assertTrue(Intent.ACTION_CREATE_DOCUMENT == intent.action)
        assertTrue("text/csv" == intent.type)
        val title = intent.getStringExtra(Intent.EXTRA_TITLE)
        assertNotNull(title)
        assertTrue(
            "expected the real ExportCoordinator.suggestedPotaFileName shape, got '$title'",
            title!!.startsWith("ort-pota-tonight-") && title.endsWith(".csv"),
        )
    }

    @Test
    fun `FR_STO_6 Save backup launches a real SAF CreateDocument intent naming a real zip file`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalSettingsSaveIoDispatcher provides Dispatchers.Unconfined) {
                OrtTheme {
                    SettingsContent(
                        context = composeTestRule.activity,
                        onDrawer = {},
                        initialScreen = SettingsScreenId.BACKUP,
                    )
                }
            }
        }
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Save backup", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("backup-screen-scroll").performScrollToNode(hasTestTag("backup-save-button"))
        composeTestRule.onNodeWithTag("backup-save-button").performClick()

        val started = shadowOf(composeTestRule.activity).nextStartedActivityForResult
        assertNotNull("expected Save backup to actually launch a SAF picker intent", started)
        val intent = started.intent
        assertTrue(Intent.ACTION_CREATE_DOCUMENT == intent.action)
        assertTrue("application/zip" == intent.type)
        val title = intent.getStringExtra(Intent.EXTRA_TITLE)
        assertNotNull(title)
        assertTrue(
            "expected the real BackupBundleBuilder.suggestedFileName shape, got '$title'",
            title!!.startsWith("ort-backup-") && title.endsWith(".zip"),
        )
    }

    @Test
    fun `FR_STO_9 Choose a backup file launches a real SAF OpenDocument intent`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalSettingsSaveIoDispatcher provides Dispatchers.Unconfined) {
                OrtTheme {
                    SettingsContent(
                        context = composeTestRule.activity,
                        onDrawer = {},
                        initialScreen = SettingsScreenId.BACKUP,
                    )
                }
            }
        }
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Choose a backup file", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("backup-screen-scroll").performScrollToNode(hasTestTag("restore-pick-button"))
        composeTestRule.onNodeWithTag("restore-pick-button").performClick()

        val started = shadowOf(composeTestRule.activity).nextStartedActivityForResult
        assertNotNull("expected Choose a backup file to actually launch a SAF picker intent", started)
        assertTrue(Intent.ACTION_OPEN_DOCUMENT == started.intent.action)
    }
}
