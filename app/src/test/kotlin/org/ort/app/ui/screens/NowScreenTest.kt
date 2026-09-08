package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.CanGetBetterRow
import org.ort.app.ui.data.EarlierNightRow
import org.ort.app.ui.data.MissingModelFacts
import org.ort.app.ui.data.NowStationRow
import org.ort.app.ui.data.NowStationsSection
import org.ort.app.ui.data.NowViewState
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.AttributionState
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * The "Now" home (ui-conformance-plan WP4, R-030/R-033/R-036/R-037; `Main.dc.html`,
 * `Now-Idle.dc.html`, `Now-First.dc.html`).
 */
@RunWith(RobolectricTestRunner::class)
class NowScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun activeState(
        sessionTitle: String = "Overnight",
        summaryLabel: String = "412 overs · 19 stations",
        overCount: Int = 412,
        missingModel: MissingModelFacts? = null,
        worthKnowing: List<org.ort.app.ui.data.WorthKnowingItem> = emptyList(),
        stations: NowStationsSection = NowStationsSection(0, emptyList(), null, "None yet."),
    ) = NowViewState.Active(
        sessionTitle = sessionTitle,
        summaryLabel = summaryLabel,
        overCount = overCount,
        activityPattern = emptyList(),
        axisStartLabel = null,
        axisEndLabel = null,
        notListeningLabel = null,
        missingModel = missingModel,
        worthKnowing = worthKnowing,
        stations = stations,
    )

    @Test
    @Requirement("R-033")
    fun `R_033 the populated Main artboard shows the real over and station counts`() {
        composeTestRule.setContent {
            OrtTheme { NowScreen(state = activeState(summaryLabel = "412 overs · 19 stations")) }
        }

        composeTestRule.onNodeWithText("412 overs · 19 stations", substring = true).assertExists()
    }

    @Test
    @Requirement("R-037")
    fun `R_037 worth knowing is the honest Nothing yet state, never a hardcoded developer note`() {
        composeTestRule.setContent { OrtTheme { NowScreen(state = activeState(worthKnowing = emptyList())) } }

        composeTestRule.onNodeWithContentDescription("Nothing yet", substring = true).assertExists()
        composeTestRule.onNodeWithText("build-plan.md", substring = true).assertDoesNotExist()
    }

    @Test
    @Requirement("R-034")
    fun `R_034 the missing-model failed block appears with its recovery action, not a raw path`() {
        val missing = MissingModelFacts(
            title = "No transcription model installed",
            body = "Audio is being captured and kept.",
            actionLabel = "Install a model",
        )
        composeTestRule.setContent { OrtTheme { NowScreen(state = activeState(missingModel = missing)) } }

        composeTestRule.onNodeWithTag("now-missing-model").assertExists()
        composeTestRule.onNodeWithText("Install a model", substring = true).assertExists()
    }

    @Test
    @Requirement("R-030")
    fun `R_030 stations heard rows show marker callsign count and last time`() {
        val stations = NowStationsSection(
            totalCount = 1,
            rows = listOf(NowStationRow("W7NPC", AttributionState.CONFIRMED, "W7NPC", "48 overs", "02:14")),
            unidentifiedLabel = "4 unidentified voices",
            emptyMessage = null,
        )
        composeTestRule.setContent { OrtTheme { NowScreen(state = activeState(stations = stations)) } }

        composeTestRule.onNodeWithTag("now-station-W7NPC").assertExists()
        composeTestRule.onNodeWithText("48 overs", substring = true).assertExists()
        composeTestRule.onNodeWithText("4 unidentified voices", substring = true).assertExists()
    }

    @Test
    @Requirement("R-036")
    fun `R_036 the idle artboard shows Not capturing, a Start capture button and earlier nights`() {
        val idle = NowViewState.Idle(
            lastSessionSummaryLabel = "Last session ended 06:14 · 412 overs · 6 h 42 m",
            inputLabel = null,
            rigLabel = null,
            tierLabel = null,
            earlierNights = listOf(
                EarlierNightRow("S1", "Overnight, Mon 7 Sep", "23:32 – 06:14 · 412 overs · 19 stations", null),
            ),
            canGetBetter = null,
        )
        composeTestRule.setContent { OrtTheme { NowScreen(state = idle) } }

        composeTestRule.onNodeWithTag("now-idle-title").assertExists()
        composeTestRule.onNodeWithTag("now-idle-start-capture").assertExists()
        composeTestRule.onNodeWithText("Overnight, Mon 7 Sep", substring = true).assertExists()
    }

    @Test
    @Requirement("R-036")
    fun `R_036 Can get better appears only when a session qualifies`() {
        val idle = NowViewState.Idle(
            lastSessionSummaryLabel = null,
            inputLabel = null,
            rigLabel = null,
            tierLabel = null,
            earlierNights = emptyList(),
            canGetBetter = CanGetBetterRow(
                headline = "64 overs were processed below this phone's capability",
                subLine = "captured at T1 tier · open Improve to reprocess",
            ),
        )
        composeTestRule.setContent { OrtTheme { NowScreen(state = idle) } }

        composeTestRule.onNodeWithTag("now-can-get-better").assertExists()
        composeTestRule.onNodeWithText("64 overs were processed below this phone's capability", substring = true)
            .assertExists()
    }

    @Test
    @Requirement("R-174")
    fun `R_174 a fresh zero over session shows the flat chart baseline, never the hatched pattern`() {
        val state = activeState(
            sessionTitle = "Tonight",
            summaryLabel = "0 overs · listening on 145.230",
            overCount = 0,
        )
        composeTestRule.setContent { OrtTheme { NowScreen(state = state) } }

        composeTestRule.onNodeWithTag("now-first-session-chart").assertExists()
        composeTestRule.onNodeWithTag("now-activity-chart").assertDoesNotExist()
        composeTestRule.onNodeWithText("the chart fills as the night goes on", substring = true).assertExists()
        composeTestRule.onNodeWithText("0 overs · listening on 145.230", substring = true).assertExists()
    }

    @Test
    fun `tapping Start capture invokes the callback`() {
        var started = false
        val idle = NowViewState.Idle(null, null, null, null, emptyList(), null)
        composeTestRule.setContent {
            OrtTheme { NowScreen(state = idle, onStartCapture = { started = true }) }
        }

        composeTestRule.onNodeWithTag("now-idle-start-capture").performClick()
        assert(started)
    }
}
