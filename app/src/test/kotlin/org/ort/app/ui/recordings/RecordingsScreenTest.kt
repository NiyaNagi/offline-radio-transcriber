package org.ort.app.ui.recordings

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/** `Recordings.dc.html` (RC01): [RecordingsScreen] is a pure function of [RecordingsViewState] — no
 * database or context needed to exercise its structure and callbacks. */
@RunWith(RobolectricTestRunner::class)
class RecordingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun stateWith(
        sessions: List<RecordingsSessionRowViewState> = emptyList(),
        selectedFilter: RecordingsFilter = RecordingsFilter.ALL,
        overAudioExceeded: Boolean = false,
    ) = RecordingsViewState(
        headline = "1 session · 1 over · since 1 Jan",
        budgets = RecordingsBudgetsViewState(
            overAudio = OverAudioCardViewState(
                usedBytes = 3_100_000_000L,
                budgetGb = 8,
                fractionUsed = 0.39f,
                exceeded = overAudioExceeded,
            ),
            archive = ArchiveCardViewState(
                enabled = true,
                usedBytes = 11_800_000_000L,
                budgetGb = 60,
                fractionUsed = 0.2f,
                monthlyRateLabel = "about 15 GB a month (estimated)",
                isMeasuredRate = false,
            ),
        ),
        filters = listOf(
            RecordingsFilterChipViewState(RecordingsFilter.ALL, "All", null),
            RecordingsFilterChipViewState(RecordingsFilter.HAS_FAILURES, "Has failures", 0),
            RecordingsFilterChipViewState(RecordingsFilter.LABELLED, "Labelled", 0),
            RecordingsFilterChipViewState(RecordingsFilter.ARCHIVE_REMOVED, "Archive removed", 0),
        ),
        selectedFilter = selectedFilter,
        sessions = sessions,
    )

    private fun row(id: String) = RecordingsSessionRowViewState(
        id = id,
        label = "Wed 10 Sep",
        timeRangeLabel = "21:48 – 06:12",
        durationLabel = "8 h 24 m",
        overCount = 41,
        failedCount = 0,
        labelledCount = 0,
        isLive = false,
        badges = listOf(RecordingBadgeViewState(RecordingBadgeKind.ARCHIVE_KEPT, "raw kept")),
        startedAtUtc = 1_694_000_000_000L,
    )

    @Test
    @Requirement("R-1051")
    fun `a null state renders the loading tag, never the empty-state text`() {
        composeTestRule.setContent {
            OrtTheme {
                RecordingsScreen(
                    state = null,
                    onDrawer = {},
                    onSelectFilter = {},
                    onOpenSession = {},
                    onOpenOverAudioBudget = {},
                    onTurnOffArchive = {},
                )
            }
        }
        composeTestRule.onNodeWithTag(RECORDINGS_LOADING_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("No recordings match this filter.").assertDoesNotExist()
    }

    @Test
    @Requirement("RC01")
    fun `an empty filtered list (real data already loaded) shows the empty state, not loading`() {
        composeTestRule.setContent {
            OrtTheme {
                RecordingsScreen(
                    state = stateWith(sessions = emptyList()),
                    onDrawer = {},
                    onSelectFilter = {},
                    onOpenSession = {},
                    onOpenOverAudioBudget = {},
                    onTurnOffArchive = {},
                )
            }
        }
        composeTestRule.onNodeWithTag(RECORDINGS_LOADING_TEST_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithText("No recordings match this filter.").assertIsDisplayed()
    }

    @Test
    @Requirement("D40", "AC-160")
    fun `an exceeded over-audio budget shows its warning without any tap`() {
        composeTestRule.setContent {
            OrtTheme {
                RecordingsScreen(
                    state = stateWith(overAudioExceeded = true),
                    onDrawer = {},
                    onSelectFilter = {},
                    onOpenSession = {},
                    onOpenOverAudioBudget = {},
                    onTurnOffArchive = {},
                )
            }
        }
        // The whole budgets card is one `clickable` region (`Storage card -> CF03`), which Compose
        // merges for accessibility (a real, correct merge — the card should read as one unit to
        // TalkBack) — so its own descendant text is only reachable via the unmerged tree, the same
        // way this project's other merged rows are inspected.
        composeTestRule.onNodeWithTag(RECORDINGS_OVER_AUDIO_WARNING_TEST_TAG, useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("Over budget · never deleted without you", useUnmergedTree = true)
            .assertExists()
        // The merged card's own accessible text must still carry the warning verbatim — a screen
        // reader that only announces the merged node must not lose it (AC-160).
        composeTestRule.onNodeWithTag(RECORDINGS_BUDGETS_CARD_TEST_TAG)
            .assert(hasText("Over budget · never deleted without you", substring = true))
    }

    @Test
    @Requirement("RC01")
    fun `tapping a session row hands the real session id up through the callback`() {
        var opened: String? = null
        composeTestRule.setContent {
            OrtTheme {
                RecordingsScreen(
                    state = stateWith(sessions = listOf(row("S1"))),
                    onDrawer = {},
                    onSelectFilter = {},
                    onOpenSession = { opened = it },
                    onOpenOverAudioBudget = {},
                    onTurnOffArchive = {},
                )
            }
        }
        composeTestRule.onNodeWithTag("recordings-session-row-S1").performClick()
        assert(opened == "S1") { "expected the row's own id, got $opened" }
    }

    @Test
    @Requirement("RC01")
    fun `tapping a filter chip hands the real filter up through the callback`() {
        var selected: RecordingsFilter? = null
        composeTestRule.setContent {
            OrtTheme {
                RecordingsScreen(
                    state = stateWith(),
                    onDrawer = {},
                    onSelectFilter = { selected = it },
                    onOpenSession = {},
                    onOpenOverAudioBudget = {},
                    onTurnOffArchive = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Has failures 0").performClick()
        assert(selected == RecordingsFilter.HAS_FAILURES) { "expected HAS_FAILURES, got $selected" }
    }

    @Test
    @Requirement("constitution VIII")
    fun `the screen renders at font scale 2-0 without crashing, budgets card and chips still present`() {
        composeTestRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides
                    androidx.compose.ui.unit.Density(
                        density = androidx.compose.ui.platform.LocalDensity.current.density,
                        fontScale = 2.0f,
                    ),
            ) {
                OrtTheme {
                    RecordingsScreen(
                        state = stateWith(sessions = listOf(row("S1"))),
                        onDrawer = {},
                        onSelectFilter = {},
                        onOpenSession = {},
                        onOpenOverAudioBudget = {},
                        onTurnOffArchive = {},
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(RECORDINGS_BUDGETS_CARD_TEST_TAG, useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag(RECORDINGS_FILTER_ROW_TEST_TAG, useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("recordings-session-row-S1", useUnmergedTree = true).assertExists()
    }

    @Test
    @Requirement("D39", "FR-STO-3f")
    fun `the archive turn-off control fires its own callback, never the budget-card one`() {
        var turnedOff = false
        var openedBudget = false
        composeTestRule.setContent {
            OrtTheme {
                RecordingsScreen(
                    state = stateWith(),
                    onDrawer = {},
                    onSelectFilter = {},
                    onOpenSession = {},
                    onOpenOverAudioBudget = { openedBudget = true },
                    onTurnOffArchive = { turnedOff = true },
                )
            }
        }
        composeTestRule.onNodeWithTag(RECORDINGS_ARCHIVE_TOGGLE_TEST_TAG).performClick()
        assert(turnedOff) { "Turn off did not fire its own callback" }
        assert(!openedBudget) { "Turn off must not also fire the card's own onOpenOverAudioBudget" }
    }
}
