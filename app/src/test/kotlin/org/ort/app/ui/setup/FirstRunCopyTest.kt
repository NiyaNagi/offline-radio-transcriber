package org.ort.app.ui.setup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.OfflinePromiseCopy
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **AC-203 (P39, D58, FR-ANL-14).** *The FR-ANL-14 privacy sentence appears exactly once in the
 * first-run flow, and no other screen in that flow makes a privacy claim of its own.*
 *
 * This is a cross-screen criterion, so it gets a cross-screen test rather than one assertion hidden
 * inside each screen's own file. The flow used to carry a privacy reassurance on four separate
 * screens and **two of those statements were false** (R-1164, R-1165) — which is the reason the
 * criterion exists at all. The failure mode it guards against is additive and quiet: nobody ever
 * decides to make a false privacy claim, someone adds a reassuring sentence to one more screen.
 *
 * The screens enumerated below are every one [SetupStateMachine.stepFor] can return on a first run.
 * They are listed explicitly rather than derived, so that a screen added tomorrow has to be added
 * here by hand — which is the point at which someone has to think about whether it says anything
 * about privacy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-420dpi")
class FirstRunCopyTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun listenState() = ListenViewState(routes = emptyList(), selectedId = null)

    /** Every screen a first run can show, by the name it is known by in the ladder. */
    private val firstRunScreens: List<Pair<String, @Composable () -> Unit>> = listOf(
        "WELCOME" to { WelcomeScreen(onBegin = {}) },
        "MODE" to { ModeScreen(onChoose = {}) },
        "LISTEN" to {
            ListenScreen(
                state = listenState(),
                actions = ListenActions(
                    onSelect = {},
                    onRefresh = {},
                    onVerify = {},
                    onContinue = {},
                    onTryAgain = {},
                    onChooseAnotherInput = {},
                ),
            )
        },
        "MODELS" to {
            ModelsSetupScreen(
                state = ModelsSetupViewState(rows = emptyList(), wifiOnly = true),
                onDownload = {},
                onToggleWifiOnly = {},
                onContinue = {},
            )
        },
        "READY" to { ReadyScreen(state = ReadyViewState(rows = emptyList()), onStartCapture = {}) },
    )

    /**
     * One `setContent` drives every screen in turn — a Compose rule allows only one, and the
     * alternative (a test per screen) would let a newly added screen quietly have no test at all,
     * which is the exact failure this criterion is about.
     */
    @Test
    @Requirement("FR-ANL-14", "AC-203")
    fun `AC_203 the FR-ANL-14 sentence appears on exactly one screen of the first-run flow`() {
        var index by mutableStateOf(0)
        composeTestRule.setContent { OrtTheme { firstRunScreens[index].second() } }

        val carrying = firstRunScreens.indices.filter { screenIndex ->
            index = screenIndex
            composeTestRule.waitForIdle()
            composeTestRule
                .onAllNodesWithText(OfflinePromiseCopy.WELCOME_PROMISE, substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }.map { firstRunScreens[it].first }

        assert(carrying == listOf("WELCOME")) {
            "exactly one screen may carry the privacy sentence, and it must be Welcome — got $carrying"
        }
    }

    /** The other half of AC-203, and the half R-1164 actually broke: Welcome states it **once**, not
     * once in the header and again in a sheet. Asserted by counting the nodes carrying the sentence,
     * not by looking for its tag, so a second copy pasted in without one is still caught. */
    @Test
    @Requirement("FR-ANL-14", "AC-203")
    fun `AC_203 Welcome states the sentence once, never twice`() {
        composeTestRule.setContent { OrtTheme { WelcomeScreen(onBegin = {}) } }

        val count = composeTestRule
            .onAllNodesWithText(OfflinePromiseCopy.WELCOME_PROMISE, substring = true, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size
        assert(count == 1) { "the FR-ANL-14 sentence must appear exactly once on Welcome, found $count" }
    }

    /**
     * The jurisdiction sheet used to end with *"Everything this app records stays on this device
     * unless you choose to export or share it"* — a second privacy claim, on the one screen already
     * making the approved one, and a **bare no-upload claim of exactly the kind FR-ANL-14 forbids**.
     * It is gone, and this is what keeps it gone.
     */
    @Test
    @Requirement("FR-ANL-14", "AC-203")
    fun `AC_203 the jurisdiction notice makes no privacy claim of its own`() {
        composeTestRule.setContent { OrtTheme { WelcomeScreen(onBegin = {}) } }
        composeTestRule.onNodeWithTag("setup-welcome-jurisdiction-row").performClick()

        FORBIDDEN_FRAGMENTS.forEach { fragment ->
            val found = composeTestRule
                .onAllNodesWithText(fragment, substring = true, ignoreCase = true, useUnmergedTree = true)
                .fetchSemanticsNodes()
            assert(found.isEmpty()) { "the jurisdiction notice must not make its own privacy claim: '$fragment'" }
        }
    }

    private companion object {
        /**
         * The claim the jurisdiction notice used to end with, and no longer may: a bare no-upload
         * statement of exactly the kind FR-ANL-14 forbids, on the one screen already making the
         * approved one.
         *
         * Deliberately one short, specific fragment rather than a general prose filter. The obvious
         * wider net — anything containing "never leave" — matches Welcome's own *What is captured, and
         * what never leaves this phone* action, which is a link label, not a claim, and is correct. A
         * check that cries wolf on a true sentence gets disabled, which is the failure mode after this
         * one (R-1175's own lesson about a guard that is too noisy to survive).
         */
        val FORBIDDEN_FRAGMENTS = listOf("stays on this device unless")
    }
}
