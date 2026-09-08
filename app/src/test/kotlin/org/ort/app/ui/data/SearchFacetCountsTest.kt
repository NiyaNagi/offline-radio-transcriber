package org.ort.app.ui.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ort.core.AttributionState

/**
 * R-061: the per-state/rejected/corrected counts the filter sheet's checkboxes show, and the
 * exact `Show N overs` preview — computed from already-fetched rows, never re-queried per tap and
 * never fabricated (guide §9).
 */
class SearchFacetCountsTest {

    private fun row(state: AttributionState, rejected: Boolean = false, corrected: Boolean = false) =
        SearchFacetRow(state, rejected, corrected)

    @Test
    fun `per-state counts are independent of the rejected and corrected counts`() {
        val counts = SearchFacetCounts(
            listOf(
                row(AttributionState.CONFIRMED),
                row(AttributionState.CONFIRMED, rejected = true),
                row(AttributionState.INFERRED),
                row(AttributionState.AMBIGUOUS, corrected = true),
                row(AttributionState.UNKNOWN),
            ),
        )

        assertEquals(5, counts.total)
        assertEquals(2, counts.confirmed)
        assertEquals(1, counts.inferred)
        assertEquals(1, counts.ambiguous)
        assertEquals(1, counts.unknown)
        assertEquals(1, counts.rejectedCount)
        assertEquals(1, counts.correctedCount)
    }

    @Test
    fun `countMatching applies attribution states and both include toggles together`() {
        val counts = SearchFacetCounts(
            listOf(
                row(AttributionState.CONFIRMED),
                row(AttributionState.CONFIRMED, rejected = true),
                row(AttributionState.UNKNOWN),
                row(AttributionState.UNKNOWN, corrected = true),
            ),
        )

        val onlyConfirmedExcludingRejected = SearchFacetFilter(
            attributionStates = setOf(AttributionState.CONFIRMED),
            includeRejected = false,
            includeCorrected = true,
        )
        assertEquals(1, counts.countMatching(onlyConfirmedExcludingRejected))

        val everything =
            SearchFacetFilter(AttributionState.entries.toSet(), includeRejected = true, includeCorrected = true)
        assertEquals(4, counts.countMatching(everything))
    }

    @Test
    fun `EMPTY reports zero for everything, never a fabricated total`() {
        assertEquals(0, SearchFacetCounts.EMPTY.total)
        assertEquals(0, SearchFacetCounts.EMPTY.confirmed)
    }
}
