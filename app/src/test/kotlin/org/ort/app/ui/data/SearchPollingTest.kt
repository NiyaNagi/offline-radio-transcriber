package org.ort.app.ui.data

import androidx.room.useReaderConnection
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.Band
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.StationEntity
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TranscriptPass
import org.ort.data.entity.TransmissionEntity
import org.robolectric.RobolectricTestRunner

/**
 * FR-UI-3, over the real DAOs — no fake stands in for `:data` here, matching
 * [org.ort.app.ui.data.ReaderPollingTest]'s own reasoning.
 *
 * R-061: [SearchDao] takes one nullable attribution state and one nullable rejected flag, but the
 * filter sheet needs a *set* of states and two independent include toggles — so [SearchPolling]
 * queries with those unset (getting the widest base result) and applies [SearchFacetFilter]
 * client-side; [SearchResult.facetCounts] carries the pre-facet-filter breakdown so the filter
 * sheet can show real per-state counts.
 *
 * This project's own `:data` test suite
 * (`data/src/test/kotlin/org/ort/data/SearchDaoFullTextTest.kt`) empirically confirms the fts5
 * module is unavailable under this Robolectric host's SQLite build — so the full-text path
 * genuinely cannot be exercised end to end here. What *can* be proven for real, in this exact
 * environment, is that [SearchPolling] does not crash when that happens: it degrades to the
 * filter-only path and honestly reports that the text term was not applied
 * ([SearchResult.textSearchUnavailable]), rather than silently dropping the user's search term or
 * propagating a raw `SQLiteException` to the screen.
 */
@RunWith(RobolectricTestRunner::class)
class SearchPollingTest {

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val includeEverything =
        SearchFacetFilter(AttributionState.entries.toSet(), includeRejected = true, includeCorrected = true)

    @Before
    fun openDatabase() {
        // Not in-memory, for the same reason ReaderPollingTest isn't: SearchPolling opens its own
        // OrtDatabase.create(context) internally, and this test needs to see what that call sees.
        context.deleteDatabase(OrtDatabase.DATABASE_NAME)
        db = OrtDatabase.create(context)
    }

    private fun session() = SessionEntity(
        id = "S1",
        startedAt = 0L,
        endedAt = null,
        profileId = null,
        deviceTier = null,
        appVersion = "test",
        terminationReason = null,
        sourceId = null,
        schemaVersion = OrtDatabase.SCHEMA_VERSION,
    )

    private fun station(id: String, callsign: String?) = StationEntity(
        id = id,
        callsign = callsign,
        firstHeardAt = null,
        lastHeardAt = null,
        notes = null,
        userName = null,
        frequenciesHeard = null,
        activityByHourDow = null,
        potaRefs = null,
        spokenGrids = null,
        ituRegionFromPrefix = null,
        overCountsByAttributionState = null,
    )

    private fun transmission(
        id: String,
        samplePosition: Long,
        stationId: String?,
        frequencyHz: Long?,
        attributionState: AttributionState = AttributionState.UNKNOWN,
        processingState: TransmissionState = TransmissionState.CAPTURED,
        corrected: Boolean = false,
    ) = TransmissionEntity(
        id = id,
        sessionId = "S1",
        threadId = null,
        startedAtUtc = samplePosition,
        endedAtUtc = samplePosition + 1_000L,
        durationMs = 1_000L,
        audioFormat = "flac/16k/mono",
        preRollMs = 200,
        postRollMs = 200,
        frequencyHz = frequencyHz,
        frequencyProvenance = "measured",
        mode = null,
        signalStrength = null,
        channelName = null,
        voiceprintId = null,
        attributionState = attributionState,
        stationId = stationId,
        attributionConfidence = null,
        attributionSourceTransmissionId = null,
        corrected = corrected,
        processingState = processingState,
        rejectionReason = null,
        samplePosition = samplePosition,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )

    @Test
    fun `FR_UI_3 filtering by callsign works with no text term`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.catalogDao().insert(station("ST1", "W7NPC"))
        db.transmissionDao().insert(transmission("TX1", 1L, "ST1", 145_230_000L))
        db.transmissionDao().insert(transmission("TX2", 2L, null, 146_520_000L))

        val result = SearchPolling.search(
            context,
            SearchQueryParams(text = null, callsign = "W7NPC"),
            includeEverything,
        )

        assertEquals(listOf("TX1"), result.details.map { it.id })
        assertFalse(result.textSearchUnavailable)
    }

    // R-204: fts5 is now always available (BundledSQLiteDriver bundles a SQLite build with fts5
    // compiled in) — this stays a real probe, not a hardcoded `true`, so a regression back to
    // "fts5 unavailable" still fails this test loudly instead of the assertion silently agreeing
    // with whatever `SearchPolling` did.
    private suspend fun fts5Available(): Boolean = try {
        db.useReaderConnection { connection ->
            connection.usePrepared("SELECT count(*) FROM transcript_fts") { it.step() }
        }
        true
    } catch (e: android.database.SQLException) {
        false
    }

    @Test
    fun `FR_UI_3 a text search either runs for real or degrades to filters and reports which happened`(): Unit =
        runTest {
            db.sessionDao().insert(session())
            db.catalogDao().insert(station("ST1", "W7NPC"))
            db.transmissionDao().insert(transmission("TX1", 1L, "ST1", 145_230_000L))
            db.transmissionDao().insert(transmission("TX2", 2L, null, 146_520_000L))
            if (fts5Available()) {
                db.transcriptDao().supersede(
                    TranscriptEntity(
                        id = "T1",
                        transmissionId = "TX1",
                        pass = TranscriptPass.B,
                        text = "mayday mayday",
                        modelId = "distil-small.en",
                        modelVersion = "1",
                        quantization = null,
                        decodeParams = null,
                        noSpeechProb = null,
                        confidence = 0.9,
                        isCurrent = true,
                        createdAt = 0L,
                    ),
                )
            }

            val result = SearchPolling.search(
                context,
                SearchQueryParams(text = "mayday", callsign = "W7NPC"),
                includeEverything,
            )

            assertEquals(listOf("TX1"), result.details.map { it.id })
            assertEquals(!fts5Available(), result.textSearchUnavailable)
        }

    @Test
    fun `FR_UI_3 the band filter narrows results to one amateur band`(): Unit = runTest {
        db.sessionDao().insert(session())
        // TX1 sits in the 2M band (144-148 MHz), TX2 in 70CM (420-450 MHz).
        db.transmissionDao().insert(transmission("TX1", 1L, null, 145_230_000L))
        db.transmissionDao().insert(transmission("TX2", 2L, null, 440_000_000L))

        val result = SearchPolling.search(
            context,
            SearchQueryParams(text = null, band = Band.VHF_2M),
            includeEverything,
        )

        assertEquals(listOf("TX1"), result.details.map { it.id })
        assertFalse(result.textSearchUnavailable)
    }

    @Test
    fun `R_061 the facet filter narrows displayed results to the selected attribution states`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(
            transmission("TX1", 1L, null, 145_230_000L, attributionState = AttributionState.CONFIRMED),
        )
        db.transmissionDao().insert(
            transmission("TX2", 2L, null, 146_520_000L, attributionState = AttributionState.UNKNOWN),
        )

        val facet =
            SearchFacetFilter(setOf(AttributionState.CONFIRMED), includeRejected = true, includeCorrected = true)
        val result = SearchPolling.search(context, SearchQueryParams(text = null), facet)

        assertEquals(listOf("TX1"), result.details.map { it.id })
    }

    @Test
    fun `R_061 the facet filter narrows rejected and corrected independently of attribution state`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(
            transmission("TX1", 1L, null, 145_230_000L, processingState = TransmissionState.REJECTED),
        )
        db.transmissionDao().insert(transmission("TX2", 2L, null, 146_520_000L, corrected = true))
        db.transmissionDao().insert(transmission("TX3", 3L, null, 147_000_000L))

        val excludeBoth =
            SearchFacetFilter(AttributionState.entries.toSet(), includeRejected = false, includeCorrected = false)
        val result = SearchPolling.search(context, SearchQueryParams(text = null), excludeBoth)

        assertEquals(listOf("TX3"), result.details.map { it.id })
    }

    @Test
    fun `R_061 facetCounts reflects every match before the facet filter, never fabricated`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(
            transmission("TX1", 1L, null, 145_230_000L, attributionState = AttributionState.CONFIRMED),
        )
        db.transmissionDao().insert(
            transmission("TX2", 2L, null, 146_520_000L, attributionState = AttributionState.CONFIRMED),
        )
        db.transmissionDao().insert(
            transmission("TX3", 3L, null, 147_000_000L, attributionState = AttributionState.UNKNOWN),
        )
        db.transmissionDao().insert(
            transmission(
                "TX4",
                4L,
                null,
                148_000_000L,
                attributionState = AttributionState.UNKNOWN,
                processingState = TransmissionState.REJECTED,
            ),
        )

        // A facet filter that hides most rows must not affect facetCounts, which describes the
        // *whole* base match — the sheet needs that to compute "how many if I include this too".
        val onlyConfirmed =
            SearchFacetFilter(setOf(AttributionState.CONFIRMED), includeRejected = false, includeCorrected = true)
        val result = SearchPolling.search(context, SearchQueryParams(text = null), onlyConfirmed)

        assertEquals(4, result.facetCounts.total)
        assertEquals(2, result.facetCounts.confirmed)
        assertEquals(2, result.facetCounts.unknown)
        assertEquals(1, result.facetCounts.rejectedCount)
        assertEquals(2, result.details.size) // only the two CONFIRMED, non-rejected rows shown
    }
}
