package org.ort.app.ui.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * This task (register R-204 follow-up, FR-UI-3): [resolveTextSearch] is the pure decision
 * [SearchPolling.search], [SearchPolling.facetCounts] and [SearchWidenSuggestions]' own
 * `countMatching` now all share, driven by [org.ort.data.hasTextSearchIndex]'s positive,
 * schema-level answer instead of a caught `SQLException`'s message text (proven, by a three-run
 * CI investigation, commit 5a9f53a, to read differently by platform for the identical failure).
 *
 * No real SQLite build reachable from this project's own test suite genuinely lacks fts5 — see
 * [SearchPollingTest]'s own comment on the test it replaced for the full story — so this class is
 * what proves the fts5-absent half of *this specific* decision for real: no database, real or
 * simulated, is needed, because the function under test does not touch one. `:data`'s own
 * `FtsCapabilityProbeTest` proves the fts5-absent half one layer down, at the capability probe
 * itself, via the test seam `OrtDatabase.fts5SupportOverrideForTest` introduces for the same
 * reason.
 */
public class SearchTextAvailabilityTest {

    @Test
    public fun FR_UI_3_no_requested_text_never_touches_the_index_and_is_never_unavailable() {
        val outcome = resolveTextSearch(requestedText = null, indexAvailable = false)

        assertNull(outcome.effectiveText)
        assertFalse(
            "a filters-only search was never asking for text, so it cannot be 'unavailable'",
            outcome.unavailable,
        )
    }

    @Test
    public fun FR_UI_3_requested_text_with_the_index_available_runs_for_real() {
        val outcome = resolveTextSearch(requestedText = "mayday", indexAvailable = true)

        assertEquals("mayday", outcome.effectiveText)
        assertFalse(outcome.unavailable)
    }

    @Test
    public fun FR_UI_3_requested_text_without_the_index_degrades_honestly_instead_of_running() {
        val outcome = resolveTextSearch(requestedText = "mayday", indexAvailable = false)

        assertNull(
            "a genuinely missing index must drop the text term, not pass it to a query that will throw",
            outcome.effectiveText,
        )
        assertTrue("the caller must be told the text term was not applied", outcome.unavailable)
    }
}
