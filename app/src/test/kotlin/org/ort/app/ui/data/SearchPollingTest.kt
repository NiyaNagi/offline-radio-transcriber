package org.ort.app.ui.data

import android.database.sqlite.SQLiteException
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

    @Before
    fun openDatabase() {
        // Not in-memory, for the same reason ReaderPollingTest isn't: SearchPolling opens its own
        // OrtDatabase.create(context) internally, and this test needs to see what that call sees.
        // Deleted first: Robolectric's file-backed "ort.db" can otherwise persist a schema created
        // by an earlier test class in this module (its `onCreate` runs exactly once per physical
        // file) — including a `transcript_fts` `sqlite_master` row from a run where fts5 happened
        // to be loadable, which would then report as "available" here even though the *current*
        // native SQLite build cannot actually execute an fts5 query against it. Starting from a
        // clean file makes `fts5Available()` reflect this run's real environment.
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

        val result = SearchPolling.search(context, SearchQueryParams(text = null, callsign = "W7NPC"))

        assertEquals(listOf("TX1"), result.details.map { it.id })
        assertFalse(result.textSearchUnavailable)
    }

    /**
     * A `sqlite_master` row for `transcript_fts` can exist even when fts5 is genuinely
     * unavailable — SQLite's `CREATE VIRTUAL TABLE IF NOT EXISTS ... USING fts5(...)` can leave a
     * schema entry behind even though the module lookup that follows it fails with
     * `no such module: fts5` (confirmed empirically in this build-plan P15 session: checking
     * `sqlite_master` alone reported "available" while every real query against the table then
     * threw exactly that error). Actually preparing a statement against the table is the only
     * reliable check.
     */
    private fun fts5Available(): Boolean = try {
        db.openHelper.writableDatabase.query("SELECT count(*) FROM transcript_fts").use { it.moveToFirst() }
        true
    } catch (e: SQLiteException) {
        false
    }

    /**
     * Branches on the same fts5-availability fact `:data`'s own
     * `SearchDaoFullTextTest` establishes, so this test states a real assertion either way rather
     * than hard-coding an assumption about the host that could go stale: if fts5 genuinely is
     * unavailable (the case empirically confirmed for this build-plan P15 session), a text search
     * must degrade to the filter-only path and say so; if a future host does carry fts5, the same
     * call must actually search text and say so.
     */
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

            val result = SearchPolling.search(context, SearchQueryParams(text = "mayday", callsign = "W7NPC"))

            assertEquals(listOf("TX1"), result.details.map { it.id })
            assertEquals(!fts5Available(), result.textSearchUnavailable)
        }

    @Test
    fun `FR_UI_3 the band filter narrows results to one amateur band`(): Unit = runTest {
        db.sessionDao().insert(session())
        // TX1 sits in the 2M band (144-148 MHz), TX2 in 70CM (420-450 MHz).
        db.transmissionDao().insert(transmission("TX1", 1L, null, 145_230_000L))
        db.transmissionDao().insert(transmission("TX2", 2L, null, 440_000_000L))

        val result = SearchPolling.search(context, SearchQueryParams(text = null, band = Band.VHF_2M))

        assertEquals(listOf("TX1"), result.details.map { it.id })
        assertFalse(result.textSearchUnavailable)
    }

    @Test
    fun `FR_UI_3 the attribution-state filter narrows results to one state`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(
            transmission("TX1", 1L, null, 145_230_000L, attributionState = AttributionState.CONFIRMED),
        )
        db.transmissionDao().insert(
            transmission("TX2", 2L, null, 146_520_000L, attributionState = AttributionState.UNKNOWN),
        )

        val result = SearchPolling.search(
            context,
            SearchQueryParams(text = null, attributionState = AttributionState.CONFIRMED),
        )

        assertEquals(listOf("TX1"), result.details.map { it.id })
        assertFalse(result.textSearchUnavailable)
    }

    @Test
    fun `FR_UI_3 the rejected filter narrows results to only-rejected or only-accepted`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(
            transmission("TX1", 1L, null, 145_230_000L, processingState = TransmissionState.REJECTED),
        )
        db.transmissionDao().insert(
            transmission("TX2", 2L, null, 146_520_000L, processingState = TransmissionState.COMPLETE),
        )

        val rejectedOnly = SearchPolling.search(context, SearchQueryParams(text = null, rejected = true))
        val acceptedOnly = SearchPolling.search(context, SearchQueryParams(text = null, rejected = false))
        val all = SearchPolling.search(context, SearchQueryParams(text = null, rejected = null))

        assertEquals(listOf("TX1"), rejectedOnly.details.map { it.id })
        assertEquals(listOf("TX2"), acceptedOnly.details.map { it.id })
        assertEquals(setOf("TX1", "TX2"), all.details.map { it.id }.toSet())
    }
}
