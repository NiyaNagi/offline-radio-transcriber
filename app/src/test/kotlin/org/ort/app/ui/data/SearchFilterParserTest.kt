package org.ort.app.ui.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.ort.core.AttributionState
import org.ort.data.Band
import org.ort.testing.Requirement

/**
 * Pure parsing of the search screen's raw fields into [SearchQueryParams] (FR-UI-3, R-061/R-062).
 * No database, no Compose — the seam [SearchScreenTest] and [SearchPollingTest] both build on.
 * `now` is fixed at 2026-09-07T12:00:00Z (`1_788_782_400_000L`) throughout so every time-filter
 * test states its own expected boundary rather than depending on the system clock.
 */
public class SearchFilterParserTest {

    private val now = 1_788_782_400_000L // 2026-09-07T12:00:00Z

    @Test
    @Requirement("FR-UI-3")
    public fun `blank fields and the ALL time filter all parse to null`() {
        val params = SearchFilterParser.parse(SearchFilterInput(), now)
        assertNull(params.text)
        assertNull(params.callsign)
        assertNull(params.frequencyHz)
        assertNull(params.fromUtcMillis)
        assertNull(params.toUtcMillis)
        assertNull(params.band)
    }

    @Test
    @Requirement("FR-UI-3")
    public fun `text and callsign are trimmed and passed through`() {
        val params = SearchFilterParser.parse(SearchFilterInput(text = "  mayday  ", callsign = " w7npc "), now)
        assertEquals("mayday", params.text)
        assertEquals("w7npc", params.callsign)
    }

    @Test
    @Requirement("FR-UI-3")
    public fun `a frequency in MHz is converted to Hz`() {
        val params = SearchFilterParser.parse(SearchFilterInput(frequencyMhz = "145.230"), now)
        assertEquals(145_230_000L, params.frequencyHz)
    }

    @Test
    @Requirement("FR-UI-3")
    public fun `an unparseable frequency is dropped rather than crashing or asserting a filter that never matches`() {
        val params = SearchFilterParser.parse(SearchFilterInput(frequencyMhz = "not a number"), now)
        assertNull(params.frequencyHz)
    }

    @Test
    @Requirement("FR-UI-3")
    public fun `band passes through unparsed`() {
        val params = SearchFilterParser.parse(SearchFilterInput(band = Band.VHF_2M), now)
        assertEquals(Band.VHF_2M, params.band)
    }

    @Test
    @Requirement("R-062")
    public fun `Tonight bounds the current UTC calendar day, start inclusive and end exclusive`() {
        val params = SearchFilterParser.parse(SearchFilterInput(timeFilter = SearchTimeFilter.TONIGHT), now)
        assertEquals(1_788_739_200_000L, params.fromUtcMillis) // 2026-09-07T00:00:00Z
        assertEquals(1_788_825_600_000L, params.toUtcMillis) // 2026-09-08T00:00:00Z
    }

    @Test
    @Requirement("R-062")
    public fun `Last 7 nights spans the six days before today through the end of today, in UTC`() {
        val params = SearchFilterParser.parse(SearchFilterInput(timeFilter = SearchTimeFilter.LAST_7_NIGHTS), now)
        assertEquals(1_788_220_800_000L, params.fromUtcMillis) // 2026-09-01T00:00:00Z
        assertEquals(1_788_825_600_000L, params.toUtcMillis) // 2026-09-08T00:00:00Z
    }

    @Test
    @Requirement("R-062")
    public fun `Range reads the two range fields as a real from-to pair, FR-UI-3's requirement`() {
        val params = SearchFilterParser.parse(
            SearchFilterInput(
                timeFilter = SearchTimeFilter.RANGE,
                rangeFromLocal = "2026-09-01T18:00",
                rangeToLocal = "2026-09-08T07:00",
            ),
            now,
        )
        assertEquals(1_788_285_600_000L, params.fromUtcMillis) // 2026-09-01T18:00:00Z
        assertEquals(1_788_850_800_000L, params.toUtcMillis) // 2026-09-08T07:00:00Z
    }

    @Test
    @Requirement("R-062")
    public fun `an unparseable range bound is dropped independently rather than crashing`() {
        val params = SearchFilterParser.parse(
            SearchFilterInput(timeFilter = SearchTimeFilter.RANGE, rangeFromLocal = "not a date", rangeToLocal = ""),
            now,
        )
        assertNull(params.fromUtcMillis)
        assertNull(params.toUtcMillis)
    }

    @Test
    @Requirement("R-064")
    public fun `attribution state and time filter prose is never the raw enum name`() {
        assertEquals("Confirmed", AttributionState.CONFIRMED.prose())
        assertEquals("Ambiguous", AttributionState.AMBIGUOUS.prose())
        assertEquals("Last 7 nights", SearchTimeFilter.LAST_7_NIGHTS.label())
    }

    @Test
    @Requirement("R-064")
    public fun `R_501 a band prose label reads 160 m and 1_25 m, never the raw enum name`() {
        assertEquals("160 m", Band.HF_160M.prose())
        assertEquals("1.25 m", Band.VHF_1_25M.prose())
    }

    @Test
    @Requirement("R-501")
    public fun `R_501 a centimetre band prose label reads 70 cm, lowercase, space-separated`() {
        assertEquals("70 cm", Band.UHF_70CM.prose())
    }

    @Test
    @Requirement("R-500")
    public fun `R_500 attributionStates defaults to Confirmed and Inferred, never every state`() {
        val input = SearchFilterInput()
        assertEquals(setOf(AttributionState.CONFIRMED, AttributionState.INFERRED), input.attributionStates)
    }
}
