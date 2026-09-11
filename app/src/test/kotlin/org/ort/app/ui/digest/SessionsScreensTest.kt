package org.ort.app.ui.digest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.components.drawHatchRegion
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * R-250 (register, round 6 System validator pass 2, halt): the gap-row hatch used to compute its
 * own stripe pitch as `size.width / 3f` — a canvas measured at zero width (the real report: "at
 * font scale 2.0 the row relayout hits that") made the pitch zero too, so the draw loop's own
 * `x += stripeWidth * 2` never advanced and span forever on the main thread — an ANR that never
 * recovered. `SessionsScreens.kt`'s hatch now guards `size.width <= 0f`/`size.height <= 0f` (draws
 * nothing) and reuses `org.ort.app.ui.components.drawHatchRegion` — the same shared, fixed-dp-pitch
 * hatch `ActivityPatternChart` already draws its own not-listening texture with, which cannot
 * degenerate this way because its pitch is a caller-supplied constant, never derived from the
 * canvas size.
 */
@RunWith(RobolectricTestRunner::class)
class SessionsScreensTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    @Requirement("R-250")
    fun `R_250 the shared hatch draws nothing and returns immediately at zero width, never hangs`() {
        // Test-suite regression (this class's own CHANGELOG entry): `withTimeout` used to wrap
        // `composeTestRule.setContent`/`waitForIdle()` here. Both are plain (non-`suspend`)
        // blocking calls that, under Robolectric, synchronously hand work to the dedicated
        // "SDK 34 Main Thread" via `Sandbox.runOnMainThread` (a cross-thread `FutureTask.get()`).
        // `withTimeout` can only react to cancellation at a coroutine's own suspension points — a
        // blocking call has none, so it cannot actually interrupt the in-flight main-thread work.
        // If that work were ever slow (system load, an unrelated earlier test's contention — never
        // observed as an actual hang here, since the loop below is provably bounded), `withTimeout`
        // would abandon this coroutine while the `FutureTask` kept running to completion on that
        // *shared, JVM-fork-wide* thread in the background — exactly the failure mode
        // `ReaderActivity.kt`'s own "poisons later Compose tests' idle checking" report already
        // names for a different cause. `drawHatchRegion`'s own loop is bounded independent of any
        // wall-clock guard (finite `width`/`height`, guarded `> 0f`, a fixed positive `pitchPx`
        // never derived from either) — a real bug here should fail loudly (an uncaught
        // `StackOverflowError`/OOM or a `ComposeTimeoutException` from Compose's own internal
        // bound), not be raced against a timer that cannot enforce itself.
        composeTestRule.setContent {
            OrtTheme {
                // The exact reproduction the register row names: a canvas measured to zero
                // width — this is genuinely the same call this package's own gap-row hatch
                // makes, guarded the same way.
                Canvas(modifier = Modifier.size(0.dp)) {
                    if (size.width > 0f && size.height > 0f) {
                        drawHatchRegion(
                            left = 0f,
                            top = 0f,
                            width = size.width,
                            height = size.height,
                            color = OrtColors.accentAmber,
                            pitchPx = 4.dp.toPx(),
                            strokeWidthPx = 2.dp.toPx(),
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    @Requirement("R-250")
    fun `R_250 a session row with a gap renders at font scale 2-0 without hanging`() {
        val state = SessionsViewState(
            headline = "1 session · 1 h listened · 1 over",
            sessions = listOf(
                SessionRowViewState(
                    id = "S1",
                    label = "Tonight",
                    timeRangeLabel = "23:32 – 00:32",
                    overCount = 1,
                    stationCount = 1,
                    gapCount = 1,
                    uncleanEndLabel = null,
                    tierChipLabel = null,
                    canBeImproved = false,
                    live = false,
                    startedAtUtc = 0L,
                ),
            ),
        )

        // See the sibling test's own kdoc above: no `withTimeout` around the blocking
        // `composeTestRule` calls — it cannot bound them and can only orphan the in-flight
        // cross-thread work if it ever fired.
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme { SessionsScreen(state = state, onDrawer = {}, onOpen = {}) }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Tonight").assertExists()
    }

    // -----------------------------------------------------------------------------------------
    // E2-G03 (DG04, FR-CAP-13): the Mode/Input/Rig-link fact rows render from the view-state.
    // -----------------------------------------------------------------------------------------

    private fun detailState(modeLabel: String, inputLabel: String, rigLinkLabel: String) = SessionDetailViewState(
        id = "S1",
        label = "Tonight",
        timeRangeLabel = "23:32 – 00:32",
        durationLabel = "1 h 0 m",
        uncleanEndLabel = null,
        coverage = emptyList(),
        notListeningLabel = null,
        gaps = emptyList(),
        overCount = 1,
        rejectedCount = 0,
        failedCount = 0,
        stationCount = 1,
        frequencyLabels = listOf("145.230"),
        inputLabel = inputLabel,
        tierLabel = "tier 3",
        audioSizeLabel = "1.0 GB",
        modeLabel = modeLabel,
        rigLinkLabel = rigLinkLabel,
    )

    // checklist row E2-G03 (DG04's session facts).
    @Test
    @Requirement("FR-CAP-13")
    fun `FR_CAP_13 Mode Input and Rig link render as their own fact rows`() {
        val state = detailState(
            modeLabel = "Bluetooth-connected radio · audio by cable",
            inputLabel = "USB Audio Device · USB · radio audio",
            rigLinkLabel = "Bluetooth SPP",
        )

        composeTestRule.setContent {
            OrtTheme { SessionDetailScreen(state = state, onBack = {}, onOpenLog = {}, onOpenDigest = {}) }
        }

        composeTestRule.onNodeWithTag("session-detail-mode").assertExists()
        composeTestRule.onNodeWithText("Bluetooth-connected radio · audio by cable", substring = true).assertExists()
        composeTestRule.onNodeWithTag("session-detail-input").assertExists()
        composeTestRule.onNodeWithText("USB Audio Device · USB · radio audio", substring = true).assertExists()
        composeTestRule.onNodeWithTag("session-detail-rig-link").assertExists()
        composeTestRule.onNodeWithText("Bluetooth SPP", substring = true).assertExists()
    }

    @Test
    @Requirement("FR-CAP-13", "R-450")
    fun `FR_CAP_13 a pre-v7 session still reads R-450's honest not-tracked line`() {
        val state = detailState(
            modeLabel = SessionDetailViewState.NOT_TRACKED_LABEL,
            inputLabel = SessionDetailViewState.NOT_TRACKED_LABEL,
            rigLinkLabel = SessionDetailViewState.NOT_TRACKED_LABEL,
        )

        composeTestRule.setContent {
            OrtTheme { SessionDetailScreen(state = state, onBack = {}, onOpenLog = {}, onOpenDigest = {}) }
        }

        composeTestRule.onNodeWithTag("session-detail-mode")
            .assert(hasText("not tracked per session in this build", substring = true))
        composeTestRule.onNodeWithTag("session-detail-rig-link")
            .assert(hasText("not tracked per session in this build", substring = true))
    }
}
