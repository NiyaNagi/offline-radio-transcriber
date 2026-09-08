package org.ort.app.ui.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement

/**
 * R-065: the character ranges `SearchScreen` paints in `highlightGreen` via
 * `LogRowViewState.highlightRanges` — computed once, here, rather than in the screen.
 */
class MatchHighlighterTest {

    @Test
    @Requirement("R-065")
    fun R_065_matched_words_are_highlighted_in_result_rows() {
        val transcript = "whiskey alpha at park kilo, activation of the state park"

        val ranges = MatchHighlighter.rangesFor(transcript, "park activation")

        val matched = ranges.map { transcript.substring(it.first, it.last + 1) }
        assertEquals(listOf("park", "activation", "park"), matched)
    }

    @Test
    @Requirement("R-065")
    fun `matching is case-insensitive`() {
        val ranges = MatchHighlighter.rangesFor("Park Activation", "park")

        assertEquals(listOf(0..3), ranges)
    }

    @Test
    @Requirement("R-065")
    fun `a blank query highlights nothing, never the whole transcript`() {
        assertEquals(emptyList<IntRange>(), MatchHighlighter.rangesFor("some transcript", "   "))
    }

    @Test
    @Requirement("R-065")
    fun `no match yields no ranges`() {
        assertEquals(emptyList<IntRange>(), MatchHighlighter.rangesFor("some transcript", "xyz"))
    }

    @Test
    @Requirement("R-065")
    fun `ranges are sorted by position regardless of token order in the query`() {
        val transcript = "park then activation"

        val ranges = MatchHighlighter.rangesFor(transcript, "activation park")

        assertEquals(listOf(0..3, 10..19), ranges)
    }

    @Test
    @Requirement("R-065")
    fun `an empty transcript is handled without crashing`() {
        assertEquals(emptyList<IntRange>(), MatchHighlighter.rangesFor("", "park"))
    }
}
