package org.ort.app.ui.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * R-063's `RECENT` rows: an app-private store of the operator's last searches with their counts.
 * Robolectric provides a real (shadow) `SharedPreferences`, so this proves the actual persistence
 * path, not a fake standing in for it.
 */
@RunWith(RobolectricTestRunner::class)
class RecentSearchesTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `R_063 an empty store lists nothing`() {
        assertEquals(emptyList<RecentSearchEntry>(), RecentSearches.list(context))
    }

    @Test
    fun `R_063 a recorded search is listed with its count, most recent first`() {
        RecentSearches.record(context, "WA7HJR", 6)
        RecentSearches.record(context, "grid square", 14)

        val entries = RecentSearches.list(context)

        assertEquals(listOf(RecentSearchEntry("grid square", 14), RecentSearchEntry("WA7HJR", 6)), entries)
    }

    @Test
    fun `R_063 repeating a search moves it to the front with its latest count, not a second row`() {
        RecentSearches.record(context, "WA7HJR", 6)
        RecentSearches.record(context, "grid square", 14)
        RecentSearches.record(context, "wa7hjr", 9)

        val entries = RecentSearches.list(context)

        assertEquals(listOf(RecentSearchEntry("wa7hjr", 9), RecentSearchEntry("grid square", 14)), entries)
    }

    @Test
    fun `R_063 only the five most recent searches are kept`() {
        repeat(7) { index -> RecentSearches.record(context, "term$index", index) }

        val entries = RecentSearches.list(context)

        assertEquals(5, entries.size)
        assertEquals("term6", entries.first().label)
        assertEquals("term2", entries.last().label)
    }

    @Test
    fun `R_063 a blank label is dropped rather than recorded as an empty row`() {
        RecentSearches.record(context, "   ", 3)

        assertEquals(emptyList<RecentSearchEntry>(), RecentSearches.list(context))
    }

    @Test
    fun `R_063 primaryTermFor prefers callsign, then text, then frequency, and is null for a filters-only search`() {
        assertEquals("W7NPC", RecentSearches.primaryTermFor(SearchFilterInput(callsign = "W7NPC", text = "mayday")))
        assertEquals("mayday", RecentSearches.primaryTermFor(SearchFilterInput(text = "mayday")))
        assertEquals("146.960", RecentSearches.primaryTermFor(SearchFilterInput(frequencyMhz = "146.960")))
        assertNull(RecentSearches.primaryTermFor(SearchFilterInput(band = org.ort.data.Band.VHF_2M)))
    }
}
