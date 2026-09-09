package org.ort.app.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.ReaderActivity
import org.ort.app.ui.data.ModelsController
import org.ort.app.ui.data.SharedPreferencesStagedActivationStore
import org.ort.app.ui.data.StagedActivation
import org.ort.lexicon.import.ActiveLexiconRecord
import org.robolectric.RobolectricTestRunner

/**
 * WP11b follow-up, split from [ReaderActivityDestinationSmokeTest] purely to keep that file under
 * detekt's `LargeClass` — the same established pattern `FailureBackHeaderTest.kt`/
 * `FailLexiconScreenTest.kt`/`FailureOverrideScenariosTest.kt` already use for it (`CHANGELOG.md`:
 * "split from ... — detekt's `LargeClass`, this codebase's established pattern for it").
 *
 * Register R-448, FR-AST-4: [ReaderActivityDestinationSmokeTest]'s own `R_448_f21_route` proves F21
 * (`Fail-Asset-Swap`) renders through a real [ReaderActivity] with [org.ort.app.ui.failures.DebugFailureOverride]
 * driving it — the only path that existed when it was written. Now that WP10's own
 * `ModelsController.stagedActivation` guard is on main, this is its real-signal counterpart: a
 * genuine [StagedActivation], written through the real, public
 * [SharedPreferencesStagedActivationStore] (the same store `ModelsController.stageLexiconActivation`/
 * `refreshStagedActivation` themselves read and write — this test seeds it directly rather than
 * reaching for that `internal` convenience function), with no debug override involved anywhere.
 * Proves the whole real chain — [org.ort.app.ui.failures.FailureSignalsPolling]'s poll,
 * [org.ort.app.ui.failures.FailureMapper.map]'s mapping (its own `stagedActivation != null` branch —
 * not gated on [org.ort.pipeline.capture.CaptureState.isCapturing] at all, deliberately: an operator
 * with something staged still deserves to see it whether or not a session happens to be live *right
 * now*, matching [ModelsController.activateStaged]'s own separate, real refusal to force early
 * activation), and [org.ort.app.ui.failures.FailureHost]'s own takeover routing — actually renders
 * F21 from a real signal.
 *
 * [createAndroidComposeRule] with no custom launch `Intent` — the same simpler, non-isolated pattern
 * [org.ort.app.ui.ReaderActivityTest] already establishes — lands on [ReaderDestination.NOW]
 * ([ReaderActivity]'s own `resolveInitialDestination` fallback for an unset `EXTRA_DESTINATION`),
 * exactly the destination this test needs, with no real session id anywhere (this class's own `@Rule`
 * never sets one) — the one real precaution [ReaderActivityDestinationSmokeTest]'s own kdoc names
 * (a *non-null* session id is what starts `OrtNavHost`'s extra, harder-to-cancel polling loops) does
 * not apply here. `ModelsController`'s persisted `StagedActivation` is drained in `@After` —
 * `ModelsController.activateStaged`, the exact real drain `ModelsControllerLexiconTest`'s own
 * `tearDown` already establishes ("draining whatever this test left staged is itself a legitimate
 * call").
 */
@RunWith(RobolectricTestRunner::class)
class AssetSwapRealSignalSmokeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ReaderActivity>()

    @After
    fun drainStagedActivation() {
        runBlocking { ModelsController.activateStaged(composeTestRule.activity) }
    }

    @Test
    fun `R_448_f21_route a real staged activation renders AssetSwap through a real ReaderActivity`() {
        SharedPreferencesStagedActivationStore(composeTestRule.activity).stageLexicon(
            StagedActivation(
                assetId = ModelsController.CALLSIGN_LEXICON_ASSET_ID,
                version = "2026.09",
                stagedAtMillis = 500_000L,
                reason = "a session is live — activating a new callsign lexicon mid-session would " +
                    "change which callsigns read as usual until this session ends (FR-AST-4)",
            ),
            ActiveLexiconRecord(
                assetId = ModelsController.CALLSIGN_LEXICON_ASSET_ID,
                version = "2026.09",
                recordCount = 41_600,
                checksum = "deadbeef",
            ),
        )

        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithTag("failure-asset-swap-screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("failure-asset-swap-screen").assertIsDisplayed()
        // The real `stagedLabel` FailureMapper built from this test's own StagedActivation (never a
        // debug-override literal) — "staged" only appears in that real sentence.
        composeTestRule.onNode(hasText("staged", substring = true)).assertIsDisplayed()
    }
}
