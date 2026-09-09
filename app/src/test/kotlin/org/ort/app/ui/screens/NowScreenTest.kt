package org.ort.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
import org.ort.app.ui.theme.OrtType
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
        axisStartOverride: String? = null,
    ) = NowViewState.Active(
        sessionTitle = sessionTitle,
        summaryLabel = summaryLabel,
        overCount = overCount,
        activityPattern = emptyList(),
        axisStartLabel = axisStartOverride,
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
    @Requirement("R-260")
    fun `R_260 the earlier-nights gap token wraps as a whole word, never one character per line, at fontscale-2_0`() {
        // R-260 (`overnight/N01-now@2x.png`): before the fix, a plain (non-wrapping) Row gave the
        // trailing " · 1 gap" whatever sliver of width was left on the line after the subLine text,
        // collapsing it into a ~16px column that wrapped one character per line. This test proves
        // the fix at a real device's content width (~350dp, `Now`'s own `OrtSpacing.lg` padding on a
        // 390dp screen) and a real font scale (2.0) — not the exact rendering (Robolectric can't
        // measure real font metrics reliably), but the one host-independent, geometry-based signal
        // that distinguishes "wrapped as a whole token" from "collapsed into single characters": a
        // token stacked seven characters high measures dramatically taller than the same token
        // rendered with room to spare on one line, not merely "a bit taller" from ordinary wrapping.
        val idle = NowViewState.Idle(
            lastSessionSummaryLabel = null,
            inputLabel = null,
            rigLabel = null,
            tierLabel = null,
            earlierNights = listOf(
                EarlierNightRow("S1", "Overnight, Tue 8 Sep", "11:04 – 17:46 · 42 overs · 9 stations", "1 gap"),
            ),
            canGetBetter = null,
        )
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme {
                    Box(modifier = Modifier.width(350.dp)) {
                        NowScreen(state = idle)
                    }
                    // The same token, unconstrained, as a reference for "rendered on one line".
                    Text(
                        text = " · 1 gap",
                        style = OrtType.subLine,
                        softWrap = false,
                        modifier = Modifier.testTag("gap-reference"),
                    )
                }
            }
        }

        val realHeight = composeTestRule.onNodeWithTag("now-earlier-night-gap").fetchSemanticsNode().size.height
        val referenceHeight = composeTestRule.onNodeWithTag("gap-reference").fetchSemanticsNode().size.height
        assert(realHeight <= referenceHeight * 2) {
            "expected the gap token to render at roughly one line's height (reference " +
                "${referenceHeight}px); got ${realHeight}px, consistent with wrapping one character per line"
        }
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
                subLine = "captured at tier 1 · open Improve to reprocess",
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
    @Requirement("R-415")
    fun `R_415 the populated Main chart carries no title, only the axis row`() {
        val state = activeState(overCount = 412).copy(
            activityPattern = listOf(
                org.ort.app.ui.data.HourActivityBucket(0, org.ort.app.ui.data.HourActivityState.HEARD, 3),
            ),
        )
        composeTestRule.setContent { OrtTheme { NowScreen(state = state) } }

        composeTestRule.onNodeWithTag("now-activity-chart").assertExists()
        composeTestRule.onNodeWithText("ACTIVITY BY HOUR", substring = true).assertDoesNotExist()
    }

    @Test
    @Requirement("R-416")
    fun `R_416 Now-Idle shows the real input rig tier meta row and the divider before Earlier nights`() {
        val idle = NowViewState.Idle(
            lastSessionSummaryLabel = null,
            inputLabel = "USB Audio Device",
            rigLabel = "TH-D75A",
            tierLabel = "tier 3",
            earlierNights = listOf(
                EarlierNightRow("S1", "Overnight, Mon 7 Sep", "23:32 – 06:14 · 412 overs · 19 stations", null),
            ),
            canGetBetter = null,
        )
        composeTestRule.setContent { OrtTheme { NowScreen(state = idle) } }

        composeTestRule.onNodeWithText("USB Audio Device · TH-D75A · tier 3", substring = true).assertExists()
        composeTestRule.onNodeWithTag("now-idle-earlier-nights-divider").assertExists()
    }

    @Test
    @Requirement("R-416")
    fun `R_416 the meta row is honestly absent when none of the three facts are known`() {
        val idle = NowViewState.Idle(
            lastSessionSummaryLabel = null,
            inputLabel = null,
            rigLabel = null,
            tierLabel = null,
            earlierNights = emptyList(),
            canGetBetter = null,
        )
        composeTestRule.setContent { OrtTheme { NowScreen(state = idle) } }

        composeTestRule.onNodeWithText("tier", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("now-idle-earlier-nights-divider").assertExists()
    }

    @Test
    @Requirement("R-417")
    fun `R_417 an empty Stations heard carries the Listening since second line`() {
        val stations = NowStationsSection(
            totalCount = 0,
            rows = emptyList(),
            unidentifiedLabel = null,
            emptyMessage = "None yet.",
        )
        val state = activeState(stations = stations, axisStartOverride = "23:32")
        composeTestRule.setContent { OrtTheme { NowScreen(state = state) } }

        composeTestRule.onNodeWithText("None yet.", substring = true).assertExists()
        composeTestRule.onNodeWithText("Listening since 23:32.", substring = true).assertExists()
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
