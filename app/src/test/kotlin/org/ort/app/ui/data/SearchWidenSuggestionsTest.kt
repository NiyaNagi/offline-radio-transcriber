package org.ort.app.ui.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.AttributionState
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
    fun `an include-everything widen option is offered from facetCounts alone, no extra query`(): Unit = runTest {
        val input = SearchFilterInput(attributionStates = setOf(AttributionState.CONFIRMED))
        val facetFilter = SearchFacetFilter.from(input)
        val facetCounts = SearchFacetCounts(
            listOf(
                SearchFacetRow(AttributionState.CONFIRMED, rejected = false, corrected = false),
                SearchFacetRow(AttributionState.UNKNOWN, rejected = false, corrected = false),
            ),
        )

        val widen = SearchWidenSuggestions.build(context, input, SearchQueryParams(), facetFilter, facetCounts)

        val includeAll = widen.options.single { it.id == "include_all_states" }
        assertTrue(includeAll.detail.contains("2 overs"))
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

        val widen = SearchWidenSuggestions.build(
            context,
            input,
            SearchQueryParams(),
            includeEverything,
            SearchFacetCounts.EMPTY,
        )

        assertEquals(listOf("VE7ABD"), widen.similarCallsigns)
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
