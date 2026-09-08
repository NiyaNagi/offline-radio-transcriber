package org.ort.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.room.useReaderConnection
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * Register R-204 (halt, FR-UI-3): the API 34 reference emulator's platform SQLite has no `fts5`
 * module — every text query failed with "no such module: fts5" and search degraded to
 * "unavailable" for every user, 100% reproducible. [OrtDatabase.create] now always installs
 * `BundledSQLiteDriver` (`androidx.sqlite:sqlite-bundled`), a SQLite build with fts5 compiled in,
 * bypassing the platform/host SQLite entirely — this class is the two checks that decision needs:
 * that fts5 is actually available through it, and that a database whose `transcript_fts` table
 * was never built (any install predating this change) gets it built, and backfilled, the moment
 * it is opened.
 */
@RunWith(RobolectricTestRunner::class)
public class FtsIndexRepairTest {

    @get:Rule
    public val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OrtDatabase::class.java,
    )

    @Test
    @Requirement("FR-UI-3", "R-204")
    public fun FR_UI_3_fts5_is_available_on_every_supported_sqlite(): Unit = runTest {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        try {
            // The load-bearing check is that this MATCH query executes at all — register R-204
            // diagnosed exactly this call throwing `no such module: fts5` on the reference
            // emulator's platform SQLite (and, independently, on this project's host-JVM/
            // Robolectric SQLite before BundledSQLiteDriver). A count of 0 against an empty
            // table is the expected, successful result — not a fallback value.
            val hits = db.useReaderConnection { connection ->
                connection.usePrepared("SELECT count(*) FROM transcript_fts WHERE transcript_fts MATCH 'nothing'") {
                    it.step()
                    it.getLong(0)
                }
            }
            assertEquals(0L, hits)
        } finally {
            db.close()
        }
    }

    /**
     * Simulates a database that reached the current schema version without ever running
     * [OrtDatabase.createHandWrittenSchema] — the state any real install predating this change
     * (or, before it, one opened on a platform whose SQLite lacked fts5 entirely) is in:
     * [MigrationTestHelper.createDatabase] builds only the tables Room's `@Entity` annotations
     * declare, from the committed schema fixture, the same way it builds every versioned fixture
     * the other `MigrationTest` cases start from — it does not run any `RoomDatabase.Callback`.
     * A transcript row is written *before* the index exists, so finding it after opening proves
     * both halves of the repair: the table gets built, and existing rows are backfilled into it —
     * not just rows written afterwards, which the sync triggers alone would already cover.
     */
    @Test
    @Requirement("FR-UI-3", "R-204", "AC-53")
    public fun FR_UI_3_an_install_without_the_index_gets_it_built_on_open(): Unit = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "fts-repair-test.db"
        context.deleteDatabase(dbName)
        try {
            val fixture = helper.createDatabase(dbName, OrtDatabase.SCHEMA_VERSION)
            fixture.execSQL(
                "INSERT INTO session (id, startedAt, endedAt, profileId, deviceTier, appVersion, " +
                    "terminationReason, sourceId, schemaVersion, gapCount, shedEvents) VALUES " +
                    "('S1', 0, NULL, NULL, NULL, 'test', NULL, NULL, ${OrtDatabase.SCHEMA_VERSION}, 0, 0)",
            )
            fixture.execSQL(
                "INSERT INTO transmission (id, sessionId, threadId, startedAtUtc, endedAtUtc, durationMs, " +
                    "audioFormat, preRollMs, postRollMs, frequencyHz, frequencyProvenance, mode, signalStrength, " +
                    "channelName, voiceprintId, attributionState, stationId, attributionConfidence, " +
                    "attributionSourceTransmissionId, corrected, processingState, rejectionReason, samplePosition, " +
                    "monotonicStartNanos, utcOffsetMinutes, calibrationId, enhancementApplied, executionProvider, " +
                    "isReprocessCandidate) VALUES ('TX1', 'S1', NULL, 0, 1000, 1000, 'flac/16k/mono', 200, 200, " +
                    "NULL, 'measured', NULL, NULL, NULL, NULL, 'UNKNOWN', NULL, NULL, NULL, 0, 'CAPTURED', NULL, " +
                    "0, 0, 0, NULL, '', NULL, 0)",
            )
            fixture.execSQL(
                "INSERT INTO transcript (id, transmissionId, pass, text, modelId, modelVersion, quantization, " +
                    "decodeParams, noSpeechProb, confidence, isCurrent, createdAt) VALUES " +
                    "('T1', 'TX1', 'B', 'whiskey seven november papa charlie', 'm', '1', NULL, NULL, NULL, NULL, 1, 1)",
            )
            fixture.close()

            // No CREATE VIRTUAL TABLE has ever run against this file — opening it is the only
            // trigger; createHandWrittenSchema (via onOpen) must both build the table and
            // backfill the row written above.
            val db = OrtDatabase.create(context, name = dbName, inMemory = false)
            try {
                val preExisting = db.searchDao().search(text = "whiskey")
                assertEquals(listOf("TX1"), preExisting.map { it.id })

                // And the triggers it installed cover rows written after the repair too.
                db.transmissionDao().insert(TestFixtures.transmission("TX2", sessionId = "S1", samplePosition = 1L))
                db.transcriptDao().supersede(
                    org.ort.data.entity.TranscriptEntity(
                        id = "T2",
                        transmissionId = "TX2",
                        pass = org.ort.data.entity.TranscriptPass.B,
                        text = "unrelated traffic on the repeater",
                        modelId = "m",
                        modelVersion = "1",
                        quantization = null,
                        decodeParams = null,
                        noSpeechProb = null,
                        confidence = null,
                        isCurrent = true,
                        createdAt = 2L,
                    ),
                )
                val stillFindsTheOldOne = db.searchDao().search(text = "whiskey")
                assertEquals(listOf("TX1"), stillFindsTheOldOne.map { it.id })
                val findsTheNewOne = db.searchDao().search(text = "repeater")
                assertEquals(listOf("TX2"), findsTheNewOne.map { it.id })
            } finally {
                db.close()
            }
        } finally {
            context.deleteDatabase(dbName)
        }
    }
}
