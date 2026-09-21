package org.ort.app.ui.settings

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/**
 * P30 (FR-STO-6, FR-STO-9, AC-170). [SettingsBackupScreen] is a pure function of
 * [SettingsBackupViewState] — polling and real IO stay in `SettingsContent.kt`'s own sub-screen
 * (the ui-conformance-plan builder rule every sibling `Settings*Screen` in this package already
 * follows).
 */
@RunWith(RobolectricTestRunner::class)
class SettingsBackupScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun preview() = SettingsBackupPreviewViewState(
        sessionCount = 6,
        transmissionCount = 214,
        correctionCount = 3,
        audioFileCount = 200,
        sizeLabel = "1.2 GB",
    )

    @Test
    fun `P30 the real preview counts render before Save backup is tapped`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsBackupScreen(
                    state = SettingsBackupViewState(preview = preview()),
                    onBack = {},
                    actions = SettingsBackupActions(),
                )
            }
        }
        composeTestRule.onNodeWithTag("backup-preview").assertExists()
        composeTestRule.onNodeWithText("6 sessions", substring = true).assertExists()
        composeTestRule.onNodeWithText("1.2 GB", substring = true).assertExists()
    }

    @Test
    fun `R_1094 the real station, voiceprint and thread counts render before Save backup is tapped`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsBackupScreen(
                    state = SettingsBackupViewState(
                        preview = preview().copy(stationCount = 12, voiceprintCount = 4, threadCount = 9),
                    ),
                    onBack = {},
                    actions = SettingsBackupActions(),
                )
            }
        }
        composeTestRule.onNodeWithText("12 stations", substring = true).assertExists()
        composeTestRule.onNodeWithText("4 voiceprints", substring = true).assertExists()
        composeTestRule.onNodeWithText("9 threads", substring = true).assertExists()
    }

    @Test
    fun `P30 tapping Save backup invokes the real handler`() {
        var saved = false
        composeTestRule.setContent {
            OrtTheme {
                SettingsBackupScreen(
                    state = SettingsBackupViewState(preview = preview()),
                    onBack = {},
                    actions = SettingsBackupActions(onSaveBackup = { saved = true }),
                )
            }
        }
        composeTestRule.onNodeWithTag("backup-save-button").performClick()
        assert(saved) { "expected Save backup to invoke onSaveBackup" }
    }

    @Test
    fun `P30 with no plan yet, Choose a backup file is the only restore action shown`() {
        var picked = false
        composeTestRule.setContent {
            OrtTheme {
                SettingsBackupScreen(
                    state = SettingsBackupViewState(preview = preview()),
                    onBack = {},
                    actions = SettingsBackupActions(onPickRestoreFile = { picked = true }),
                )
            }
        }
        composeTestRule.onNodeWithTag("restore-plan").assertDoesNotExist()
        composeTestRule.onNodeWithTag("backup-screen-scroll").performScrollToNode(hasTestTag("restore-pick-button"))
        composeTestRule.onNodeWithTag("restore-pick-button").performClick()
        assert(picked) { "expected Choose a backup file to invoke onPickRestoreFile" }
    }

    @Test
    fun `P30 AC_170 a plan with conflicts states the conflict counts, never silently hidden`() {
        val plan = SettingsRestorePlanViewState(
            sessionsToAddCount = 1,
            sessionConflictCount = 1,
            transmissionsToAddCount = 1,
            transmissionConflictCount = 1,
            correctionsToAddCount = 0,
            correctionConflictCount = 1,
            audioToAddCount = 0,
            audioConflictCount = 1,
            hasConflicts = true,
        )
        composeTestRule.setContent {
            OrtTheme {
                SettingsBackupScreen(
                    state = SettingsBackupViewState(preview = preview(), restorePlan = plan),
                    onBack = {},
                    actions = SettingsBackupActions(),
                )
            }
        }
        composeTestRule.onNodeWithTag("restore-conflict-summary").assertExists()
        composeTestRule.onNodeWithTag("restore-confirm-button").assertExists()
        composeTestRule.onNodeWithTag("restore-cancel-button").assertExists()
    }

    @Test
    fun `R_1094 a plan naming stations, voiceprints and threads states those counts too`() {
        val plan = SettingsRestorePlanViewState(
            sessionsToAddCount = 1,
            sessionConflictCount = 0,
            transmissionsToAddCount = 1,
            transmissionConflictCount = 0,
            correctionsToAddCount = 0,
            correctionConflictCount = 0,
            audioToAddCount = 0,
            audioConflictCount = 0,
            hasConflicts = true,
            stationsToAddCount = 3,
            stationConflictCount = 1,
            voiceprintsToAddCount = 2,
            voiceprintConflictCount = 1,
            threadsToAddCount = 5,
            threadConflictCount = 1,
        )
        composeTestRule.setContent {
            OrtTheme {
                SettingsBackupScreen(
                    state = SettingsBackupViewState(preview = preview(), restorePlan = plan),
                    onBack = {},
                    actions = SettingsBackupActions(),
                )
            }
        }
        composeTestRule.onNodeWithText("3 stations", substring = true).assertExists()
        composeTestRule.onNodeWithText("2 voiceprints", substring = true).assertExists()
        composeTestRule.onNodeWithText("5 threads", substring = true).assertExists()
        composeTestRule.onNodeWithText("1 stations", substring = true).assertExists()
        composeTestRule.onNodeWithText("1 voiceprints", substring = true).assertExists()
        composeTestRule.onNodeWithText("1 threads", substring = true).assertExists()
    }

    @Test
    fun `P30 confirming a restore invokes the real handler`() {
        var confirmed = false
        val plan = SettingsRestorePlanViewState(
            sessionsToAddCount = 2, sessionConflictCount = 0, transmissionsToAddCount = 2,
            transmissionConflictCount = 0, correctionsToAddCount = 1, correctionConflictCount = 0,
            audioToAddCount = 1, audioConflictCount = 0, hasConflicts = false,
        )
        composeTestRule.setContent {
            OrtTheme {
                SettingsBackupScreen(
                    state = SettingsBackupViewState(preview = preview(), restorePlan = plan),
                    onBack = {},
                    actions = SettingsBackupActions(onConfirmRestore = { confirmed = true }),
                )
            }
        }
        composeTestRule.onNodeWithTag("backup-screen-scroll").performScrollToNode(hasTestTag("restore-confirm-button"))
        composeTestRule.onNodeWithTag("restore-confirm-button").performClick()
        assert(confirmed) { "expected Restore to invoke onConfirmRestore" }
    }

    @Test
    fun `P30 a restore summary replaces the plan once a restore has actually run`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsBackupScreen(
                    state = SettingsBackupViewState(
                        preview = preview(),
                        restoreSummary = "Added 2 sessions, 2 overs; 1 session already existed and was left alone.",
                    ),
                    onBack = {},
                    actions = SettingsBackupActions(),
                )
            }
        }
        composeTestRule.onNodeWithTag("restore-summary").assertExists()
        composeTestRule.onNodeWithTag("restore-plan").assertDoesNotExist()
        composeTestRule.onNodeWithTag("restore-pick-button").assertDoesNotExist()
    }
}
