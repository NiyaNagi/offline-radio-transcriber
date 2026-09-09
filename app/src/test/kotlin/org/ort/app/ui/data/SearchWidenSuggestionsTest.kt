package org.ort.app.ui.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.AttributionState
import org.ort.data.Band
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.StationEntity
import org.ort.data.entity.TransmissionEntity
import org.robolectric.RobolectricTestRunner

/**
 * `Search-Empty.dc.html` (R-063): each widen option carries a real count, computed here, never
 * fabricated (guide §9) — [SearchWidenSuggestions.build] is the seam [SearchContent] calls once a
 * search comes back empty.
 */
@RunWith(RobolectricTestRunner::class)
class SearchWidenSuggestionsTest {

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val includeEverything =
        SearchFacetFilter(AttributionState.entries.toSet(), includeRejected = true, includeCorrected = true)

    @Before
    fun openDatabase() {
        context.deleteDatabase(OrtDatabase.DATABASE_NAME)
        db = OrtDatabase.create(context)
    }

    private fun session() = SessionEntity(
        id = "S1", startedAt = 0L, endedAt = null, profileId = null, deviceTier = null,
        appVersion = "test", terminationReason = null, sourceId = null, schemaVersion = OrtDatabase.SCHEMA_VERSION,
    )

    private fun station(id: String, callsign: String?) = StationEntity(
        id = id, callsign = callsign, firstHeardAt = null, lastHeardAt = null, notes = null, userName = null,
        frequenciesHeard = null, activityByHourDow = null, potaRefs = null, spokenGrids = null,
        ituRegionFromPrefix = null, overCountsByAttributionState = null,
    )

    private fun transmission(
        id: String,
        samplePosition: Long,
        stationId: String?,
        attributionState: AttributionState,
    ) = TransmissionEntity(
        id = id, sessionId = "S1", threadId = null, startedAtUtc = samplePosition,
        endedAtUtc = samplePosition + 1000L,
        durationMs = 1000L, audioFormat = "flac/16k/mono", preRollMs = 200, postRollMs = 200,
        frequencyHz = 145_230_000L, frequencyProvenance = "measured", mode = null, signalStrength = null,
        channelName = null, voiceprintId = null, attributionState = attributionState, stationId = stationId,
        attributionConfidence = null, attributionSourceTransmissionId = null,
        processingState = org.ort.core.TransmissionState.CAPTURED, rejectionReason = null,
        samplePosition = samplePosition, monotonicStartNanos = 0L, utcOffsetMinutes = 0, calibrationId = null,
        executionProvider = null,
    )

    @Test
    fun `an all-nights widen option is offered with a real count when the time filter narrowed to nothing`(): Unit =
        runTest {
            db.sessionDao().insert(session())
            // Well outside "tonight"'s window relative to `now` below.
            db.transmissionDao().insert(transmission("TX1", 1L, null, AttributionState.CONFIRMED))

            val input = SearchFilterInput(timeFilter = SearchTimeFilter.TONIGHT)
            val now = 4_000_000_000_000L // far in the future relative to samplePosition 1L
            val params = SearchFilterParser.parse(input, now)
            val facetFilter = SearchFacetFilter.from(input)
            val facetCounts = SearchFacetCounts(emptyList())

            val widen = SearchWidenSuggestions.build(context, input, params, facetFilter, facetCounts)

            val allNights = widen.options.single { it.id == "all_nights" }
            assertTrue(allNights.detail.contains("1 over"))
        }

    @Test
    fun `R_503 an include-inferred-and-ambiguous widen option is offered from facetCounts alone`(): Unit = runTest {
        // `Search-Empty.dc.html`'s own second widen category — "Include inferred and ambiguous" —
        // replaces the previous, broader "every attribution state, rejected and corrected"; Unknown
        // is deliberately excluded from this row's own AMBIGUOUS-row count below (real signal only).
        val input = SearchFilterInput(attributionStates = setOf(AttributionState.CONFIRMED))
        val facetFilter = SearchFacetFilter.from(input)
        val facetCounts = SearchFacetCounts(
            listOf(
                SearchFacetRow(AttributionState.CONFIRMED, rejected = false, corrected = false),
                SearchFacetRow(AttributionState.AMBIGUOUS, rejected = false, corrected = false),
            ),
        )

        val widen = SearchWidenSuggestions.build(context, input, SearchQueryParams(), facetFilter, facetCounts)

        val option = widen.options.single { it.id == "include_inferred_ambiguous" }
        assertEquals("Include inferred and ambiguous", option.label)
        assertTrue(option.detail.contains("2 overs"))
    }

    @Test
    fun `R_503 no include-inferred-and-ambiguous option when neither state would add anything`(): Unit = runTest {
        val input = SearchFilterInput()
        val facetFilter = SearchFacetFilter.from(input)
        val facetCounts = SearchFacetCounts(listOf(SearchFacetRow(AttributionState.CONFIRMED, false, false)))

        val widen = SearchWidenSuggestions.build(context, input, SearchQueryParams(), facetFilter, facetCounts)

        assertEquals(0, widen.options.count { it.id == "include_inferred_ambiguous" })
    }

    @Test
    fun `no widen options are offered once every filter is already at its widest`(): Unit = runTest {
        val input = SearchFilterInput()
        val widen = SearchWidenSuggestions.build(
            context,
            input,
            SearchQueryParams(),
            includeEverything,
            SearchFacetCounts.EMPTY,
        )

        assertEquals(emptyList<SearchWidenOption>(), widen.options)
    }

    @Test
    fun `similar callsigns are offered only when a callsign filter is set`(): Unit = runTest {
        db.catalogDao().insert(station("ST1", "VE7ABD"))
        val input = SearchFilterInput(callsign = "VE7ABC")

        // R-372: sourced from `params.callsign`, the parsed value every real caller
        // (`SearchContent.kt`) actually passes — `SearchQueryParams(callsign = input.callsign)`
        // here rather than the bare `SearchFilterParser.parse(input, ...)` call, since this test's
        // own point is the callsign-set/callsign-unset behaviour, not the parser itself (that is
        // `SearchFilterParserTest`'s job).
        val widen = SearchWidenSuggestions.build(
            context,
            input,
            SearchQueryParams(callsign = input.callsign),
            includeEverything,
            SearchFacetCounts.EMPTY,
        )

        assertEquals(listOf("VE7ABD"), widen.similarCallsigns)
    }

    @Test
    fun `R_372 a callsign typo'd into the free-text query box still offers a similar-callsign suggestion`(): Unit =
        runTest {
            // The register's own worked example: KE7QRT (typed into the search box, never the
            // Filters sheet's dedicated callsign field) should offer KE7QRS, one edit away.
            db.catalogDao().insert(station("ST1", "KE7QRS"))
            val input = SearchFilterInput(text = "KE7QRT")
            val params = SearchFilterParser.parse(input, nowUtcMillis = 0L)

            val widen = SearchWidenSuggestions.build(context, input, params, includeEverything, SearchFacetCounts.EMPTY)

            assertEquals(listOf("KE7QRS"), widen.similarCallsigns)
        }

    @Test
    fun `R_372 a drop-frequency widen option offers the real count once the frequency filter alone is dropped`(): Unit =
        runTest {
            db.sessionDao().insert(session())
            // The shared `transmission()` helper above fixes frequencyHz at 145.230MHz (2M band).
            db.transmissionDao().insert(transmission("TX1", 1L, "ST1", AttributionState.CONFIRMED))

            val input = SearchFilterInput(band = Band.VHF_2M, frequencyMhz = "146.960")
            // band=2M genuinely matches TX1 (145.230MHz); frequencyHz is deliberately wrong
            // (146.960MHz) so the combined query matches nothing — exactly `Search-Empty.dc.html`'s
            // own scenario — while dropping *only* the frequency filter (band kept) matches TX1.
            val params = SearchQueryParams(frequencyHz = 146_960_000L, band = Band.VHF_2M)

            val widen = SearchWidenSuggestions.build(context, input, params, includeEverything, SearchFacetCounts.EMPTY)

            val dropFrequency = widen.options.single { it.id == "drop_frequency" }
            assertTrue(dropFrequency.detail.contains("1 over"))
            // Dropping band instead (frequency kept, still wrong) still excludes TX1 — a 0-count
            // drop option is never fabricated as a row.
            assertTrue(widen.options.none { it.id == "drop_band" })
        }

    @Test
    fun `the narrowing summary names which filters narrowed the search`(): Unit = runTest {
        val input = SearchFilterInput(callsign = "VE7ABC", timeFilter = SearchTimeFilter.TONIGHT)
        val widen = SearchWidenSuggestions.build(
            context,
            input,
            SearchQueryParams(),
            includeEverything,
            SearchFacetCounts.EMPTY,
        )

        assertTrue(widen.narrowingSummary.contains("callsign"))
        assertTrue(widen.narrowingSummary.contains("time"))
    }
}
