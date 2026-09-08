package org.ort.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.data.entity.ShedEventEntity
import org.ort.data.entity.ShedTrigger
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * FR-AST-5, FR-AST-6 → AC-53. Runs Room's [MigrationTestHelper] against the committed v1 fixture
 * (`:data/schemas/org.ort.data.OrtDatabase/1.json`, wired into the test classpath as an asset in
 * `build.gradle.kts`). There is exactly one released schema so far — this test is the harness
 * every future `Migration` in [OrtDatabase.MIGRATIONS] runs forward through to head.
 */
@RunWith(RobolectricTestRunner::class)
public class MigrationTest {

    @get:Rule
    public val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OrtDatabase::class.java,
    )

    @Test
    @Requirement("AC-53", "FR-AST-5", "FR-AST-6")
    public fun migration_from_the_v1_fixture_preserves_audio_and_superseded_transcripts() {
        val dbName = "migration-test-db"
        val v1 = helper.createDatabase(dbName, 1)
        v1.execSQL(
            "INSERT INTO session (id, startedAt, endedAt, profileId, deviceTier, appVersion, " +
                "terminationReason, sourceId, schemaVersion, gapCount, shedEvents) VALUES " +
                "('S1', 0, NULL, NULL, NULL, 'test', NULL, NULL, 1, 0, 0)",
        )
        v1.execSQL(
            "INSERT INTO transmission (id, sessionId, threadId, startedAtUtc, endedAtUtc, durationMs, " +
                "audioFormat, preRollMs, postRollMs, frequencyHz, frequencyProvenance, mode, signalStrength, " +
                "channelName, voiceprintId, attributionState, stationId, attributionConfidence, " +
                "attributionSourceTransmissionId, corrected, processingState, rejectionReason, samplePosition, " +
                "monotonicStartNanos, utcOffsetMinutes, calibrationId, enhancementApplied, executionProvider, " +
                "isReprocessCandidate) VALUES ('TX1', 'S1', NULL, 0, 1000, 1000, 'flac/16k/mono', 200, 200, " +
                "NULL, 'measured', NULL, NULL, NULL, NULL, 'UNKNOWN', NULL, NULL, NULL, 0, 'CAPTURED', NULL, " +
                "0, 0, 0, NULL, '', NULL, 0)",
        )
        // A superseded transcript (isCurrent = 0) and the current one (isCurrent = 1) — both must survive.
        v1.execSQL(
            "INSERT INTO transcript (id, transmissionId, pass, text, modelId, modelVersion, quantization, " +
                "decodeParams, noSpeechProb, confidence, isCurrent, createdAt) VALUES " +
                "('T1', 'TX1', 'B', 'first pass', 'm', '1', NULL, NULL, NULL, NULL, 0, 1)",
        )
        v1.execSQL(
            "INSERT INTO transcript (id, transmissionId, pass, text, modelId, modelVersion, quantization, " +
                "decodeParams, noSpeechProb, confidence, isCurrent, createdAt) VALUES " +
                "('T2', 'TX1', 'B', 'reprocessed', 'm', '1', NULL, NULL, NULL, NULL, 1, 2)",
        )
        v1.close()

        helper.runMigrationsAndValidate(dbName, 1, true, *OrtDatabase.MIGRATIONS)

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), OrtDatabase::class.java, dbName)
            .addMigrations(*OrtDatabase.MIGRATIONS)
            .build()
        try {
            val transmission = runBlocking { db.transmissionDao().getById("TX1") }
            assertEquals("flac/16k/mono", transmission!!.audioFormat) // audio survives

            val versions = runBlocking { db.transcriptDao().getAllVersions("TX1") }
            assertEquals(setOf("T1", "T2"), versions.map { it.id }.toSet()) // superseded transcript survives
            assertEquals("reprocessed", versions.single { it.isCurrent }.text)
        } finally {
            db.close()
        }
    }

    /**
     * F-021 — the v1 -> v2 migration adds `shed_event` without touching existing tables. Proves
     * both halves of FR-AST-5/6: pre-existing rows (session, transmission, transcript) survive
     * the migration, and the new table is immediately usable through [OrtDatabase.shedEventDao].
     */
    @Test
    @Requirement("AC-53", "FR-AST-5", "FR-AST-6")
    public fun migration_from_v1_to_v2_preserves_existing_rows_and_adds_the_shed_event_table() {
        val dbName = "migration-test-db-v2"
        val v1 = helper.createDatabase(dbName, 1)
        v1.execSQL(
            "INSERT INTO session (id, startedAt, endedAt, profileId, deviceTier, appVersion, " +
                "terminationReason, sourceId, schemaVersion, gapCount, shedEvents) VALUES " +
                "('S1', 0, NULL, NULL, NULL, 'test', NULL, NULL, 1, 0, 0)",
        )
        v1.execSQL(
            "INSERT INTO transmission (id, sessionId, threadId, startedAtUtc, endedAtUtc, durationMs, " +
                "audioFormat, preRollMs, postRollMs, frequencyHz, frequencyProvenance, mode, signalStrength, " +
                "channelName, voiceprintId, attributionState, stationId, attributionConfidence, " +
                "attributionSourceTransmissionId, corrected, processingState, rejectionReason, samplePosition, " +
                "monotonicStartNanos, utcOffsetMinutes, calibrationId, enhancementApplied, executionProvider, " +
                "isReprocessCandidate) VALUES ('TX1', 'S1', NULL, 0, 1000, 1000, 'flac/16k/mono', 200, 200, " +
                "NULL, 'measured', NULL, NULL, NULL, NULL, 'UNKNOWN', NULL, NULL, NULL, 0, 'CAPTURED', NULL, " +
                "0, 0, 0, NULL, '', NULL, 0)",
        )
        v1.close()

        helper.runMigrationsAndValidate(dbName, 2, true, OrtDatabase.MIGRATION_1_2)

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), OrtDatabase::class.java, dbName)
            .addMigrations(*OrtDatabase.MIGRATIONS)
            .build()
        try {
            val transmission = runBlocking { db.transmissionDao().getById("TX1") }
            assertEquals("flac/16k/mono", transmission!!.audioFormat) // pre-existing row survives

            runBlocking {
                db.shedEventDao().insert(
                    ShedEventEntity(
                        id = "SE1",
                        sessionId = "S1",
                        levelBefore = 0,
                        levelAfter = 1,
                        trigger = ShedTrigger.BACKLOG,
                        reason = "backlog 20 >= threshold",
                        atWallMillis = 1_000L,
                        atMonotonicNanos = 1_000_000_000L,
                        samplePosition = null,
                    ),
                )
            }
            val events = runBlocking { db.shedEventDao().listBySession("S1") }
            assertEquals(listOf("SE1"), events.map { it.id }) // new table works post-migration
        } finally {
            db.close()
        }
    }
}
