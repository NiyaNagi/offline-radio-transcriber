package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.RejectedFilter
import org.ort.app.ui.data.SearchFilterInput
import org.ort.app.ui.data.SearchResult
import org.ort.app.ui.data.TransmissionDetail
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.Attribution
import org.ort.core.AttributionState
import org.ort.data.Band
import org.robolectric.RobolectricTestRunner

/**
 * FR-UI-3 — full-text search over the transcript index, with callsign/frequency/date filters. The
 * screen itself is a pure function of [SearchFilterInput]/[SearchResult] — [SearchPollingTest] and
 * [org.ort.app.ui.data.SearchFilterParserTest] cover the data half; this proves what renders.
 */
@RunWith(RobolectricTestRunner::class)
class SearchScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun detail(id: String, text: String, attribution: Attribution = Attribution.unknown()) = TransmissionDetail(
        id = id,
        startedAtUtcMillis = 0L,
        frequencyHz = 145_230_000L,
        durationMs = 1_000L,
        signalStrength = null,
        attribution = attribution,
        currentTranscriptText = text,
        supersededTranscriptTexts = emptyList(),
        hasAudio = false,
    )

    @Test
    fun `before any search runs, the screen shows an honest prompt rather than a blank list`() {
        composeTestRule.setContent {
            OrtTheme {
                SearchScreen(input = SearchFilterInput(), result = null, onInputChange = {}, onSearch = {}, onOpen = {})
            }
        }

        composeTestRule.onNodeWithText("Enter a search term or filter, then tap Search").assertExists()
    }

    @Test
    fun `tapping Search invokes the callback`() {
        var searched = false
        composeTestRule.setContent {
            OrtTheme {
                SearchScreen(
                    input = SearchFilterInput(),
                    result = null,
                    onInputChange = {},
                    onSearch = { searched = true },
                    onOpen = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Run search").performScrollTo().performClick()

        assert(searched) { "expected the Search action to be invoked" }
    }

    @Test
    fun `a search with no matches shows an honest empty state, not a blank screen`() {
        composeTestRule.setContent {
            OrtTheme {
                SearchScreen(
                    input = SearchFilterInput(text = "nonesuch"),
                    result = SearchResult(emptyList(), textSearchUnavailable = false),
                    onInputChange = {},
                    onSearch = {},
                    onOpen = {},
                )
            }
        }

        composeTestRule.onNodeWithText("No results").assertExists()
    }

    @Test
    fun `matching transmissions render with their attribution and open on tap`() {
        var opened: String? = null
        composeTestRule.setContent {
            OrtTheme {
                SearchScreen(
                    input = SearchFilterInput(text = "mayday"),
                    result = SearchResult(
                        listOf(detail("TX1", "mayday mayday", Attribution.confirmed("W7NPC", 0.9))),
                        textSearchUnavailable = false,
                    ),
                    onInputChange = {},
                    onSearch = {},
                    onOpen = { opened = it },
                )
            }
        }

        composeTestRule.onNodeWithText("mayday mayday").assertExists()
        composeTestRule.onNodeWithContentDescription("filled circle, ✓ CONFIRMED", substring = true).assertExists()
        composeTestRule.onNodeWithContentDescription("Transmission at", substring = true)
            .performScrollTo()
            .performClick()
        assert(opened == "TX1") { "expected TX1 to be opened but was $opened" }
    }

    @Test
    fun `when the full-text index is unavailable the screen says so honestly rather than hiding it`() {
        composeTestRule.setContent {
            OrtTheme {
                SearchScreen(
                    input = SearchFilterInput(text = "mayday", callsign = "W7NPC"),
                    result = SearchResult(
                        listOf(detail("TX1", "irrelevant transcript")),
                        textSearchUnavailable = true,
                    ),
                    onInputChange = {},
                    onSearch = {},
                    onOpen = {},
                )
            }
        }

        composeTestRule.onNodeWithText(
            "Full-text search unavailable right now; only the other filters were applied",
        ).assertExists()
    }

    /**
     * FR-UI-3 / audit F-017: the `:app` half of the finding — band, attribution-state and
     * rejected/accepted controls exist (text-labelled, per the accessibility floor) and tapping
     * each one changes the [SearchFilterInput] the screen issues, exactly like every other filter
     * field here.
     */
    @Test
    fun `FR_UI_3 band, attribution-state and rejected filter controls exist and a tap changes the issued query`() {
        var current = SearchFilterInput()
        composeTestRule.setContent {
            OrtTheme {
                SearchScreen(
                    input = current,
                    result = null,
                    onInputChange = { current = it },
                    onSearch = {},
                    onOpen = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Band filter: All bands").assertExists()
        composeTestRule.onNodeWithContentDescription("Attribution state filter: All states").assertExists()
        composeTestRule.onNodeWithContentDescription("Accepted/rejected filter: All").assertExists()

        composeTestRule.onNodeWithContentDescription("Band filter: All bands").performScrollTo().performClick()
        assert(current.band == Band.HF_160M) { "expected the band filter to advance but was ${current.band}" }

        composeTestRule.onNodeWithContentDescription("Attribution state filter: All states")
            .performScrollTo()
            .performClick()
        assert(current.attributionState == AttributionState.CONFIRMED) {
            "expected the attribution-state filter to advance but was ${current.attributionState}"
        }

        composeTestRule.onNodeWithContentDescription("Accepted/rejected filter: All").performScrollTo().performClick()
        assert(current.rejectedFilter == RejectedFilter.ACCEPTED) {
            "expected the rejected filter to advance but was ${current.rejectedFilter}"
        }
    }
}
