package org.ort.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.ThreadAttributionExplanationLine
import org.ort.app.ui.data.ThreadDetailOverViewState
import org.ort.app.ui.data.ThreadDetailViewState
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtTheme
import org.ort.app.ui.theme.OrtType
import org.ort.core.Attribution
import org.robolectric.RobolectricTestRunner

/**
 * R-044 (ui-conformance WP5): `Thread-Detail.dc.html` — the "How these were attributed" card
 * (constitution I, P2) and the per-over reasoning list, whose source-over link opens that over.
 */
@RunWith(RobolectricTestRunner::class)
class ThreadDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val over1Transcript = "this is whiskey seven november papa charlie"
    private val over2Reasoning = "inherited by voice from 02:16:02"

    private fun state(kindLabel: String? = null, modeLabel: String? = null) = ThreadDetailViewState(
        threadId = "T1",
        kindLabel = kindLabel,
        frequencyLabel = "145.230",
        modeLabel = modeLabel,
        titleText = "W7NPC and K7LWH",
        metaText = "4 overs · 02:14:07 – 02:16:02 · 1 m 55 s",
        howAttributed = listOf(
            ThreadAttributionExplanationLine(Attribution.confirmed("W7NPC", 0.95), "W7NPC heard in overs 1 and 3"),
            ThreadAttributionExplanationLine(
                Attribution.inferred("K7LWH", 0.82),
                "Over 2 matched K7LWH's voice from over 4 · 0.82",
            ),
        ),
        overs = listOf(
            ThreadDetailOverViewState(
                transmissionId = "TX1",
                timeLabel = "02:14:07",
                attribution = Attribution.confirmed("W7NPC", 0.95),
                transcript = over1Transcript,
                reasoning = "callsign heard in this over",
                sourceTransmissionId = null,
            ),
            ThreadDetailOverViewState(
                transmissionId = "TX2",
                timeLabel = "02:14:22",
                attribution = Attribution.inferred("K7LWH", 0.82),
                transcript = "roger that",
                reasoning = over2Reasoning,
                sourceTransmissionId = "TX4",
                sourceTimeLabel = "02:16:02",
            ),
        ),
    )

    @Composable
    private fun screen(
        onBack: () -> Unit = {},
        onOpenOver: (String) -> Unit = {},
        onOpenSourceOver: (String) -> Unit = {},
        backLabel: String = "Threads",
        kindLabel: String? = null,
        modeLabel: String? = null,
    ) = ThreadDetailScreen(
        state = state(kindLabel = kindLabel, modeLabel = modeLabel),
        onBack = onBack,
        onOpenOver = onOpenOver,
        onOpenSourceOver = onOpenSourceOver,
        backLabel = backLabel,
    )

    @Test
    fun `R_044 the how-these-were-attributed card names each station and the source of an inherited match`() {
        composeTestRule.setContent { OrtTheme { screen() } }

        composeTestRule.onNodeWithText("W7NPC heard in overs 1 and 3").assertExists()
        composeTestRule.onNodeWithText("Over 2 matched K7LWH's voice from over 4 · 0.82").assertExists()
    }

    @Test
    fun `R_424 an INFERRED how-attributed line shows its score once, inline, never a second chip`() {
        // `overnight/T02-thread-detail.png`: before this wiring, K7LWH's line showed "0.82" twice —
        // AttributionRow's own chip beside the marker, plus the identical figure this card's own
        // sentence text already carries ("... over 4 · 0.82"). A bare `onNodeWithText("0.82")`
        // exact-matches only the standalone chip's own Text node, never the longer sentence.
        composeTestRule.setContent { OrtTheme { screen() } }

        composeTestRule.onNodeWithText("Over 2 matched K7LWH's voice from over 4 · 0.82").assertExists()
        composeTestRule.onNodeWithText("0.82").assertDoesNotExist()
    }

    @Test
    fun `R_044 every over renders its transcript and reasoning`() {
        composeTestRule.setContent { OrtTheme { screen() } }

        composeTestRule.onNodeWithText(over1Transcript).assertExists()
        composeTestRule.onNodeWithTag("thread-detail-overs").performScrollToNode(hasText(over2Reasoning))
        composeTestRule.onNodeWithText(over2Reasoning).assertExists()
    }

    @Test
    fun `R_044 tapping an over opens it`() {
        var opened: String? = null
        composeTestRule.setContent { OrtTheme { screen(onOpenOver = { opened = it }) } }

        composeTestRule.onNodeWithText(over1Transcript).performClick()

        assert(opened == "TX1") { "expected TX1 to be opened but was $opened" }
    }

    @Test
    fun `R_044 tapping the source-over link opens the source, not the current over`() {
        var openedSource: String? = null
        composeTestRule.setContent { OrtTheme { screen(onOpenSourceOver = { openedSource = it }) } }

        composeTestRule.onNodeWithTag("thread-detail-overs").performScrollToNode(hasText(over2Reasoning))
        composeTestRule.onNodeWithText(over2Reasoning).performClick()

        assert(openedSource == "TX4") { "expected TX4 to be opened but was $openedSource" }
    }

    @Test
    fun `tapping back invokes the callback`() {
        var backPressed = false
        composeTestRule.setContent { OrtTheme { screen(onBack = { backPressed = true }) } }

        composeTestRule.onNodeWithContentDescription("Back to Threads").performClick()

        assert(backPressed)
    }

    @Test
    fun `R_161 the header line leads with the frequency and adds kind and mode only when the data has them`() {
        composeTestRule.setContent { OrtTheme { screen() } }

        composeTestRule.onNodeWithText("145.230").assertExists()
    }

    @Test
    fun `R_161 the header line renders kind and mode when both are derivable`() {
        composeTestRule.setContent { OrtTheme { screen(kindLabel = "QSO", modeLabel = "FM") } }

        composeTestRule.onNodeWithText("QSO · 145.230 · FM").assertExists()
    }

    @Test
    fun `R_161 the span duration renders on the meta line`() {
        composeTestRule.setContent { OrtTheme { screen() } }

        composeTestRule.onNodeWithText("4 overs · 02:14:07 – 02:16:02 · 1 m 55 s").assertExists()
    }

    @Test
    fun `R_161 the per-over table has no FREQ column, just time and over`() {
        composeTestRule.setContent { OrtTheme { screen() } }

        composeTestRule.onNodeWithText("TIME").assertExists()
        composeTestRule.onNodeWithText("OVER").assertExists()
        composeTestRule.onNodeWithText("FREQ").assertDoesNotExist()
    }

    @Test
    fun `R_162 a CONFIRMED line in how-these-were-attributed never announces a confidence number`() {
        composeTestRule.setContent { OrtTheme { screen() } }

        // W7NPC's line is CONFIRMED (0.95) — AttributionRow's own description (guide §9) never
        // states a confidence figure for CONFIRMED, unlike the legacy AttributionMarker path
        // (whose `legacyMarkerDescription` appended one regardless of `showConfidence`).
        composeTestRule.onNodeWithContentDescription("filled circle, Confirmed").assertExists()
    }

    @Test
    fun `R_017 the chevron names the real navigation origin, not a hardcoded Threads`() {
        var backPressed = false
        composeTestRule.setContent { OrtTheme { screen(backLabel = "Log", onBack = { backPressed = true }) } }

        composeTestRule.onNodeWithContentDescription("Back to Threads").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Back to Log").performClick()

        assert(backPressed)
    }

    @Test
    fun `R_332_affordance the inherited line renders and links, and its time run is styled mono-green`() {
        composeTestRule.setContent { OrtTheme { screen() } }

        composeTestRule.onNodeWithTag("thread-detail-overs").performScrollToNode(hasText(over2Reasoning))
        // The whole sentence is still one reachable, tappable text (unchanged behaviour) —
        // R_044's own "tapping the source-over link opens the source" test already proves the tap
        // itself; this proves the visual affordance guide §6.7 asks for.
        composeTestRule.onNodeWithText(over2Reasoning).assertExists()

        val styled = reasoningAnnotatedString(over2Reasoning, "02:16:02")
        assertEquals("inherited by voice from 02:16:02", styled.text)
        val timeSpan = styled.spanStyles.single()
        assertEquals("inherited by voice from ".length, timeSpan.start)
        assertEquals(styled.text.length, timeSpan.end)
        assertEquals(OrtColors.accentGreen, timeSpan.item.color)
        assertEquals(OrtType.mono, timeSpan.item.fontFamily)
    }

    @Test
    fun `R_332_affordance a mismatched or missing link time never styles the wrong span`() {
        assertEquals("inherited by callsign", reasoningAnnotatedString("inherited by callsign", null).text)
        assertEquals(0, reasoningAnnotatedString("inherited by callsign", null).spanStyles.size)
        // A link time that is not actually the sentence's own trailing substring is never applied.
        assertEquals(0, reasoningAnnotatedString("inherited by voice from 02:16:02", "03:00:00").spanStyles.size)
    }
}
