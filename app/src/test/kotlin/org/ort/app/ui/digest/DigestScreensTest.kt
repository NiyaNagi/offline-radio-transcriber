package org.ort.app.ui.digest

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * `Digest-Prose.dc.html` (E2-G07, FR-DIG-3, FR-DIG-6, FR-DIG-11): [DigestScreen] is a pure
 * function of [DigestViewState] — [DigestPollingTest] covers which real facts produce
 * [DigestViewState.prose]; this proves the screen renders (or omits) it correctly.
 */
@RunWith(RobolectricTestRunner::class)
class DigestScreensTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun baseState(prose: DigestProseSectionViewState?) = DigestViewState(
        sessionId = "S1",
        headline = "Overnight, Mon 7 Sep",
        timeRangeLabel = "23:32 – 06:14",
        overCount = 412,
        stationCount = 19,
        bandCount = 2,
        gapCount = 1,
        items = emptyList(),
        notKnown = emptyList(),
        attributedPercentLabel = "92%",
        rejectedCount = 6,
        prose = prose,
    )

    private fun card(fromMillis: Long = 0L, toMillis: Long = 0L) = DigestProseCardViewState(
        subject = "WA7HJR",
        detailLine = "6 overs",
        text = "Reported running low power from the park with a wire antenna.",
        oversRangeLabel = "from overs 02:17 – 02:41",
        fromMillis = fromMillis,
        toMillis = toMillis,
    )

    @Test
    @Requirement("E2-G07", "FR-DIG-6")
    fun `E2_G07 the prose section renders a card, badged generated, with the footnote`() {
        val prose = DigestProseSectionViewState(
            cards = listOf(card()),
            footnote = "Written on this phone by the bundled language model from the resolved overs only.",
        )

        composeTestRule.setContent {
            OrtTheme {
                DigestScreen(state = baseState(prose), onBack = {}, onOpenItem = {}, onFullLog = {})
            }
        }

        composeTestRule.onNodeWithText("IN THEIR WORDS", substring = true).assertExists()
        composeTestRule.onNodeWithText("GENERATED", substring = true).assertExists()
        composeTestRule.onNodeWithText("WA7HJR").assertExists()
        composeTestRule.onNodeWithText("Reported running low power", substring = true).assertExists()
        composeTestRule.onNodeWithText("from overs 02:17 – 02:41", substring = true).assertExists()
        composeTestRule.onNodeWithText("Written on this phone", substring = true).assertExists()
    }

    @Test
    @Requirement("E2-G07", "FR-DIG-3a")
    fun `E2_G07 the section is absent entirely when prose is null, never an empty header`() {
        composeTestRule.setContent {
            OrtTheme {
                DigestScreen(state = baseState(null), onBack = {}, onOpenItem = {}, onFullLog = {})
            }
        }

        composeTestRule.onNodeWithText("IN THEIR WORDS", substring = true).assertDoesNotExist()
    }

    @Test
    @Requirement("E2-G07")
    fun `E2_G07 Read the overs invokes onReadOvers with the card's own over window`() {
        val prose = DigestProseSectionViewState(
            cards = listOf(card(fromMillis = 8_220_000L, toMillis = 9_660_000L)),
            footnote = "footnote",
        )
        var seededFrom: Long? = null
        var seededTo: Long? = null

        composeTestRule.setContent {
            OrtTheme {
                DigestScreen(
                    state = baseState(prose),
                    onBack = {},
                    onOpenItem = {},
                    onFullLog = {},
                    onReadOvers = { from, to ->
                        seededFrom = from
                        seededTo = to
                    },
                )
            }
        }

        composeTestRule.onNodeWithText("Read the overs").performClick()

        assert(seededFrom == 8_220_000L) { "got $seededFrom" }
        assert(seededTo == 9_660_000L) { "got $seededTo" }
    }
}
