package org.ort.app.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.testing.ORT_COMPOSE_ASYNC_WAIT_TIMEOUT_MILLIS
import org.ort.app.ui.theme.OrtTheme
import org.ort.data.OrtDatabase
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * R-090 round 3 (WP3's host find): [SettingsContent.initialScreen] lets a caller land directly on
 * one sub-screen — `Assets` (Now's "Install a model", Setup S12's `Install`), `Storage` (F6's
 * "Free space"), `Rig` (F9's "Reconnect"), `Capture` (N06's `Adjust`) — instead of always opening
 * the `Settings` root first. `null` (the default) keeps the pre-existing root-first behaviour.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun ComposeContentTestRule.waitUntilTextExists(
        text: String,
        timeoutMillis: Long = ORT_COMPOSE_ASYNC_WAIT_TIMEOUT_MILLIS,
    ) {
        waitUntil(timeoutMillis) {
            runCatching { onNodeWithText(text, substring = true).assertExists() }.isSuccess
        }
    }

    /** See `AC_144`'s own doc comment: pays `OrtDatabase.create`'s one-time, load-sensitive real
     * disk I/O synchronously, before any timed `waitUntil` starts, instead of racing it inside one. */
    private fun warmDatabase() {
        OrtDatabase.create(context)
    }

    @Test
    @Requirement("R-090")
    fun `R_090 no initialScreen opens the Settings root, unchanged from before this parameter existed`() {
        composeTestRule.setContent { OrtTheme { SettingsContent(context = context, onDrawer = {}) } }

        composeTestRule.waitUntilTextExists("RECORDS")
        composeTestRule.onNodeWithText("RECORDS").assertExists()
    }

    @Test
    @Requirement("R-090")
    fun `R_090 initialScreen STORAGE lands directly on Storage, never rendering the root section labels`() {
        composeTestRule.setContent {
            OrtTheme { SettingsContent(context = context, onDrawer = {}, initialScreen = SettingsScreenId.STORAGE) }
        }

        composeTestRule.waitUntilTextExists("Storage and retention")
        composeTestRule.onNodeWithText("Storage and retention").assertExists()
        composeTestRule.onNodeWithText("RECORDS").assertDoesNotExist()
    }

    @Test
    @Requirement("R-090")
    fun `R_090 initialScreen ASSETS lands directly on Models and lexicon`() {
        composeTestRule.setContent {
            OrtTheme { SettingsContent(context = context, onDrawer = {}, initialScreen = SettingsScreenId.ASSETS) }
        }

        composeTestRule.waitUntilTextExists("Models and lexicon")
        composeTestRule.onNodeWithText("Models and lexicon").assertExists()
    }

    @Test
    @Requirement("R-090")
    fun `R_090 initialScreen RIG lands directly on the Rig screen`() {
        composeTestRule.setContent {
            OrtTheme { SettingsContent(context = context, onDrawer = {}, initialScreen = SettingsScreenId.RIG) }
        }

        composeTestRule.waitUntilTextExists("No radio configured")
        composeTestRule.onNodeWithText("No radio configured").assertExists()
        composeTestRule.onNodeWithText("RECORDS").assertDoesNotExist()
    }

    @Test
    @Requirement("R-090")
    fun `R_090 initialScreen CAPTURE lands directly on Input and level`() {
        composeTestRule.setContent {
            OrtTheme { SettingsContent(context = context, onDrawer = {}, initialScreen = SettingsScreenId.CAPTURE) }
        }

        composeTestRule.waitUntilTextExists("Input and level")
        composeTestRule.onNodeWithText("Input and level").assertExists()
        composeTestRule.onNodeWithText("RECORDS").assertDoesNotExist()
    }

    // Split into two tests (round 6 fix-up): a single `ComposeContentTestRule` refuses a second
    // `setContent` call within one test ("Cannot call setContent twice per test!") — each half of
    // the double-header regression this covers needs its own fresh composition regardless, so this
    // is not a loss of coverage, just two `@Test` functions instead of one two-act test body.

    @Test
    @Requirement("R-090")
    fun `R_090_settings_root_has_one_header_and_sub_screens_one_drill_in_header — the root draws exactly one`() {
        // Round 6 (WP3's own smoke test find): the root draws its own `ScreenHeader` again
        // (`SettingsRootScreen`'s own doc comment says why) — exactly one "Open navigation" node,
        // never zero (that would be R-130's bug back) and never two (the double-header this fixes).
        composeTestRule.setContent { OrtTheme { SettingsContent(context = context, onDrawer = {}) } }
        composeTestRule.waitUntilTextExists("RECORDS")
        composeTestRule.onAllNodesWithContentDescription("Open navigation").assertCountEquals(1)
    }

    @Test
    @Requirement("FR-CAP-12")
    fun `E2_F08 initialScreen MODE lands directly on CF11, Capture mode`() {
        composeTestRule.setContent {
            OrtTheme { SettingsContent(context = context, onDrawer = {}, initialScreen = SettingsScreenId.MODE) }
        }

        composeTestRule.waitUntilTextExists("Capture mode")
        composeTestRule.onNodeWithText("Capture mode").assertExists()
        composeTestRule.onNodeWithText("RECORDS").assertDoesNotExist()
    }

    @Test
    @Requirement("R-090")
    fun `R_090_settings_root_has_one_header_and_sub_screens_one_drill_in_header — a sub-screen's own header`() {
        // A sub-screen entered directly via `initialScreen` (WP3's own real entry points) draws
        // only its `DrillInHeader` back chevron — no `ScreenHeader`/drawer icon of its own, since
        // this composable no longer draws one outside the root.
        composeTestRule.setContent {
            OrtTheme { SettingsContent(context = context, onDrawer = {}, initialScreen = SettingsScreenId.STORAGE) }
        }
        composeTestRule.waitUntilTextExists("Storage and retention")
        composeTestRule.onNodeWithContentDescription("Back to Settings").assertExists()
        composeTestRule.onAllNodesWithContentDescription("Open navigation").assertCountEquals(0)
    }

    /**
     * WPR2 (AC-144): "toggles default off and the screen reappears on a second upload rather than
     * proceeding on a remembered choice" — proven end to end through the real `SettingsContent`
     * wiring (not just the stateless `FieldReportConsentScreen` composable in isolation, which
     * `SettingsDiagnosticsScreenTest` already covers): toggle `Screen frames` on, `Cancel`, reopen,
     * and find it off again — all in one composition, since this rule refuses a second `setContent`.
     *
     * Gate fallout (P36/R-1128 batch, register): the `waitUntil` below timed out under a six-worker
     * gate on a loaded machine — passed in isolation with `--rerun-tasks`, so this was contention,
     * not a behavioural regression. Investigated rather than papered over with a longer timeout
     * (`ORT_COMPOSE_ASYNC_WAIT_TIMEOUT_MILLIS`'s own doc comment already names this exact test as
     * the reason it was raised once before, R-1043): `Send field report…` opens the consent screen,
     * whose `LaunchedEffect` calls the real `FieldReportBundleBuilder.preview()`, which renders
     * every one of `FieldReportBundleSpec.ungatedFiles` — including `CountsJsonProducer`, reused
     * from `DiagnosticsBundleSpec.files`, which calls `OrtDatabase.create(context)`. Nothing in this
     * test class opens a database before that point, so this was the **first** `OrtDatabase.create`
     * call in the test's own fresh Robolectric sandbox — real, synchronous disk I/O
     * (`buildAndInitialize`'s own `runBlocking`: WAL mode, the hand-written schema, the busy-timeout
     * pragma), ordinarily a few milliseconds but load-sensitive exactly the way
     * `BannerClosingBorderTest`'s own R-1080 fix documents for a different lazily-opened database.
     * [warmDatabase] pays that one-time cost synchronously, before the timed wait starts, rather
     * than racing it inside a 15 s wall-clock window — the same "move the real I/O out of the
     * timed wait" shape as that fix, applied here because `CountsJsonProducer` cannot be routed
     * around the way that fix routed around `FailureSignalsPolling`'s query with `sessionId = null`
     * (every ungated file, including this one, is unconditional). Deliberately not closed: this
     * class does not otherwise manage an `OrtDatabase` lifecycle (no `ortComposeTestRule`), and
     * closing it from inside this method, before `composeTestRule`'s own teardown fires, would
     * reproduce the exact "close while composition still live" race `OrtComposeTestRule.kt`'s own
     * doc comment describes — each Robolectric test method gets its own fresh, discarded sandbox
     * regardless, the same accepted shape `app/build.gradle.kts`'s own poison-hunting comments
     * already document for several `*Polling`/`*Runner` objects that never close what they open.
     */
    @Test
    @Requirement("AC-144")
    fun `AC_144 a category toggled on and cancelled is off again the next time the consent screen opens`() {
        warmDatabase()
        composeTestRule.setContent {
            OrtTheme { SettingsContent(context = context, onDrawer = {}, initialScreen = SettingsScreenId.DIAGNOSTICS) }
        }
        composeTestRule.waitUntilTextExists("Send field report…")

        composeTestRule.onNode(hasScrollAction())
            .performScrollToNode(hasContentDescription("Send field report…"))
        composeTestRule.onNodeWithContentDescription("Send field report…").performClick()
        composeTestRule.waitUntil(ORT_COMPOSE_ASYNC_WAIT_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithTag(FIELD_REPORT_TOGGLE_SCREEN_FRAMES_TEST_TAG).fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNode(hasScrollAction())
            .performScrollToNode(hasTestTag(FIELD_REPORT_TOGGLE_SCREEN_FRAMES_TEST_TAG))
        composeTestRule.onNodeWithTag(FIELD_REPORT_TOGGLE_SCREEN_FRAMES_TEST_TAG).performClick()
        composeTestRule.onNodeWithTag(FIELD_REPORT_TOGGLE_SCREEN_FRAMES_TEST_TAG).assertIsOn()

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Cancel"))
        composeTestRule.onNodeWithContentDescription("Cancel").performClick()
        composeTestRule.waitUntilTextExists("Send field report…")
        composeTestRule.onNode(hasScrollAction())
            .performScrollToNode(hasContentDescription("Send field report…"))
        composeTestRule.onNodeWithContentDescription("Send field report…").performClick()
        composeTestRule.waitUntil(ORT_COMPOSE_ASYNC_WAIT_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithTag(FIELD_REPORT_TOGGLE_SCREEN_FRAMES_TEST_TAG).fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeTestRule.onNodeWithTag(FIELD_REPORT_TOGGLE_SCREEN_FRAMES_TEST_TAG).assertIsOff()
    }

    /**
     * P27 (NFR-6d, AC-167): end-to-end through the real [SettingsContent] wiring — from the Settings
     * root, under "About" (where `SettingsRootScreen`'s own appended row lives), to the real
     * [SettingsLicensesScreen], and into one notice's own full text. Never touches `:net` anywhere
     * on this path — the screen reads only `Context.assets`.
     */
    @Test
    @Requirement("AC-167")
    fun `AC_167 Settings root to About's Licences row opens the real licence-notices screen`() {
        composeTestRule.setContent { OrtTheme { SettingsContent(context = context, onDrawer = {}) } }
        val licencesRowDescription = "Licences. Third-party notices · read offline"
        composeTestRule.waitUntil(ORT_COMPOSE_ASYNC_WAIT_TIMEOUT_MILLIS) {
            runCatching { composeTestRule.onNodeWithContentDescription(licencesRowDescription).assertExists() }
                .isSuccess
        }

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasContentDescription(licencesRowDescription))
        composeTestRule.onNodeWithContentDescription(licencesRowDescription).performClick()

        composeTestRule.waitUntilTextExists("Third-party licences")
        composeTestRule.onNodeWithText("Third-party licences").assertExists()
        // Every Settings sub-screen carries the "‹ Settings" back chevron (`TourStepsTest`'s own
        // generic check for every `settingsScreen` drill-in relies on this too) — proven here for
        // real, not merely asserted by that generic check.
        composeTestRule.onNodeWithContentDescription("Back to Settings").assertExists()
    }

    @Test
    @Requirement("AC-167")
    fun `AC_167 initialScreen LICENSES lands directly on the licence-notices screen`() {
        composeTestRule.setContent {
            OrtTheme { SettingsContent(context = context, onDrawer = {}, initialScreen = SettingsScreenId.LICENSES) }
        }

        composeTestRule.waitUntilTextExists("Third-party licences")
        composeTestRule.onNodeWithText("Third-party licences").assertExists()
        composeTestRule.onNodeWithText("RECORDS").assertDoesNotExist()
    }

    @Test
    @Requirement("AC-167")
    fun `AC_167 tapping a notice opens its full, real licence text, readable without touching net`() {
        composeTestRule.setContent {
            OrtTheme { SettingsContent(context = context, onDrawer = {}, initialScreen = SettingsScreenId.LICENSES) }
        }
        val whisperRowDescription = "Whisper tiny.en. MIT License"
        composeTestRule.waitUntil(ORT_COMPOSE_ASYNC_WAIT_TIMEOUT_MILLIS) {
            runCatching { composeTestRule.onNodeWithContentDescription(whisperRowDescription).assertExists() }
                .isSuccess
        }
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasContentDescription(whisperRowDescription))

        composeTestRule.onNodeWithContentDescription(whisperRowDescription).performClick()

        composeTestRule.waitUntilTextExists("Copyright (c) 2022 OpenAI")
        composeTestRule.onNodeWithText("Copyright (c) 2022 OpenAI", substring = true).assertExists()
        composeTestRule.onNodeWithContentDescription("Back to Licences").assertExists()
    }
}
