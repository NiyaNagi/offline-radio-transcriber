package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.SearchFacetCounts
import org.ort.app.ui.data.SearchFacetRow
import org.ort.app.ui.data.SearchFilterInput
import org.ort.app.ui.data.SearchTimeFilter
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.AttributionState
import org.ort.data.Band
import org.robolectric.RobolectricTestRunner

/**
 * `Search-Filters.dc.html` (R-061/R-062/R-064): every closed set is a visible list — chips for
 * band/time, checkboxes with counts for attribution/include — never a tap-to-cycle label. No
 * artboard element here reads a raw enum name (R-064).
 */
@RunWith(RobolectricTestRunner::class)
class SearchFiltersSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // R-203: 145.230/146.960 MHz are both inside VHF_2M's 144-148MHz allocation, matching the
    // register's own worked example — the default here keeps every pre-existing chip/band test
    // below meaningful without each one having to restate it.
    private val defaultHeardFrequenciesHz = listOf(145_230_000L, 146_960_000L)

    private fun sheet(
        input: SearchFilterInput = SearchFilterInput(),
        facetCounts: SearchFacetCounts = SearchFacetCounts.EMPTY,
        heardFrequenciesHz: List<Long> = defaultHeardFrequenciesHz,
        onInputChange: (SearchFilterInput) -> Unit = {},
        onShowResults: () -> Unit = {},
        onClearAll: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            OrtTheme {
                SearchFiltersSheet(
                    input = input,
                    facetCounts = facetCounts,
                    heardFrequenciesHz = heardFrequenciesHz,
                    onInputChange = onInputChange,
                    onShowResults = onShowResults,
                    onClearAll = onClearAll,
                )
            }
        }
    }

    @Test
    fun `R_061 band is a chip list, never a tap-to-cycle label, and selecting one updates the input`() {
        var current = SearchFilterInput()
        sheet(input = current, onInputChange = { current = it })

        composeTestRule.onNodeWithText("2M").performScrollTo().performClick()

        assert(current.band == Band.VHF_2M) { "expected VHF_2M but was ${current.band}" }
    }

    @Test
    fun `R_203 the frequency and band chips come from what this corpus actually heard, not a fixed table`() {
        sheet(heardFrequenciesHz = listOf(145_230_000L, 146_960_000L))

        // 2m (145.230/146.960) and 70cm both heard in the register's own example — the generic
        // HF chips (160M, 80M, 60M, ...) this replaced must not render at all.
        composeTestRule.onNodeWithText("145.230").assertExists()
        composeTestRule.onNodeWithText("146.960").assertExists()
        composeTestRule.onNodeWithText("2M").assertExists()
        composeTestRule.onNodeWithText("160M").assertDoesNotExist()
        composeTestRule.onNodeWithText("80M").assertDoesNotExist()
    }

    @Test
    fun `R_203 an unheard corpus offers no frequency or band chips beyond All`() {
        sheet(heardFrequenciesHz = emptyList())

        // Tagged, not text — the Time section has its own, separate "All" chip
        // (`SearchTimeFilter.ALL.label()` is also literally "All").
        composeTestRule.onNodeWithTag("search-filter-band-all").assertExists()
        composeTestRule.onNodeWithText("2M").assertDoesNotExist()
        composeTestRule.onNodeWithText("160M").assertDoesNotExist()
    }

    @Test
    fun `R_203 tapping an exact heard-frequency chip sets frequencyMhz and clears band`() {
        var current = SearchFilterInput(band = Band.UHF_70CM)
        sheet(input = current, heardFrequenciesHz = listOf(145_230_000L), onInputChange = { current = it })

        composeTestRule.onNodeWithText("145.230").performScrollTo().performClick()

        assert(current.frequencyMhz == "145.230") { "expected frequencyMhz 145.230 but was ${current.frequencyMhz}" }
        assert(current.band == null) { "expected band to clear but was ${current.band}" }
    }

    @Test
    fun `R_203 tapping a heard-band chip sets band and clears the exact frequency`() {
        var current = SearchFilterInput(frequencyMhz = "145.230")
        sheet(input = current, heardFrequenciesHz = listOf(145_230_000L), onInputChange = { current = it })

        composeTestRule.onNodeWithText("2M").performScrollTo().performClick()

        assert(current.band == Band.VHF_2M) { "expected VHF_2M but was ${current.band}" }
        assert(current.frequencyMhz == "") { "expected frequencyMhz to clear but was ${current.frequencyMhz}" }
    }

    @Test
    fun `R_062 time is a chip list with a real Range, and Range reveals a from-to pair`() {
        var current = SearchFilterInput()
        sheet(input = current, onInputChange = { current = it })

        composeTestRule.onNodeWithText("Range").performScrollTo().performClick()

        assert(current.timeFilter == SearchTimeFilter.RANGE) { "expected RANGE but was ${current.timeFilter}" }
    }

    @Test
    fun `R_062 the Range from-to pair renders once Range is selected`() {
        sheet(input = SearchFilterInput(timeFilter = SearchTimeFilter.RANGE))

        composeTestRule.onNodeWithContentDescription("Range start").assertExists()
        composeTestRule.onNodeWithContentDescription("Range end").assertExists()
    }

    @Test
    fun `R_062 the Range from-to pair is absent for every other time filter`() {
        sheet(input = SearchFilterInput(timeFilter = SearchTimeFilter.TONIGHT))

        composeTestRule.onNodeWithContentDescription("Range start").assertDoesNotExist()
    }

    @Test
    fun `R_064 attribution checkboxes show prose labels and real counts, never a raw enum name`() {
        val counts = SearchFacetCounts(
            listOf(
                SearchFacetRow(AttributionState.CONFIRMED, false, false),
                SearchFacetRow(AttributionState.CONFIRMED, false, false),
                SearchFacetRow(AttributionState.AMBIGUOUS, false, false),
            ),
        )
        sheet(facetCounts = counts)

        composeTestRule.onNodeWithText("Confirmed").assertExists()
        composeTestRule.onNodeWithText("2").assertExists()
        composeTestRule.onNodeWithText("CONFIRMED").assertDoesNotExist()
    }

    @Test
    fun `R_061 unchecking an attribution checkbox removes that state from the input`() {
        var current = SearchFilterInput()
        sheet(input = current, onInputChange = { current = it })

        composeTestRule.onNodeWithText("Confirmed").performScrollTo().performClick()

        assert(AttributionState.CONFIRMED !in current.attributionStates) {
            "expected Confirmed to be removed but attributionStates was ${current.attributionStates}"
        }
    }

    @Test
    fun `R_061 the Include checkboxes toggle rejected and corrected independently`() {
        var current = SearchFilterInput()
        sheet(input = current, onInputChange = { current = it })

        composeTestRule.onNodeWithText("Rejected segments").performScrollTo().performClick()

        assert(current.includeRejected) { "expected includeRejected to flip true" }
    }

    @Test
    fun `Show N overs reports the exact count the current selection would give, never a fabricated number`() {
        val counts = SearchFacetCounts(
            listOf(
                SearchFacetRow(AttributionState.CONFIRMED, false, false),
                SearchFacetRow(AttributionState.UNKNOWN, false, false),
            ),
        )
        sheet(facetCounts = counts)

        composeTestRule.onNodeWithText("Show 2 overs").assertExists()
    }

    @Test
    fun `tapping Show N overs invokes onShowResults`() {
        var shown = false
        sheet(onShowResults = { shown = true })

        composeTestRule.onNodeWithText("Show 0 overs").performScrollTo().performClick()

        assert(shown) { "expected Show N overs to invoke onShowResults" }
    }

    @Test
    fun `Clear all invokes onClearAll`() {
        var cleared = false
        sheet(onClearAll = { cleared = true })

        composeTestRule.onNodeWithText("Clear all").performScrollTo().performClick()

        assert(cleared) { "expected Clear all to invoke onClearAll" }
    }

    @Test
    fun `the callsign prefix field issues an updated input on edit`() {
        var current = SearchFilterInput()
        sheet(input = current, onInputChange = { current = it })

        composeTestRule.onNodeWithContentDescription("Callsign prefix filter")
            .performScrollTo()
            .performTextInput("K7L")

        assert(current.callsign.contains("K7L")) {
            "expected the callsign field edit to reach onInputChange, got ${current.callsign}"
        }
    }
}
