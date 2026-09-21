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
import org.ort.core.AttributionState
import org.ort.core.Tier
import org.ort.core.capture.VadDetectorKind
import org.ort.data.dao.StationIdentityDao
import org.ort.data.entity.CorrectionEntity
import org.ort.data.entity.PriorAdjustmentEntity
import org.ort.data.entity.ShedEventEntity
import org.ort.data.entity.ShedTrigger
import org.ort.data.entity.StationIdentityHistoryEntity
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * FR-AST-5, FR-AST-6 → AC-53. Runs Room's [MigrationTestHelper] against the committed v1 fixture
 * (`:data/schemas/org.ort.data.OrtDatabase/1.json`, wired into the test classpath as an asset in
 * `build.gradle.kts`). There is exactly one released schema so far — this test is the harness
 * every future `Migration` in [OrtDatabase.MIGRATIONS] runs forward through to head.
 */
// Deliberately large, not sloppy: by this file's own design (see kdoc below and the two
// "every prior fixture" sweeps), every new schema version adds one v(N-1)->vN test here and
// widens both sweep loops by one -- that is the intended growth, not a refactor smell.
@Suppress("LargeClass")
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

    /**
     * Register R-052, R-073 — the v2 -> v3 migration adds `station_identity_history`,
     * `voiceprint_binding_history` and `prior_adjustment` without touching existing tables.
     * Proves both halves of FR-AST-5/6: a pre-existing station row survives, and
     * [OrtDatabase.stationIdentityDao]'s write path is immediately usable afterwards.
     */
    @Test
    @Requirement("AC-53", "FR-AST-5", "FR-AST-6", "R-052", "R-073")
    public fun migration_from_v2_to_v3_preserves_existing_rows_and_adds_the_identity_history_tables() {
        val dbName = "migration-test-db-v3"
        val v2 = helper.createDatabase(dbName, 2)
        v2.execSQL(
            "INSERT INTO station (id, callsign, firstHeardAt, lastHeardAt, transmissionCount, " +
                "isUserPinned, notes, userName, frequenciesHeard, activityByHourDow, potaRefs, " +
                "spokenGrids, ituRegionFromPrefix, overCountsByAttributionState) VALUES " +
                "('N7XYZ', 'N7XYZ', 0, 0, 1, 0, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)",
        )
        v2.close()

        helper.runMigrationsAndValidate(dbName, 3, true, OrtDatabase.MIGRATION_2_3)

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), OrtDatabase::class.java, dbName)
            .addMigrations(*OrtDatabase.MIGRATIONS)
            .build()
        try {
            val station = runBlocking { db.catalogDao().getStation("N7XYZ") }
            assertEquals("N7XYZ", station!!.callsign) // pre-existing row survives

            runBlocking {
                db.stationIdentityDao().renameStation(
                    StationIdentityHistoryEntity(
                        id = "H1",
                        stationId = "N7XYZ",
                        field = StationIdentityDao.FIELD_NAME,
                        previousValue = null,
                        newValue = "Dave",
                        changedAt = 100L,
                    ),
                )
                db.stationIdentityDao().updatePriorWeight(
                    PriorAdjustmentEntity(
                        id = "P1",
                        stationId = "N7XYZ",
                        name = "on_this_repeater",
                        weight = 0.2,
                        reason = null,
                        isCurrent = true,
                        updatedAt = 100L,
                    ),
                )
            }
            val renamed = runBlocking { db.catalogDao().getStation("N7XYZ") }
            assertEquals("Dave", renamed!!.userName) // new tables usable post-migration
            val prior = runBlocking { db.stationIdentityDao().currentPriorWeight("N7XYZ", "on_this_repeater") }
            assertEquals(0.2, prior!!.weight, 0.0)
        } finally {
            db.close()
        }
    }

    /**
     * Register R-204 follow-up (FR-REP-2, FR-REP-9) — the v3 -> v4 migration adds
     * `transmission.processedTier` without touching any existing column. Proves both halves of
     * FR-AST-5/6: a pre-existing transmission row survives with its real data intact, and the new
     * column defaults to `NULL` (never processed) until [OrtDatabase.transmissionDao]'s new write
     * path is used.
     */
    @Test
    @Requirement("AC-53", "FR-AST-5", "FR-AST-6", "FR-REP-2", "FR-REP-9", "R-204")
    public fun migration_from_v3_to_v4_preserves_existing_rows_and_adds_the_processed_tier_column() {
        val dbName = "migration-test-db-v4"
        val v3 = helper.createDatabase(dbName, 3)
        v3.execSQL(
            "INSERT INTO session (id, startedAt, endedAt, profileId, deviceTier, appVersion, " +
                "terminationReason, sourceId, schemaVersion, gapCount, shedEvents) VALUES " +
                "('S1', 0, NULL, NULL, NULL, 'test', NULL, NULL, 1, 0, 0)",
        )
        v3.execSQL(
            "INSERT INTO transmission (id, sessionId, threadId, startedAtUtc, endedAtUtc, durationMs, " +
                "audioFormat, preRollMs, postRollMs, frequencyHz, frequencyProvenance, mode, signalStrength, " +
                "channelName, voiceprintId, attributionState, stationId, attributionConfidence, " +
                "attributionSourceTransmissionId, corrected, processingState, rejectionReason, samplePosition, " +
                "monotonicStartNanos, utcOffsetMinutes, calibrationId, enhancementApplied, executionProvider, " +
                "isReprocessCandidate) VALUES ('TX1', 'S1', NULL, 0, 1000, 1000, 'flac/16k/mono', 200, 200, " +
                "NULL, 'measured', NULL, NULL, NULL, NULL, 'UNKNOWN', NULL, NULL, NULL, 0, 'CAPTURED', NULL, " +
                "0, 0, 0, NULL, '', NULL, 0)",
        )
        v3.close()

        helper.runMigrationsAndValidate(dbName, 4, true, OrtDatabase.MIGRATION_3_4)

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), OrtDatabase::class.java, dbName)
            .addMigrations(*OrtDatabase.MIGRATIONS)
            .build()
        try {
            val transmission = runBlocking { db.transmissionDao().getById("TX1") }
            assertEquals("flac/16k/mono", transmission!!.audioFormat) // pre-existing row survives
            assertEquals(null, transmission.processedTier) // new column defaults to "never processed"

            runBlocking { db.transmissionDao().setProcessedTier("TX1", Tier.T2) }
            val processed = runBlocking { db.transmissionDao().getById("TX1") }
            assertEquals(Tier.T2, processed!!.processedTier) // new write path usable post-migration
        } finally {
            db.close()
        }
    }

    /**
     * Register R-321 — the v4 -> v5 migration adds `correction.previousAttributionState` and its
     * three siblings without touching any existing column. Proves both halves of FR-AST-5/6: a
     * pre-existing correction row survives with its real `newValue` intact, and the four new
     * columns default to `NULL` until [OrtDatabase.correctionDao]'s write path stamps them.
     */
    @Test
    @Requirement("AC-53", "FR-AST-5", "FR-AST-6", "R-321")
    public fun migration_from_v4_to_v5_preserves_existing_rows_and_adds_the_previous_attribution_columns() {
        val dbName = "migration-test-db-v5"
        val v4 = helper.createDatabase(dbName, 4)
        v4.execSQL(
            "INSERT INTO session (id, startedAt, endedAt, profileId, deviceTier, appVersion, " +
                "terminationReason, sourceId, schemaVersion, gapCount, shedEvents) VALUES " +
                "('S1', 0, NULL, NULL, NULL, 'test', NULL, NULL, 1, 0, 0)",
        )
        v4.execSQL(
            "INSERT INTO transmission (id, sessionId, threadId, startedAtUtc, endedAtUtc, durationMs, " +
                "audioFormat, preRollMs, postRollMs, frequencyHz, frequencyProvenance, mode, signalStrength, " +
                "channelName, voiceprintId, attributionState, stationId, attributionConfidence, " +
                "attributionSourceTransmissionId, corrected, processingState, rejectionReason, samplePosition, " +
                "monotonicStartNanos, utcOffsetMinutes, calibrationId, enhancementApplied, executionProvider, " +
                "isReprocessCandidate, processedTier) VALUES ('TX1', 'S1', NULL, 0, 1000, 1000, " +
                "'flac/16k/mono', 200, 200, NULL, 'measured', NULL, NULL, NULL, NULL, 'CONFIRMED', 'K7ABC', " +
                "0.9, NULL, 0, 'CAPTURED', NULL, 0, 0, 0, NULL, '', NULL, 0, NULL)",
        )
        v4.execSQL(
            "INSERT INTO correction (id, transmissionId, field, previousValue, newValue, correctedAt, " +
                "propagatedToCount) VALUES ('CORR1', 'TX1', 'stationId', NULL, 'K7ABC', 100, 0)",
        )
        v4.close()

        helper.runMigrationsAndValidate(dbName, 5, true, OrtDatabase.MIGRATION_4_5)

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), OrtDatabase::class.java, dbName)
            .addMigrations(*OrtDatabase.MIGRATIONS)
            .build()
        try {
            val correction = runBlocking { db.correctionDao().correctionsFor("TX1") }.single()
            assertEquals("K7ABC", correction.newValue) // pre-existing row survives
            assertEquals(null, correction.previousAttributionState) // new columns default to NULL

            runBlocking {
                db.correctionDao().recordCorrection(
                    CorrectionEntity(
                        id = "CORR2",
                        transmissionId = "TX1",
                        field = "stationId",
                        previousValue = "K7ABC",
                        newValue = "W7NPC",
                        correctedAt = 200L,
                    ),
                )
            }
            val stamped = runBlocking { db.correctionDao().correctionsFor("TX1") }.single { it.id == "CORR2" }
            // New write path usable post-migration.
            assertEquals(AttributionState.CONFIRMED, stamped.previousAttributionState)
        } finally {
            db.close()
        }
    }

    /**
     * Register R-320, R-182 — the same v4 -> v5 migration also adds `lattice_slot` (schema v5's
     * other, independently-landed item — see [OrtDatabase.MIGRATION_4_5]'s own doc comment for
     * why one version bump covers both). Proves both halves of FR-AST-5/6: a pre-existing
     * `callsign_candidate` row survives, and the new table's write/read path
     * ([org.ort.data.dao.CatalogDao.insert], `.slotDetailsFor`) is immediately usable afterwards.
     */
    @Test
    @Requirement("AC-53", "FR-AST-5", "FR-AST-6", "R-320", "R-182")
    public fun migration_from_v4_to_v5_preserves_existing_rows_and_adds_the_lattice_slot_table() {
        val dbName = "migration-test-db-v5-lattice-slot"
        val v4 = helper.createDatabase(dbName, 4)
        v4.execSQL(
            "INSERT INTO session (id, startedAt, endedAt, profileId, deviceTier, appVersion, " +
                "terminationReason, sourceId, schemaVersion, gapCount, shedEvents) VALUES " +
                "('S1', 0, NULL, NULL, NULL, 'test', NULL, NULL, 1, 0, 0)",
        )
        v4.execSQL(
            "INSERT INTO transmission (id, sessionId, threadId, startedAtUtc, endedAtUtc, durationMs, " +
                "audioFormat, preRollMs, postRollMs, frequencyHz, frequencyProvenance, mode, signalStrength, " +
                "channelName, voiceprintId, attributionState, stationId, attributionConfidence, " +
                "attributionSourceTransmissionId, corrected, processingState, rejectionReason, samplePosition, " +
                "monotonicStartNanos, utcOffsetMinutes, calibrationId, enhancementApplied, executionProvider, " +
                "isReprocessCandidate, processedTier) VALUES ('TX1', 'S1', NULL, 0, 1000, 1000, " +
                "'flac/16k/mono', 200, 200, NULL, 'measured', NULL, NULL, NULL, NULL, 'CONFIRMED', 'K7ABC', " +
                "0.9, NULL, 0, 'CAPTURED', NULL, 0, 0, 0, NULL, '', NULL, 0, NULL)",
        )
        v4.execSQL(
            "INSERT INTO callsign_candidate (id, transmissionId, callsign, `rank`, score, grammarValid, " +
                "ituPrefix, ituCountry, priorBreakdown, databaseHit, selected) VALUES " +
                "('C1', 'TX1', 'K7ABC', 0, 0.9, 1, 'K', 'United States', NULL, 1, 1)",
        )
        v4.close()

        helper.runMigrationsAndValidate(dbName, 5, true, OrtDatabase.MIGRATION_4_5)

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), OrtDatabase::class.java, dbName)
            .addMigrations(*OrtDatabase.MIGRATIONS)
            .build()
        try {
            val candidates = runBlocking { db.catalogDao().candidatesFor("TX1") }
            assertEquals("K7ABC", candidates.single().callsign) // pre-existing row survives

            runBlocking {
                db.catalogDao().insert(
                    org.ort.data.entity.LatticeSlotEntity(
                        id = "S1",
                        transmissionId = "TX1",
                        candidateId = "C1",
                        index = 0,
                        unit = "K",
                        score = 0.95,
                        keptAlternate = null,
                        charStart = 0,
                        charEnd = 1,
                    ),
                )
            }
            val slots = runBlocking { db.catalogDao().slotDetailsFor("TX1") }
            assertEquals("K", slots.single().unit) // new table usable post-migration
            val span = runBlocking { db.catalogDao().winningCandidateCharSpan("TX1") }
            assertEquals(0, span.spanStart)
            assertEquals(1, span.spanEnd)
        } finally {
            db.close()
        }
    }

    /**
     * Register R-426 (`Fail-Pass.dc.html`): v5 -> v6 adds `work_attempt`. Proves both halves of
     * FR-AST-5/6: a pre-existing `work_queue_item` row survives untouched, and the new table's
     * write/read path ([org.ort.data.dao.WorkQueueDao.insert], `.attemptsFor`) is immediately
     * usable afterwards.
     */
    @Test
    @Requirement("AC-53", "FR-AST-5", "FR-AST-6", "R-426")
    public fun migration_from_v5_to_v6_preserves_existing_rows_and_adds_the_work_attempt_table() {
        val dbName = "migration-test-db-v6-work-attempt"
        val v5 = helper.createDatabase(dbName, 5)
        v5.execSQL(
            "INSERT INTO work_queue_item (id, transmissionId, pass, state, priority, attemptCount, " +
                "lastError, shedLevel, leaseRunId, deadlineAt, enqueuedAt, startedAt) VALUES " +
                "(1, 'TX1', 'B_OFFLINE', 'READY', 0, 2, 'out of memory in the decoder', 0, NULL, NULL, 0, NULL)",
        )
        v5.close()

        helper.runMigrationsAndValidate(dbName, 6, true, OrtDatabase.MIGRATION_5_6)

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), OrtDatabase::class.java, dbName)
            .addMigrations(*OrtDatabase.MIGRATIONS)
            .build()
        try {
            val item = runBlocking { db.workQueueDao().getById(1) }
            assertEquals("out of memory in the decoder", item?.lastError) // pre-existing row survives

            runBlocking {
                db.workQueueDao().insert(
                    org.ort.data.entity.WorkAttemptEntity(
                        itemId = 1,
                        attemptNo = 1,
                        startedAtMillis = 1_000L,
                        finishedAtMillis = 2_000L,
                        outcome = org.ort.data.entity.WorkAttemptOutcome.FAILED,
                        reason = "out of memory in the decoder",
                    ),
                )
            }
            val attempts = runBlocking { db.workQueueDao().attemptsFor(1) }
            assertEquals("out of memory in the decoder", attempts.single().reason) // new table usable post-migration
        } finally {
            db.close()
        }
    }

    /**
     * FR-CAP-13, AC-129: v6 -> v7 adds `session.captureMode` and its four siblings. Proves both
     * halves of FR-AST-5/6: a pre-existing `session` row survives untouched, and the new columns
     * default to `NULL` until [OrtDatabase.sessionDao]'s insert path stamps them for a v7 install.
     */
    @Test
    @Requirement("AC-53", "AC-129", "FR-AST-5", "FR-AST-6", "FR-CAP-13")
    public fun migration_from_v6_to_v7_preserves_existing_rows_and_adds_the_capture_mode_columns() {
        val dbName = "migration-test-db-v7-capture-mode"
        val v6 = helper.createDatabase(dbName, 6)
        v6.execSQL(
            "INSERT INTO session (id, startedAt, endedAt, profileId, deviceTier, appVersion, " +
                "terminationReason, sourceId, schemaVersion, gapCount, shedEvents) VALUES " +
                "('S1', 0, NULL, NULL, NULL, 'test', NULL, NULL, 6, 0, 0)",
        )
        v6.close()

        helper.runMigrationsAndValidate(dbName, 7, true, OrtDatabase.MIGRATION_6_7)

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), OrtDatabase::class.java, dbName)
            .addMigrations(*OrtDatabase.MIGRATIONS)
            .build()
        try {
            val migrated = runBlocking { db.sessionDao().getById("S1") }
            assertEquals("test", migrated!!.appVersion) // pre-existing row survives
            assertEquals(null, migrated.captureMode) // new columns default to NULL, never fabricated
            assertEquals(null, migrated.audioRouteKind)
            assertEquals(null, migrated.audioRouteLabel)
            assertEquals(null, migrated.bluetoothProfile)
            assertEquals(null, migrated.rigTransport)

            runBlocking {
                db.sessionDao().insert(
                    org.ort.data.entity.SessionEntity(
                        id = "S2",
                        startedAt = 0L,
                        endedAt = null,
                        profileId = null,
                        deviceTier = null,
                        appVersion = "test",
                        terminationReason = null,
                        sourceId = null,
                        schemaVersion = 7,
                        captureMode = "USB_RADIO",
                        audioRouteKind = "USB",
                        audioRouteLabel = "USB Audio Adapter",
                        bluetoothProfile = null,
                        rigTransport = "USB_SERIAL",
                    ),
                )
            }
            val info = runBlocking { db.sessionDao().getCaptureInfo("S2") }
            assertEquals("USB_RADIO", info!!.captureMode) // new write/read path usable post-migration
            assertEquals("USB", info.audioRouteKind)
        } finally {
            db.close()
        }
    }

    /**
     * FR-DIG-3, FR-DIG-11 / T3: v7 -> v8 adds the `prose_summary` table for
     * [org.ort.data.entity.ProseSummaryEntity]. Proves both halves of FR-AST-5/6: a pre-existing
     * `session` row survives untouched, and the new table's write/read path
     * ([OrtDatabase.proseSummaryDao]) is immediately usable afterwards.
     */
    @Test
    @Requirement("AC-53", "FR-AST-5", "FR-AST-6", "FR-DIG-3", "FR-DIG-11")
    public fun migration_from_v7_to_v8_preserves_existing_rows_and_adds_the_prose_summary_table() {
        val dbName = "migration-test-db-v8-prose-summary"
        val v7 = helper.createDatabase(dbName, 7)
        v7.execSQL(
            "INSERT INTO session (id, startedAt, endedAt, profileId, deviceTier, appVersion, " +
                "terminationReason, sourceId, schemaVersion, gapCount, shedEvents, captureMode, " +
                "audioRouteKind, audioRouteLabel, bluetoothProfile, rigTransport) VALUES " +
                "('S1', 0, NULL, NULL, NULL, 'test', NULL, NULL, 7, 0, 0, NULL, NULL, NULL, NULL, NULL)",
        )
        v7.close()

        helper.runMigrationsAndValidate(dbName, 8, true, OrtDatabase.MIGRATION_7_8)

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), OrtDatabase::class.java, dbName)
            .addMigrations(*OrtDatabase.MIGRATIONS)
            .build()
        try {
            val migrated = runBlocking { db.sessionDao().getById("S1") }
            assertEquals("test", migrated!!.appVersion) // pre-existing row survives

            runBlocking {
                db.proseSummaryDao().upsert(
                    org.ort.data.entity.ProseSummaryEntity(
                        threadId = "TH1",
                        text = "Exchanged signal reports.",
                        sourceTransmissionIds = listOf("TX1", "TX2"),
                        generatedAtMillis = 1_000L,
                        modelId = "gemma3-1b-it-int4",
                    ),
                )
            }
            val summary = runBlocking { db.proseSummaryDao().getByThreadId("TH1") }
            assertEquals("Exchanged signal reports.", summary?.text) // new table usable post-migration
            assertEquals(listOf("TX1", "TX2"), summary?.sourceTransmissionIds)
        } finally {
            db.close()
        }
    }

    /**
     * WPC3, FR-RIG-6, FR-CAP-5: v8 → v9 adds `transmission.rigStateChangedMidTransmission`. Proves
     * both halves of FR-AST-5/6: a pre-existing `transmission` row survives with its real data
     * intact, and the new column defaults to `false` (never a fabricated `true`) until
     * [OrtDatabase.transmissionDao]'s insert path sets it.
     */
    @Test
    @Requirement("AC-53", "FR-AST-5", "FR-AST-6", "FR-RIG-6", "FR-CAP-5")
    public fun migration_from_v8_to_v9_preserves_existing_rows_and_adds_the_rig_state_changed_column() {
        val dbName = "migration-test-db-v9-rig-state-changed"
        val v8 = helper.createDatabase(dbName, 8)
        v8.execSQL(
            "INSERT INTO session (id, startedAt, endedAt, profileId, deviceTier, appVersion, " +
                "terminationReason, sourceId, schemaVersion, gapCount, shedEvents, captureMode, " +
                "audioRouteKind, audioRouteLabel, bluetoothProfile, rigTransport) VALUES " +
                "('S1', 0, NULL, NULL, NULL, 'test', NULL, NULL, 8, 0, 0, NULL, NULL, NULL, NULL, NULL)",
        )
        v8.execSQL(
            "INSERT INTO transmission (id, sessionId, threadId, startedAtUtc, endedAtUtc, durationMs, " +
                "audioFormat, preRollMs, postRollMs, frequencyHz, frequencyProvenance, mode, signalStrength, " +
                "channelName, voiceprintId, attributionState, stationId, attributionConfidence, " +
                "attributionSourceTransmissionId, corrected, processingState, rejectionReason, samplePosition, " +
                "monotonicStartNanos, utcOffsetMinutes, calibrationId, enhancementApplied, executionProvider, " +
                "isReprocessCandidate, processedTier) VALUES ('TX1', 'S1', NULL, 0, 1000, 1000, " +
                "'flac/16k/mono', 200, 200, 14250000, 'rig', NULL, NULL, NULL, NULL, 'UNKNOWN', NULL, NULL, " +
                "NULL, 0, 'CAPTURED', NULL, 0, 0, 0, NULL, '', NULL, 0, NULL)",
        )
        v8.close()

        helper.runMigrationsAndValidate(dbName, 9, true, OrtDatabase.MIGRATION_8_9)

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), OrtDatabase::class.java, dbName)
            .addMigrations(*OrtDatabase.MIGRATIONS)
            .build()
        try {
            val transmission = runBlocking { db.transmissionDao().getById("TX1") }
            assertEquals("flac/16k/mono", transmission!!.audioFormat) // pre-existing row survives
            assertEquals(14250000L, transmission.frequencyHz)
            assertEquals(
                "the new column must default to false, never a fabricated true",
                false,
                transmission.rigStateChangedMidTransmission,
            )

            runBlocking {
                db.transmissionDao().insert(
                    transmission.copy(id = "TX2", rigStateChangedMidTransmission = true),
                )
            }
            val flagged = runBlocking { db.transmissionDao().getById("TX2") }
            assertEquals(true, flagged!!.rigStateChangedMidTransmission) // new write path usable post-migration
        } finally {
            db.close()
        }
    }

    /**
     * E2-A07: v9 → v10 adds `session.rigDescriptorId`, `.audioRouteVerified` and
     * `.audioNativeRateHz`. Proves both halves of FR-AST-5/6: a pre-existing `session` row
     * survives untouched with all three new columns `NULL`, and the new write paths
     * ([OrtDatabase.sessionDao]'s insert and [org.ort.data.dao.SessionDao.setAudioRouteVerified])
     * are immediately usable afterwards.
     */
    @Test
    @Requirement("AC-53", "FR-AST-5", "FR-AST-6")
    public fun migration_from_v9_to_v10_preserves_existing_rows_and_adds_the_session_route_facts_columns() {
        val dbName = "migration-test-db-v10-session-route-facts"
        val v9 = helper.createDatabase(dbName, 9)
        v9.execSQL(
            "INSERT INTO session (id, startedAt, endedAt, profileId, deviceTier, appVersion, " +
                "terminationReason, sourceId, schemaVersion, gapCount, shedEvents, captureMode, " +
                "audioRouteKind, audioRouteLabel, bluetoothProfile, rigTransport) VALUES " +
                "('S1', 0, NULL, NULL, NULL, 'test', NULL, NULL, 9, 0, 0, 'USB_RADIO', 'USB', " +
                "'USB Audio Adapter', NULL, 'USB_SERIAL')",
        )
        v9.close()

        helper.runMigrationsAndValidate(dbName, 10, true, OrtDatabase.MIGRATION_9_10)

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), OrtDatabase::class.java, dbName)
            .addMigrations(*OrtDatabase.MIGRATIONS)
            .build()
        try {
            val migrated = runBlocking { db.sessionDao().getById("S1") }
            assertEquals("test", migrated!!.appVersion) // pre-existing row survives
            assertEquals(null, migrated.rigDescriptorId) // new columns default to NULL
            assertEquals(null, migrated.audioRouteVerified)
            assertEquals(null, migrated.audioNativeRateHz)

            runBlocking {
                db.sessionDao().insert(
                    org.ort.data.entity.SessionEntity(
                        id = "S2",
                        startedAt = 0L,
                        endedAt = null,
                        profileId = null,
                        deviceTier = null,
                        appVersion = "test",
                        terminationReason = null,
                        sourceId = null,
                        schemaVersion = 10,
                        rigDescriptorId = "kenwood-thd75a",
                        audioRouteVerified = false,
                        audioNativeRateHz = 48_000,
                    ),
                )
                db.sessionDao().setAudioRouteVerified("S2", true)
            }
            val info = runBlocking { db.sessionDao().getCaptureInfo("S2") }
            assertEquals("kenwood-thd75a", info!!.rigDescriptorId) // new write/read path usable post-migration
            assertEquals(true, info.audioRouteVerified) // setAudioRouteVerified overwrites the insert-time value
            assertEquals(48_000, info.audioNativeRateHz)
        } finally {
            db.close()
        }
    }

    /** Register R-1002: v10 → v11 adds `work_queue_item.retryNotBeforeMillis`, `NULL` on an
     * existing row (unchanged behaviour), with [WorkQueueDao.retryReady]'s new form usable after. */
    @Test
    @Requirement("AC-53", "FR-AST-5", "FR-AST-6", "R-1002")
    public fun migration_from_v10_to_v11_preserves_existing_rows_and_adds_the_retry_not_before_column() {
        val dbName = "migration-test-db-v11-retry-not-before"
        val v10 = helper.createDatabase(dbName, 10)
        v10.execSQL(
            "INSERT INTO work_queue_item (id, transmissionId, pass, state, priority, attemptCount, " +
                "lastError, shedLevel, leaseRunId, deadlineAt, enqueuedAt, startedAt) VALUES " +
                "(1, 'TX1', 'B_OFFLINE', 'READY', 0, 1, 'ASR unavailable: no ASR model installed', " +
                "0, NULL, NULL, 0, NULL)",
        )
        v10.close()

        helper.runMigrationsAndValidate(dbName, 11, true, OrtDatabase.MIGRATION_10_11)

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), OrtDatabase::class.java, dbName)
            .addMigrations(*OrtDatabase.MIGRATIONS)
            .build()
        try {
            val item = runBlocking { db.workQueueDao().getById(1) }
            assertEquals("ASR unavailable: no ASR model installed", item?.lastError) // survives
            assertEquals(null, item?.retryNotBeforeMillis) // new column defaults to NULL

            runBlocking {
                db.workQueueDao().retryReady(1, attemptCount = 2, error = "boom", retryNotBeforeMillis = 99_999L)
            }
            val retried = runBlocking { db.workQueueDao().getById(1) }
            assertEquals(99_999L, retried?.retryNotBeforeMillis) // new write path usable post-migration
        } finally {
            db.close()
        }
    }

    /**
     * WPARC (FR-SEG-9, FR-STO-3d, D39): v11 → v12 adds `session.archiveState`/
     * `.archiveRemovedAtMillis` and the `archive_gap` table. Proves both halves of FR-AST-5/6: a
     * pre-existing `session` row survives untouched with both new columns `NULL` (no archive,
     * never a fabricated "kept"), and the new write paths ([OrtDatabase.sessionDao]'s
     * `setArchiveKept`/`setArchiveRemoved` and [OrtDatabase.archiveGapDao]) are immediately usable
     * afterwards.
     */
    @Test
    @Requirement("AC-53", "AC-96", "AC-150", "AC-151", "FR-AST-5", "FR-AST-6", "FR-SEG-9", "FR-STO-3d")
    public fun migration_from_v11_to_v12_preserves_existing_rows_and_adds_the_archive_columns() {
        val dbName = "migration-test-db-v12-archive"
        val v11 = helper.createDatabase(dbName, 11)
        v11.execSQL(
            "INSERT INTO session (id, startedAt, endedAt, profileId, deviceTier, appVersion, " +
                "terminationReason, sourceId, schemaVersion, gapCount, shedEvents, captureMode, " +
                "audioRouteKind, audioRouteLabel, bluetoothProfile, rigTransport) VALUES " +
                "('S1', 0, NULL, NULL, NULL, 'test', NULL, NULL, 11, 0, 0, NULL, NULL, NULL, NULL, NULL)",
        )
        v11.close()

        helper.runMigrationsAndValidate(dbName, 12, true, OrtDatabase.MIGRATION_11_12)

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), OrtDatabase::class.java, dbName)
            .addMigrations(*OrtDatabase.MIGRATIONS)
            .build()
        try {
            val migrated = runBlocking { db.sessionDao().getById("S1") }
            assertEquals("test", migrated!!.appVersion) // pre-existing row survives
            assertEquals(null, migrated.archiveState) // new columns default to NULL, never fabricated
            assertEquals(null, migrated.archiveRemovedAtMillis)

            runBlocking {
                db.sessionDao().insert(
                    org.ort.data.entity.SessionEntity(
                        id = "S2",
                        startedAt = 0L,
                        endedAt = null,
                        profileId = null,
                        deviceTier = null,
                        appVersion = "test",
                        terminationReason = null,
                        sourceId = null,
                        schemaVersion = 12,
                    ),
                )
                db.sessionDao().setArchiveKept("S2")
                db.archiveGapDao().insert(
                    org.ort.data.entity.ArchiveGapEntity(
                        id = "AG1",
                        sessionId = "S2",
                        startSample = 0L,
                        sampleCount = 480_000L,
                        reason = "verification_failed",
                        recordedAtMillis = 100L,
                    ),
                )
            }
            val kept = runBlocking { db.sessionDao().getById("S2") }
            assertEquals("KEPT", kept!!.archiveState) // new write path usable post-migration

            runBlocking { db.sessionDao().setArchiveRemoved("S2", removedAtMillis = 5_000L) }
            val removed = runBlocking { db.sessionDao().getById("S2") }
            assertEquals("REMOVED", removed!!.archiveState)
            assertEquals(5_000L, removed.archiveRemovedAtMillis) // the pruned interval stays listed, with its date

            val gaps = runBlocking { db.archiveGapDao().listBySession("S2") }
            assertEquals("verification_failed", gaps.single().reason) // archive_gap table usable post-migration
        } finally {
            db.close()
        }
    }

    /**
     * WPDATA (FR-STO-3e, D40, FR-OBS-4): v12 → v13 adds `session.overAudioRemovedAtMillis` and the
     * `transmission_label` table. Proves both halves of FR-AST-5/6: a pre-existing `session` row
     * survives untouched with the new column `NULL` (over audio never deleted, never fabricated as
     * removed), and the new write paths ([OrtDatabase.sessionDao]'s `setOverAudioRemoved` and
     * [OrtDatabase.transmissionLabelDao]) are immediately usable afterwards.
     */
    @Test
    @Requirement("AC-53", "FR-AST-5", "FR-AST-6", "FR-STO-3e", "FR-OBS-4")
    public fun migration_from_v12_to_v13_preserves_existing_rows_and_adds_the_over_audio_and_label_columns() {
        // R-1043 (second correction): this test's own long name, embedded verbatim by Robolectric
        // into its per-test sandbox temp directory name, combined with a descriptive dbName, pushed
        // the resolved absolute path (this file's own `%TEMP%\robolectric-<class>_<test name><random
        // suffix>\...\databases\<dbName>`) to 253 characters -- under Windows' MAX_PATH (260) for the
        // bare `.db` file itself, but over it once SQLite's own `-journal` sidecar file (needed the
        // moment a connection opens, before WAL is even established -- exactly where the crash
        // occurred, in `SQLiteConnection.setJournalMode`/`.setWalModeFromConfiguration`) added its own
        // 8-character suffix (261 characters, one past the ceiling). Confirmed empirically: printing
        // the real resolved path and its length reproduced exactly 253/261; shortening only `dbName`
        // (never the test's own name, which carries the requirement id `MigrationTest`'s other cases
        // rely on for traceability) brings every sidecar file safely under the limit again, the same
        // budget every other case in this file already keeps to by using a short `dbName`.
        val dbName = "migration-test-db-v13"
        val v12 = helper.createDatabase(dbName, 12)
        v12.execSQL(
            "INSERT INTO session (id, startedAt, endedAt, profileId, deviceTier, appVersion, " +
                "terminationReason, sourceId, schemaVersion, gapCount, shedEvents, captureMode, " +
                "audioRouteKind, audioRouteLabel, bluetoothProfile, rigTransport) VALUES " +
                "('S1', 0, NULL, NULL, NULL, 'test', NULL, NULL, 12, 0, 0, NULL, NULL, NULL, NULL, NULL)",
        )
        v12.execSQL(
            "INSERT INTO transmission (id, sessionId, threadId, startedAtUtc, endedAtUtc, durationMs, " +
                "audioFormat, preRollMs, postRollMs, frequencyHz, frequencyProvenance, mode, signalStrength, " +
                "channelName, voiceprintId, attributionState, stationId, attributionConfidence, " +
                "attributionSourceTransmissionId, corrected, processingState, rejectionReason, samplePosition, " +
                "monotonicStartNanos, utcOffsetMinutes, calibrationId, enhancementApplied, executionProvider, " +
                "isReprocessCandidate, processedTier, rigStateChangedMidTransmission) VALUES ('TX1', 'S1', NULL, " +
                "0, 1000, 1000, 'flac/16k/mono', 200, 200, NULL, 'measured', NULL, NULL, NULL, NULL, 'UNKNOWN', " +
                "NULL, NULL, NULL, 0, 'CAPTURED', NULL, 0, 0, 0, NULL, '', NULL, 0, NULL, 0)",
        )
        v12.close()

        helper.runMigrationsAndValidate(dbName, 13, true, OrtDatabase.MIGRATION_12_13)

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), OrtDatabase::class.java, dbName)
            .addMigrations(*OrtDatabase.MIGRATIONS)
            .build()
        try {
            val migrated = runBlocking { db.sessionDao().getById("S1") }
            assertEquals("test", migrated!!.appVersion) // pre-existing row survives
            assertEquals(null, migrated.overAudioRemovedAtMillis) // new column defaults to NULL, never fabricated

            runBlocking { db.sessionDao().setOverAudioRemoved("S1", removedAtMillis = 7_000L) }
            val removed = runBlocking { db.sessionDao().getById("S1") }
            assertEquals(7_000L, removed!!.overAudioRemovedAtMillis) // new write path usable post-migration

            runBlocking {
                db.transmissionLabelDao().upsert(
                    org.ort.data.entity.TransmissionLabelEntity(
                        transmissionId = "TX1",
                        markedForTraining = true,
                        outcome = org.ort.data.entity.LabelOutcome.SPEECH,
                        truthCallsign = "K7ABC",
                        callsignCertainty = org.ort.data.entity.LabelCertainty.CERTAIN,
                        rating = "good",
                        labelledAtMillis = 8_000L,
                    ),
                )
            }
            val label = runBlocking { db.transmissionLabelDao().getByTransmissionId("TX1") }
            assertEquals("K7ABC", label?.truthCallsign) // transmission_label table usable post-migration
            assertEquals("good", label?.rating)
            val count = runBlocking { db.transmissionLabelDao().countMarkedForTrainingBySession("S1") }
            assertEquals(1, count)
        } finally {
            db.close()
        }
    }

    /**
     * WPSEGPROV (FR-SEG-10, register R-1054, AC-162): v13 → v14 adds
     * `session.vadDetector`/`.vadDetectorVersion` and
     * `transmission.vadDetector`/`.vadDetectorVersion`/`.rigSquelchFusionApplied`. Proves both
     * halves of FR-AST-5/6: a pre-existing `session` and `transmission` row each survive untouched
     * with `vadDetector` defaulting to the honest `'UNKNOWN'` (never a fabricated `'SILERO'`) and
     * `rigSquelchFusionApplied` to `false`, and the new write path
     * ([OrtDatabase.transmissionDao]'s insert, using the real [VadDetectorKind] enum) is immediately
     * usable afterwards.
     */
    @Test
    @Requirement("AC-53", "FR-AST-5", "FR-AST-6", "FR-SEG-10", "AC-162")
    public fun migration_from_v13_to_v14_preserves_existing_rows_and_adds_the_vad_detector_columns() {
        val dbName = "migration-test-db-v14"
        val v13 = helper.createDatabase(dbName, 13)
        v13.execSQL(
            "INSERT INTO session (id, startedAt, endedAt, profileId, deviceTier, appVersion, " +
                "terminationReason, sourceId, schemaVersion, gapCount, shedEvents, captureMode, " +
                "audioRouteKind, audioRouteLabel, bluetoothProfile, rigTransport) VALUES " +
                "('S1', 0, NULL, NULL, NULL, 'test', NULL, NULL, 13, 0, 0, NULL, NULL, NULL, NULL, NULL)",
        )
        v13.execSQL(
            "INSERT INTO transmission (id, sessionId, threadId, startedAtUtc, endedAtUtc, durationMs, " +
                "audioFormat, preRollMs, postRollMs, frequencyHz, frequencyProvenance, mode, signalStrength, " +
                "channelName, voiceprintId, attributionState, stationId, attributionConfidence, " +
                "attributionSourceTransmissionId, corrected, processingState, rejectionReason, samplePosition, " +
                "monotonicStartNanos, utcOffsetMinutes, calibrationId, enhancementApplied, executionProvider, " +
                "isReprocessCandidate, processedTier, rigStateChangedMidTransmission) VALUES ('TX1', 'S1', NULL, " +
                "0, 1000, 1000, 'flac/16k/mono', 200, 200, NULL, 'measured', NULL, NULL, NULL, NULL, 'UNKNOWN', " +
                "NULL, NULL, NULL, 0, 'CAPTURED', NULL, 0, 0, 0, NULL, '', NULL, 0, NULL, 0)",
        )
        v13.close()

        helper.runMigrationsAndValidate(dbName, 14, true, OrtDatabase.MIGRATION_13_14)

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), OrtDatabase::class.java, dbName)
            .addMigrations(*OrtDatabase.MIGRATIONS)
            .build()
        try {
            val migratedSession = runBlocking { db.sessionDao().getById("S1") }
            assertEquals("test", migratedSession!!.appVersion) // pre-existing row survives
            assertEquals(
                "the new session column must default to the honest UNKNOWN, never a fabricated SILERO",
                VadDetectorKind.UNKNOWN,
                migratedSession.vadDetector,
            )
            assertEquals(null, migratedSession.vadDetectorVersion)

            val migratedTransmission = runBlocking { db.transmissionDao().getById("TX1") }
            assertEquals("flac/16k/mono", migratedTransmission!!.audioFormat) // pre-existing row survives
            assertEquals(
                "the new transmission column must default to the honest UNKNOWN",
                VadDetectorKind.UNKNOWN,
                migratedTransmission.vadDetector,
            )
            assertEquals(null, migratedTransmission.vadDetectorVersion)
            assertEquals(
                "the new fusion column must default to false, never a fabricated true",
                false,
                migratedTransmission.rigSquelchFusionApplied,
            )
            assertEquals(
                "an UNKNOWN detector must never read as conforming to FR-SEG-1",
                false,
                migratedTransmission.conformsToFrSeg1(),
            )

            runBlocking {
                db.transmissionDao().insert(
                    migratedTransmission.copy(
                        id = "TX2",
                        vadDetector = VadDetectorKind.SILERO,
                        vadDetectorVersion = "silero-v5",
                        rigSquelchFusionApplied = true,
                    ),
                )
            }
            val newRow = runBlocking { db.transmissionDao().getById("TX2") }
            // New write path usable post-migration, using the real enum type.
            assertEquals(VadDetectorKind.SILERO, newRow!!.vadDetector)
            assertEquals("silero-v5", newRow.vadDetectorVersion)
            assertEquals(true, newRow.rigSquelchFusionApplied)
            assertEquals(true, newRow.conformsToFrSeg1())
        } finally {
            db.close()
        }
    }

    /**
     * WPSEGPROV follow-up (register R-1098, FR-SPK-5, constitution I): v14 → v15 adds
     * `transmission.threadJoinReason`. Proves both halves of FR-AST-5/6: a pre-existing
     * `transmission` row survives untouched with the new column `NULL` (no threading decision ever
     * recorded for it, never a fabricated reason), and the new write path
     * ([OrtDatabase.transmissionDao]'s insert, using the real [org.ort.data.entity.ThreadJoinReason]
     * enum) is immediately usable afterwards.
     */
    @Test
    @Requirement("AC-53", "FR-AST-5", "FR-AST-6", "FR-SPK-5", "R-1098")
    public fun migration_from_v14_to_v15_preserves_existing_rows_and_adds_the_thread_join_reason_column() {
        val dbName = "migration-test-db-v15"
        val v14 = helper.createDatabase(dbName, 14)
        // R-1098: a freshly created v14 schema declares `session.vadDetector` `NOT NULL` with no
        // SQL-level default (only `MIGRATION_13_14`'s own `ALTER TABLE ... DEFAULT 'UNKNOWN'`
        // carries one, and that path never runs for a fixture created directly at v14) — see the
        // identical fact documented on this file's `every_prior_fixture...`/`r_885_every_fixture...`
        // sweeps.
        v14.execSQL(
            "INSERT INTO session (id, startedAt, endedAt, profileId, deviceTier, appVersion, " +
                "terminationReason, sourceId, schemaVersion, gapCount, shedEvents, captureMode, " +
                "audioRouteKind, audioRouteLabel, bluetoothProfile, rigTransport, vadDetector) VALUES " +
                "('S1', 0, NULL, NULL, NULL, 'test', NULL, NULL, 14, 0, 0, NULL, NULL, NULL, NULL, NULL, 'UNKNOWN')",
        )
        v14.execSQL(
            "INSERT INTO transmission (id, sessionId, threadId, startedAtUtc, endedAtUtc, durationMs, " +
                "audioFormat, preRollMs, postRollMs, frequencyHz, frequencyProvenance, mode, signalStrength, " +
                "channelName, voiceprintId, attributionState, stationId, attributionConfidence, " +
                "attributionSourceTransmissionId, corrected, processingState, rejectionReason, samplePosition, " +
                "monotonicStartNanos, utcOffsetMinutes, calibrationId, enhancementApplied, executionProvider, " +
                "isReprocessCandidate, processedTier, rigStateChangedMidTransmission, vadDetector, " +
                "vadDetectorVersion, rigSquelchFusionApplied) VALUES ('TX1', 'S1', NULL, 0, 1000, 1000, " +
                "'flac/16k/mono', 200, 200, NULL, 'measured', NULL, NULL, NULL, NULL, 'UNKNOWN', NULL, NULL, " +
                "NULL, 0, 'CAPTURED', NULL, 0, 0, 0, NULL, '', NULL, 0, NULL, 0, 'UNKNOWN', NULL, 0)",
        )
        v14.close()

        helper.runMigrationsAndValidate(dbName, 15, true, OrtDatabase.MIGRATION_14_15)

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), OrtDatabase::class.java, dbName)
            .addMigrations(*OrtDatabase.MIGRATIONS)
            .build()
        try {
            val migrated = runBlocking { db.transmissionDao().getById("TX1") }
            assertEquals("flac/16k/mono", migrated!!.audioFormat) // pre-existing row survives
            assertEquals(
                "the new column must default to NULL, never a fabricated reason",
                null,
                migrated.threadJoinReason,
            )

            runBlocking {
                db.transmissionDao().insert(
                    migrated.copy(
                        id = "TX2",
                        threadJoinReason = org.ort.data.entity.ThreadJoinReason.SAME_FREQUENCY_WITHIN_GAP,
                    ),
                )
            }
            val newRow = runBlocking { db.transmissionDao().getById("TX2") }
            // New write path usable post-migration, using the real enum type.
            assertEquals(org.ort.data.entity.ThreadJoinReason.SAME_FREQUENCY_WITHIN_GAP, newRow!!.threadJoinReason)
        } finally {
            db.close()
        }
    }

    /**
     * Register R-1133 (FR-ASR-5, FR-ASR-6, AC-6): v15 → v16 adds `transmission.inertControls`.
     * Proves both halves of FR-AST-5/6: a pre-existing `transmission` row survives untouched with
     * the new column `NULL` (no Pass B outcome has recorded this fact for it yet, never a
     * fabricated `""`), and the new write path ([OrtDatabase.transmissionDao]'s
     * `setInertControls`) is immediately usable afterwards.
     */
    @Test
    @Requirement("AC-53", "FR-AST-5", "FR-AST-6", "FR-ASR-5", "FR-ASR-6", "AC-6", "R-1133")
    public fun migration_from_v15_to_v16_preserves_existing_rows_and_adds_the_inert_controls_column() {
        val dbName = "migration-test-db-v16"
        val v15 = helper.createDatabase(dbName, 15)
        v15.execSQL(
            "INSERT INTO session (id, startedAt, endedAt, profileId, deviceTier, appVersion, " +
                "terminationReason, sourceId, schemaVersion, gapCount, shedEvents, captureMode, " +
                "audioRouteKind, audioRouteLabel, bluetoothProfile, rigTransport, vadDetector) VALUES " +
                "('S1', 0, NULL, NULL, NULL, 'test', NULL, NULL, 15, 0, 0, NULL, NULL, NULL, NULL, NULL, 'UNKNOWN')",
        )
        v15.execSQL(
            "INSERT INTO transmission (id, sessionId, threadId, startedAtUtc, endedAtUtc, durationMs, " +
                "audioFormat, preRollMs, postRollMs, frequencyHz, frequencyProvenance, mode, signalStrength, " +
                "channelName, voiceprintId, attributionState, stationId, attributionConfidence, " +
                "attributionSourceTransmissionId, corrected, processingState, rejectionReason, samplePosition, " +
                "monotonicStartNanos, utcOffsetMinutes, calibrationId, enhancementApplied, executionProvider, " +
                "isReprocessCandidate, processedTier, rigStateChangedMidTransmission, vadDetector, " +
                "vadDetectorVersion, rigSquelchFusionApplied, threadJoinReason) VALUES ('TX1', 'S1', NULL, 0, " +
                "1000, 1000, 'flac/16k/mono', 200, 200, NULL, 'measured', NULL, NULL, NULL, NULL, 'UNKNOWN', " +
                "NULL, NULL, NULL, 0, 'CAPTURED', NULL, 0, 0, 0, NULL, '', NULL, 0, NULL, 0, 'UNKNOWN', NULL, " +
                "0, NULL)",
        )
        v15.close()

        helper.runMigrationsAndValidate(dbName, 16, true, OrtDatabase.MIGRATION_15_16)

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), OrtDatabase::class.java, dbName)
            .addMigrations(*OrtDatabase.MIGRATIONS)
            .build()
        try {
            val migrated = runBlocking { db.transmissionDao().getById("TX1") }
            assertEquals("flac/16k/mono", migrated!!.audioFormat) // pre-existing row survives
            assertEquals(
                "the new column must default to NULL, never a fabricated empty signature",
                null,
                migrated.inertControls,
            )

            runBlocking { db.transmissionDao().setInertControls("TX1", "NO_SPEECH_PROB") }
            val stamped = runBlocking { db.transmissionDao().getById("TX1") }
            assertEquals("NO_SPEECH_PROB", stamped!!.inertControls) // new write path usable post-migration
        } finally {
            db.close()
        }
    }

    /**
     * FR-AST-5: every previously released schema's fixture — v1 through v15 — walks forward through
     * the *entire* migration chain to v16 (the current head), not just the single step each version
     * was introduced by. The `session` table's columns relevant here are unchanged from v1 to v15,
     * so the same insert works unmodified against every fixture version; what varies is only which
     * version [MigrationTestHelper.createDatabase] starts from and how many migrations run to reach
     * head.
     */
    @Test
    @Requirement("AC-53", "FR-AST-5", "FR-AST-6")
    public fun every_prior_fixture_from_v1_to_v15_migrates_forward_to_v16_preserving_its_session_row() {
        for (fixtureVersion in 1..15) {
            val dbName = "migration-test-db-every-fixture-v$fixtureVersion"
            val fixture = helper.createDatabase(dbName, fixtureVersion)
            // R-1098: a *freshly created* v14 schema (this is, exactly what `createDatabase` builds
            // from `schemas/14.json`) declares `session.vadDetector` as `NOT NULL` with no SQL-level
            // `DEFAULT` — only `MIGRATION_13_14`'s own `ALTER TABLE ... DEFAULT 'UNKNOWN'` carries a
            // default, and that path never runs for a fixture created directly at v14. Every
            // fixtureVersion below 14 has no such column at all, so it is added to the insert only
            // from v14 on, the same "insert what this fixture version's own schema requires" shape
            // this loop already has no other way to express with one shared statement.
            if (fixtureVersion >= 14) {
                fixture.execSQL(
                    "INSERT INTO session (id, startedAt, endedAt, profileId, deviceTier, appVersion, " +
                        "terminationReason, sourceId, schemaVersion, gapCount, shedEvents, vadDetector) VALUES " +
                        "('S1', 0, NULL, NULL, NULL, 'test', NULL, NULL, $fixtureVersion, 0, 0, 'UNKNOWN')",
                )
            } else {
                fixture.execSQL(
                    "INSERT INTO session (id, startedAt, endedAt, profileId, deviceTier, appVersion, " +
                        "terminationReason, sourceId, schemaVersion, gapCount, shedEvents) VALUES " +
                        "('S1', 0, NULL, NULL, NULL, 'test', NULL, NULL, $fixtureVersion, 0, 0)",
                )
            }
            fixture.close()

            helper.runMigrationsAndValidate(dbName, OrtDatabase.SCHEMA_VERSION, true, *OrtDatabase.MIGRATIONS)

            val db = Room.databaseBuilder(
                ApplicationProvider.getApplicationContext(),
                OrtDatabase::class.java,
                dbName,
            ).addMigrations(*OrtDatabase.MIGRATIONS).build()
            try {
                val migrated = runBlocking { db.sessionDao().getById("S1") }
                assertEquals(
                    "fixture v$fixtureVersion's session row must survive the full migration chain to v16",
                    "test",
                    migrated!!.appVersion,
                )
                assertEquals(
                    "fixture v$fixtureVersion: the v7 column must default to NULL, never a fabricated value",
                    null,
                    migrated.captureMode,
                )
                assertEquals(
                    "fixture v$fixtureVersion: the v10 rigDescriptorId column must default to NULL",
                    null,
                    migrated.rigDescriptorId,
                )
                assertEquals(
                    "fixture v$fixtureVersion: the v12 archiveState column must default to NULL",
                    null,
                    migrated.archiveState,
                )
                assertEquals(
                    "fixture v$fixtureVersion: the v13 overAudioRemovedAtMillis column must default to NULL",
                    null,
                    migrated.overAudioRemovedAtMillis,
                )
                assertEquals(
                    "fixture v$fixtureVersion: the v14 vadDetector column must default to the honest " +
                        "UNKNOWN, never a fabricated SILERO",
                    VadDetectorKind.UNKNOWN,
                    migrated.vadDetector,
                )
                assertEquals(
                    "fixture v$fixtureVersion: the v14 vadDetectorVersion column must default to NULL",
                    null,
                    migrated.vadDetectorVersion,
                )
                // The v8 table exists and is queryable from every fixture version, empty rather
                // than absent — a missing table would throw here, not read as null.
                val noSummaryYet = runBlocking { db.proseSummaryDao().getByThreadId("no-such-thread") }
                assertEquals(
                    "fixture v$fixtureVersion: prose_summary must exist and be queryable after the full chain",
                    null,
                    noSummaryYet,
                )
            } finally {
                db.close()
            }
        }
    }

    /**
     * Register R-885 (halt): the real production open path — [OrtDatabase.create], which installs
     * [androidx.sqlite.driver.bundled.BundledSQLiteDriver] — hands every [androidx.room.migration
     * .Migration] a connection through its `migrate(SQLiteConnection)` entry point, not the legacy
     * `migrate(SupportSQLiteDatabase)` override every [Migration] in [OrtDatabase] originally had
     * alone. [androidx.room.migration.Migration]'s own default `migrate(SQLiteConnection)`
     * implementation only bridges to `migrate(SupportSQLiteDatabase)` when the connection wraps a
     * `SupportSQLiteDatabase` — true only on the classic, non-driver open path
     * ([androidx.room.RoomDatabase.Builder] with no `.setDriver(...)` call). Both [helper] (Room's
     * own [MigrationTestHelper]) and every plain `Room.databaseBuilder(...).build()` call elsewhere
     * in this file are exactly that classic path, so this whole suite — despite exercising every
     * migration's SQL — never once drove a connection-based open and never caught this. A device
     * carrying an older on-disk database crashed on its very first real open with
     * `NotImplementedError: Migration N-M requires overriding migrate(SQLiteConnection)`.
     *
     * Reproduces by writing each fixture version's on-disk file directly through
     * [MigrationTestHelper.createDatabase] — deliberately never [MigrationTestHelper
     * .runMigrationsAndValidate], which itself performs the migration through the same legacy path
     * this test exists to bypass — then opening that exact file through [OrtDatabase.create]
     * itself: the same factory function, with the same [androidx.sqlite.driver.bundled
     * .BundledSQLiteDriver], the shipped app and every other production caller use. `fixtureVersion`
     * 9 is the version named in the halt report (`data/schemas/org.ort.data.OrtDatabase/9.json`);
     * the loop also covers every earlier released version (v15 added, R-1133, head v16), since
     * each is an on-disk shape a real device could still be carrying. Widening the range by one is
     * the one change each new head version needs here.
     */
    @Test
    @Requirement("R-885", "AC-53", "FR-AST-5", "FR-AST-6")
    public fun r_885_every_fixture_from_v1_to_v15_opens_through_OrtDatabase_create_and_reads_its_session_row() {
        for (fixtureVersion in 1..15) {
            val dbName = "r885-real-open-v$fixtureVersion"
            val fixture = helper.createDatabase(dbName, fixtureVersion)
            // R-1098: see the identical branch's own comment in
            // `every_prior_fixture_from_v1_to_v15_migrates_forward_to_v16_preserving_its_session_row`
            // — a freshly created v14 schema's `session.vadDetector` is `NOT NULL` with no SQL-level
            // default.
            if (fixtureVersion >= 14) {
                fixture.execSQL(
                    "INSERT INTO session (id, startedAt, endedAt, profileId, deviceTier, appVersion, " +
                        "terminationReason, sourceId, schemaVersion, gapCount, shedEvents, vadDetector) VALUES " +
                        "('S1', 0, NULL, NULL, NULL, 'test', NULL, NULL, $fixtureVersion, 0, 0, 'UNKNOWN')",
                )
            } else {
                fixture.execSQL(
                    "INSERT INTO session (id, startedAt, endedAt, profileId, deviceTier, appVersion, " +
                        "terminationReason, sourceId, schemaVersion, gapCount, shedEvents) VALUES " +
                        "('S1', 0, NULL, NULL, NULL, 'test', NULL, NULL, $fixtureVersion, 0, 0)",
                )
            }
            fixture.close()

            // The real production factory -- installs BundledSQLiteDriver and, since this on-disk
            // file sits at fixtureVersion (not head), runs every pending Migration through the
            // connection-based path a real device actually uses. This is the exact call R-885
            // crashed in.
            val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), dbName)
            try {
                val migrated = runBlocking { db.sessionDao().getById("S1") }
                assertEquals(
                    "fixture v$fixtureVersion's session row must survive a real OrtDatabase.create() open",
                    "test",
                    migrated!!.appVersion,
                )
                // Register R-1002: fixtureVersion 10 is the one whose real open runs exactly
                // MIGRATION_10_11 through this connection-based path (the R-885 mistake shipped in
                // a Migration overriding only the legacy migrate(SupportSQLiteDatabase) signature)
                // -- proven by using the new column's write/read path, not just "did not crash".
                if (fixtureVersion == 10) runBlocking { verifyRetryNotBeforeUsableThroughRealOpen(db) }
                // WPARC: fixtureVersion 11 is the one whose real open runs exactly MIGRATION_11_12
                // through this connection-based path -- proven by using both new write paths
                // (setArchiveKept and archiveGapDao), not just "did not crash".
                if (fixtureVersion == 11) runBlocking { verifyArchiveColumnsUsableThroughRealOpen(db) }
                // WPDATA: fixtureVersion 12 is the one whose real open runs exactly MIGRATION_12_13
                // through this connection-based path -- proven by using both new write paths
                // (setOverAudioRemoved and transmissionLabelDao), not just "did not crash".
                if (fixtureVersion == 12) runBlocking { verifyOverAudioAndLabelUsableThroughRealOpen(db) }
                // WPSEGPROV: fixtureVersion 13 is the one whose real open runs exactly
                // MIGRATION_13_14 through this connection-based path -- proven by writing a real
                // VadDetectorKind through the transmission insert path, not just "did not crash".
                if (fixtureVersion == 13) runBlocking { verifyVadDetectorUsableThroughRealOpen(db) }
                // R-1098: fixtureVersion 14 is the one whose real open runs exactly
                // MIGRATION_14_15 through this connection-based path -- proven by writing a real
                // ThreadJoinReason through the transmission insert path, not just "did not crash".
                if (fixtureVersion == 14) runBlocking { verifyThreadJoinReasonUsableThroughRealOpen(db) }
                // R-1133: fixtureVersion 15 is the one whose real open runs exactly
                // MIGRATION_15_16 through this connection-based path -- proven by writing through
                // the new setInertControls write path, not just "did not crash".
                if (fixtureVersion == 15) runBlocking { verifyInertControlsUsableThroughRealOpen(db) }
            } finally {
                db.close()
            }
        }
    }

    /** R-885 helper: [org.ort.data.dao.WorkQueueDao.retryReady]'s `retryNotBeforeMillis` column,
     * exercised through a real [OrtDatabase.create] open — see the caller's own doc comment. */
    private suspend fun verifyRetryNotBeforeUsableThroughRealOpen(db: OrtDatabase) {
        db.transmissionDao().insert(TestFixtures.transmission("TX-R885-V10", sessionId = "S1"))
        val itemId = db.workQueueDao().insert(
            org.ort.data.entity.WorkQueueItemEntity(
                transmissionId = "TX-R885-V10",
                pass = org.ort.core.PassId.B_OFFLINE,
                state = org.ort.data.entity.WorkQueueState.READY,
                priority = 0,
                enqueuedAt = 0L,
            ),
        )
        db.workQueueDao().retryReady(itemId, attemptCount = 1, error = "boom", retryNotBeforeMillis = 42L)
        val row = db.workQueueDao().getById(itemId)
        assertEquals(
            "MIGRATION_10_11's retryNotBeforeMillis column must be usable through the " +
                "real connection-based open, not just Room's legacy SupportSQLiteDatabase path",
            42L,
            row?.retryNotBeforeMillis,
        )
    }

    /** R-885 helper: [org.ort.data.entity.SessionEntity.archiveState] and `archive_gap`, exercised
     * through a real [OrtDatabase.create] open — see the caller's own doc comment. */
    private suspend fun verifyArchiveColumnsUsableThroughRealOpen(db: OrtDatabase) {
        db.sessionDao().setArchiveKept("S1")
        db.archiveGapDao().insert(
            org.ort.data.entity.ArchiveGapEntity(
                id = "AG-R885-V11",
                sessionId = "S1",
                startSample = 0L,
                sampleCount = 480_000L,
                reason = "verification_failed",
                recordedAtMillis = 100L,
            ),
        )
        val kept = db.sessionDao().getById("S1")
        assertEquals(
            "MIGRATION_11_12's archiveState column must be usable through the real " +
                "connection-based open, not just Room's legacy SupportSQLiteDatabase path",
            "KEPT",
            kept?.archiveState,
        )
        val gaps = db.archiveGapDao().listBySession("S1")
        assertEquals("verification_failed", gaps.single().reason)
    }

    /** R-885 helper: [org.ort.data.entity.SessionEntity.overAudioRemovedAtMillis] and
     * `transmission_label`, exercised through a real [OrtDatabase.create] open — see the caller's
     * own doc comment. */
    private suspend fun verifyOverAudioAndLabelUsableThroughRealOpen(db: OrtDatabase) {
        db.sessionDao().setOverAudioRemoved("S1", removedAtMillis = 55_000L)
        db.transmissionDao().insert(TestFixtures.transmission("TX-R885-V12", sessionId = "S1"))
        db.transmissionLabelDao().upsert(
            org.ort.data.entity.TransmissionLabelEntity(
                transmissionId = "TX-R885-V12",
                markedForTraining = true,
                labelledAtMillis = 66_000L,
            ),
        )
        val session = db.sessionDao().getById("S1")
        assertEquals(
            "MIGRATION_12_13's overAudioRemovedAtMillis column must be usable through the " +
                "real connection-based open, not just Room's legacy SupportSQLiteDatabase path",
            55_000L,
            session?.overAudioRemovedAtMillis,
        )
        val label = db.transmissionLabelDao().getByTransmissionId("TX-R885-V12")
        assertEquals(true, label?.markedForTraining)
    }

    /** WPSEGPROV: [org.ort.data.entity.TransmissionEntity.vadDetector] and its siblings, exercised
     * through a real [OrtDatabase.create] open — see the caller's own doc comment. */
    private suspend fun verifyVadDetectorUsableThroughRealOpen(db: OrtDatabase) {
        db.transmissionDao().insert(
            TestFixtures.transmission("TX-R885-V13", sessionId = "S1").copy(
                vadDetector = VadDetectorKind.SILERO,
                vadDetectorVersion = "silero-v5",
                rigSquelchFusionApplied = true,
            ),
        )
        val transmission = db.transmissionDao().getById("TX-R885-V13")
        assertEquals(
            "MIGRATION_13_14's vadDetector column must be usable through the real " +
                "connection-based open, not just Room's legacy SupportSQLiteDatabase path",
            VadDetectorKind.SILERO,
            transmission?.vadDetector,
        )
        assertEquals("silero-v5", transmission?.vadDetectorVersion)
        assertEquals(true, transmission?.rigSquelchFusionApplied)
        assertEquals(true, transmission?.conformsToFrSeg1())
    }

    /** R-1098: [org.ort.data.entity.TransmissionEntity.threadJoinReason], exercised through a real
     * [OrtDatabase.create] open — see the caller's own doc comment. */
    private suspend fun verifyThreadJoinReasonUsableThroughRealOpen(db: OrtDatabase) {
        db.transmissionDao().insert(
            TestFixtures.transmission("TX-R885-V14", sessionId = "S1").copy(
                threadJoinReason = org.ort.data.entity.ThreadJoinReason.NEW_THREAD_GAP_EXCEEDED,
            ),
        )
        val transmission = db.transmissionDao().getById("TX-R885-V14")
        assertEquals(
            "MIGRATION_14_15's threadJoinReason column must be usable through the real " +
                "connection-based open, not just Room's legacy SupportSQLiteDatabase path",
            org.ort.data.entity.ThreadJoinReason.NEW_THREAD_GAP_EXCEEDED,
            transmission?.threadJoinReason,
        )
    }

    /** R-1133: [org.ort.data.entity.TransmissionEntity.inertControls], exercised through a real
     * [OrtDatabase.create] open — see the caller's own doc comment. */
    private suspend fun verifyInertControlsUsableThroughRealOpen(db: OrtDatabase) {
        db.transmissionDao().insert(TestFixtures.transmission("TX-R885-V15", sessionId = "S1"))
        db.transmissionDao().setInertControls("TX-R885-V15", "NO_SPEECH_PROB")
        val transmission = db.transmissionDao().getById("TX-R885-V15")
        assertEquals(
            "MIGRATION_15_16's inertControls column must be usable through the real " +
                "connection-based open, not just Room's legacy SupportSQLiteDatabase path",
            "NO_SPEECH_PROB",
            transmission?.inertControls,
        )
    }
}
