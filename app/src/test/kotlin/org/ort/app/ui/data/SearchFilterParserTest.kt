package org.ort.app.ui.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ort.testing.Requirement

/**
 * Pure parsing of the search screen's raw text fields into [SearchQueryParams] (FR-UI-3). No
 * database, no Compose — the seam [SearchScreenTest] and [SearchPollingTest] both build on.
 */
public class SearchFilterParserTest {

    @Test
    @Requirement("FR-UI-3")
    public fun `blank fields all parse to null filters`() {
        val params = SearchFilterParser.parse(SearchFilterInput())
        assertNull(params.text)
        assertNull(params.callsign)
        assertNull(params.frequencyHz)
        assertNull(params.fromUtcMillis)
        assertNull(params.toUtcMillis)
    }

    @Test
    @Requirement("FR-UI-3")
    public fun `text and callsign are trimmed and passed through`() {
        val params = SearchFilterParser.parse(SearchFilterInput(text = "  mayday  ", callsign = " w7npc "))
        assertEquals("mayday", params.text)
        assertEquals("w7npc", params.callsign)
    }

    @Test
    @Requirement("FR-UI-3")
    public fun `a frequency in MHz is converted to Hz`() {
        val params = SearchFilterParser.parse(SearchFilterInput(frequencyMhz = "145.230"))
        assertEquals(145_230_000L, params.frequencyHz)
    }

    @Test
    @Requirement("FR-UI-3")
    public fun `an unparseable frequency is dropped rather than crashing or asserting a filter that never matches`() {
        val params = SearchFilterParser.parse(SearchFilterInput(frequencyMhz = "not a number"))
        assertNull(params.frequencyHz)
    }

    @Test
    @Requirement("FR-UI-3")
    public fun `a date filters the whole UTC day, start inclusive and end exclusive`() {
        val params = SearchFilterParser.parse(SearchFilterInput(dateUtc = "2026-09-07"))
        assertEquals(1_788_739_200_000L, params.fromUtcMillis) // 2026-09-07T00:00:00Z
        assertEquals(1_788_825_600_000L, params.toUtcMillis) // 2026-09-08T00:00:00Z
    }

    @Test
    @Requirement("FR-UI-3")
    public fun `an unparseable date is dropped rather than crashing`() {
        val params = SearchFilterParser.parse(SearchFilterInput(dateUtc = "not a date"))
        assertNull(params.fromUtcMillis)
        assertNull(params.toUtcMillis)
    }
}
