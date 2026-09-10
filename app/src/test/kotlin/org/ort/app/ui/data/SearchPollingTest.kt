package org.ort.app.ui.data

import androidx.room.useReaderConnection
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.debug.Scenarios
import org.ort.core.AttributionState
import org.ort.core.SystemClock
import org.ort.core.TransmissionState
import org.ort.data.Band
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.StationEntity
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TranscriptPass
import org.ort.data.entity.TransmissionEntity
import org.ort.data.execRaw
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

    // R-371: routed through the real search path — SearchFilterParser.parse (the free-text shape
    // router) into SearchPolling.search (the real DAO) — against the same `search-corpus` fixture
    // debug scenario the register's own validator loaded on the device (`Scenarios.load`, the
    // debug source set `ScenariosTest` also depends on). 14 overs across 3 nights, alternating
    // 146.960/145.230 MHz by index, every transcript reading "this is <callsign>, doing a park
    // activation at K-<4400+i>, any hunters listening" (`Scenarios.kt`'s own `searchCorpus` doc
    // comment: "8 hits on 146.960" matches this fixture's own i%2==0 alternation exactly).

    private val includeEverythingInput = SearchFilterInput(attributionStates = AttributionState.entries.toSet())

    @Test
    fun R_371_frequency_query(): Unit = runTest {
        Scenarios.load(context, "search-corpus")

        // Both the app's own TRY hint shapes — "146.96" (2 decimals) and "146.960" (3) — must
        // resolve to the identical 146,960,000 Hz filter, never literal FTS text (which the
        // register found always returned "Nothing matched", although the corpus has 8 overs on
        // that exact frequency — the Filters chip for the same frequency narrows "park" to 8).
        for (typed in listOf("146.96", "146.960")) {
            val params = SearchFilterParser.parse(SearchFilterInput(text = typed), SystemClock.wallMillis())
            val result = SearchPolling.search(context, params, SearchFacetFilter.from(includeEverythingInput))

            assertEquals("typing '$typed'", 8, result.details.size)
            assertTrue(
                "expected every result to be on 146.960MHz for '$typed', got " +
                    result.details.map { it.frequencyHz },
                result.details.all { it.frequencyHz == 146_960_000L },
            )
            assertFalse(result.textSearchUnavailable)
        }
    }

    @Test
    fun R_371_mixed_query(): Unit = runTest {
        Scenarios.load(context, "search-corpus")

        // "K-4400" (the POTA-style reference embedded in the first over of every night, i=0 — see
        // `Scenarios.kt`'s own `searchCorpus`) narrows the 8-over frequency match down to exactly
        // the 3 nights' own i=0 over — proving the routed frequency filter and the *remaining* FTS
        // text term both actually apply, ANDed, not one silently dropping the other.
        val params = SearchFilterParser.parse(SearchFilterInput(text = "146.96 K-4400"), SystemClock.wallMillis())
        val result = SearchPolling.search(context, params, SearchFacetFilter.from(includeEverythingInput))

        assertEquals(3, result.details.size)
        assertTrue(result.details.all { it.frequencyHz == 146_960_000L })
        assertTrue(result.details.all { it.currentTranscriptText?.contains("K-4400") == true })
        assertFalse(result.textSearchUnavailable)
    }

    // R-502: the register's own diagnosis request — "decide with a test that submits the same
    // string through the same seam" — whether `TextQueryRouter`'s callsign route drops the hit, or
    // the seam never reaches it. Routed through the identical real path every other test in this
    // class uses (`SearchFilterParser.parse` into `SearchPolling.search`), against the real
    // `search-corpus` fixture. Root cause found and fixed elsewhere (`Scenarios.kt`'s own
    // `searchCorpus`, this package's CHANGELOG entry): the fixture never inserted a `StationEntity`
    // row for any callsign, so `SearchDao`'s `LEFT JOIN station` always missed — not a routing bug.
    @Test
    fun R_502_callsign_query(): Unit = runTest {
        Scenarios.load(context, "search-corpus")

        val params = SearchFilterParser.parse(SearchFilterInput(text = "KE7QRS"), SystemClock.wallMillis())
        val result = SearchPolling.search(context, params, SearchFacetFilter.from(includeEverythingInput))

        assertTrue("expected at least one hit for KE7QRS, got none", result.details.isNotEmpty())
        assertTrue(
            "expected every result to actually be KE7QRS, got ${result.details.map { it.attribution.stationId }}",
            result.details.all { it.attribution.stationId == "KE7QRS" },
        )
        assertTrue(
            "expected every result's transcript to contain the literal callsign",
            result.details.all { it.currentTranscriptText?.contains("KE7QRS") == true },
        )
        assertFalse(result.textSearchUnavailable)
    }

    // This task (register R-204 follow-up, FR-UI-3): SearchPolling.search/facetCounts and
    // SearchWidenSuggestions' own countMatching used to decide "fts5 is missing" by catching
    // SQLException and parsing its message for "fts5"/"transcript_fts" — proven, by a three-run CI
    // investigation (commit 5a9f53a), to read text that differs by platform for the identical
    // failure. They now ask OrtDatabase.hasTextSearchIndex() (a positive, schema-level fact)
    // before running a text query at all — see resolveTextSearch's own doc comment.
    //
    // What is NOT tested here, and why: an earlier version of this test tried to simulate a
    // genuinely fts5-less build by dropping `transcript_fts` (and its sync triggers) on this
    // class's own `db` handle, on the theory that OrtDatabase.create's self-heal (R-204 —
    // applyHandWrittenSchema runs on every create() call, and repairs a missing index) would
    // leave a *different* database instance to see the drop. It does not: this Robolectric host's
    // SQLite genuinely has fts5 (confirmed directly — see `:data`'s own
    // FtsCapabilityProbeTest.FR_UI_3_the_probe_itself_reports_fts5_present_on_the_real_driver), so
    // every OrtDatabase.create() call — including the one inside SearchPolling.search itself —
    // correctly, honestly rebuilds the index that this test had dropped, exactly as R-204 designed
    // it to. That is the system working as intended, not a gap in it; it just means schema
    // tampering cannot stand in for a genuinely fts5-less driver in this environment. The
    // fts5-absent half of *this* decision is proven instead, for real, by
    // SearchTextAvailabilityTest against resolveTextSearch — the pure function this file's
    // SearchPolling/SearchWidenSuggestions now share for exactly this reason — and the
    // fts5-absent half of the probe underneath it is proven by `:data`'s own
    // FtsCapabilityProbeTest.FR_UI_3_a_build_without_fts5_opens_honestly_without_the_index_instead_of_throwing
    // via the test seam OrtDatabase.fts5SupportOverrideForTest introduces for that reason.

    @Test
    fun `FR_UI_3 a real database error still propagates, not mistaken for missing fts5`(): Unit = runTest {
        db.sessionDao().insert(session())
        // transcript_fts is left intact -- hasTextSearchIndex() reports "available" -- so the only
        // way this can fail is the query itself, against a genuinely broken, unrelated table. That
        // failure must reach the caller as a real error, never be silently folded into the
        // missing-fts5 fallback.
        db.execRaw("DROP TABLE transmission")

        var threw = false
        try {
            SearchPolling.search(context, SearchQueryParams(text = "anything"), includeEverything)
        } catch (e: android.database.SQLException) {
            threw = true
        }
        assertTrue("a real database error (dropped transmission table) must propagate, not be swallowed", threw)
    }
}
