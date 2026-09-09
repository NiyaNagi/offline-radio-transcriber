package org.ort.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.pipeline.capture.RigStatus
import org.robolectric.RobolectricTestRunner

/**
 * R-333 (Log half — register): `OrtNavHost`'s own host `BackHandler` (WP3's) closes the drawer and
 * pops drill-ins, but has no idea `LogContent`'s own L02 filter sheet exists — `LogFilterSheet` is a
 * plain composable, not a `ModalBottomSheet` that would intercept back on its own — so a system back
 * press while the sheet was open fell through to the host and popped Log itself, one level too far.
 *
 * Also covers the coordinator's own screenshot-tour seam: `LogContent.initialSheetOpen` (`NavSeed.kt`
 * named this exact gap in its own doc comment — neither this sheet nor `Search`'s own accepted an
 * initial-open parameter before this) seeds the sheet open on first composition, for a tour step to
 * capture L02 without a real tap on "Filter" — and the R-333 `BackHandler` above must still close a
 * seeded-open sheet exactly as it closes a tapped-open one.
 *
 * A dedicated file, not cases added to `LogAndThreadContentActivityTest`: both tests here need a real
 * (non-`null`) `sessionId` so `LogContent`'s own session-tied `LaunchedEffect` polling loops actually
 * run — that file's own doc comment records why a `sessionId`-carrying case must not share a class
 * with one that keeps `sessionId = null` (an uncancelled polling loop bleeding into a later test in
 * the same Robolectric suite run); two `sessionId`-carrying tests sharing this class is not that risk.
 */
@RunWith(RobolectricTestRunner::class)
class LogContentBackHandlerTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun resetRigStatus() {
        RigStatus.reset()
    }

    @After
    fun resetRigStatusAfter() {
        RigStatus.reset()
    }

    private fun ComposeContentTestRule.waitUntilTextExists(text: String, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis) { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun R_333_log_sheet_back() {
        composeTestRule.setContent {
            OrtTheme {
                LogContent(context = composeTestRule.activity, sessionId = "S1", onOpen = {})
            }
        }

        composeTestRule.onNodeWithText("Filter").performClick()
        composeTestRule.waitUntilTextExists("Filter the log")
        composeTestRule.onNodeWithText("Filter the log").assertExists()

        composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        composeTestRule.waitForIdle()

        // The sheet closed on this one back press...
        composeTestRule.onNodeWithText("Filter the log").assertDoesNotExist()
        // ...and Log itself is still showing — back did not fall through and pop the destination.
        composeTestRule.onNodeWithText("Log").assertExists()
    }

    @Test
    fun R_TOUR_log_sheet_open() {
        composeTestRule.setContent {
            OrtTheme {
                LogContent(
                    context = composeTestRule.activity,
                    sessionId = "S1",
                    onOpen = {},
                    initialSheetOpen = true,
                )
            }
        }

        // Seeded open on first composition — no tap on "Filter" needed.
        composeTestRule.waitUntilTextExists("Filter the log")
        composeTestRule.onNodeWithText("Filter the log").assertExists()

        // The R-333 BackHandler still claims back for a seeded-open sheet, same as a tapped-open one.
        composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Filter the log").assertDoesNotExist()
        composeTestRule.onNodeWithText("Log").assertExists()
    }
}
