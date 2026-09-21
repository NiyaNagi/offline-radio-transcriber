package org.ort.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.PassId
import org.ort.data.entity.PriorAdjustmentEntity
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TranscriptPass
import org.ort.data.entity.WorkQueueItemEntity
import org.ort.data.entity.WorkQueueState
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * Register R-1120 (halt), FR-STO-7, constitution III ("nothing is deleted quietly"): proves
 * [OrtDatabase]'s hand-written-schema guard against a database that **already violates** one of
 * the three partial unique indices before the guard ever runs — the exact shape build-plan P35
 * names: "an upgrade over a database that violates the partial unique index".
 *
 * Each fixture is deliberately built with a bare `Room.databaseBuilder(...).build()`, never
 * [OrtDatabase.create] — the same pattern [MigrationTest] already uses for its own post-migration
 * reads — so the fixture's `INSERT`s land before any hand-written unique index exists to reject
 * them, reproducing exactly what an install from before this guard existed looks like on disk.
 * [OrtDatabase.create] — the real, production factory, including [OrtDatabase]'s own
 * `BundledSQLiteDriver` — is then the one and only thing this test calls to open it again;
 * proving it does not throw despite the pre-existing violation is the actual claim register
 * R-1120 makes.
 */
@RunWith(RobolectricTestRunner::class)
public class HandWrittenSchemaGuardTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    public fun deleteAnyPriorFile() {
        context.deleteDatabase(DB_NAME)
    }

    @After
    public fun cleanUp() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    @Requirement("R-1120", "FR-STO-7")
    public fun opening_a_database_with_two_current_transcripts_for_one_transmission_does_not_throw_and_keeps_both_reachable() {
        buildBareFixture { db ->
            db.sessionDao().insert(TestFixtures.session())
            db.transmissionDao().insert(TestFixtures.transmission("TX1"))
            // Two isCurrent = 1 rows for the same transmission — idx_transcript_one_current's own
            // violation, only possible because this fixture has no such index yet.
            db.transcriptDao().insert(transcript("T1", "TX1", createdAt = 1L))
            db.transcriptDao().insert(transcript("T2", "TX1", createdAt = 2L))
        }

        // The real production path — must not throw despite the pre-existing violation.
        val db = OrtDatabase.create(context, name = DB_NAME, inMemory = false)
        try {
            runBlocking {
                val versions = db.transcriptDao().getAllVersions("TX1")
                // R-1120 / constitution III: neither row was deleted.
                assertEquals(setOf("T1", "T2"), versions.map { it.id }.toSet())
                // The guard resolved the tie: exactly one survivor.
                assertEquals(1, versions.count { it.isCurrent })
                // The survivor is the higher-rowid (most recently inserted) row, T2.
                assertTrue(versions.single { it.isCurrent }.id == "T2")
            }
        } finally {
            db.close()
        }
    }

    @Test
    @Requirement("R-1120", "FR-STO-7")
    public fun opening_a_database_with_two_active_work_queue_items_for_one_pass_does_not_throw_and_keeps_both_reachable() {
        buildBareFixture { db ->
            db.sessionDao().insert(TestFixtures.session())
            db.transmissionDao().insert(TestFixtures.transmission("TX1"))
            // Two active-state rows for the same (transmissionId, pass) — idx_wq_active's own
            // violation.
            db.workQueueDao().insert(
                WorkQueueItemEntity(
                    transmissionId = "TX1",
                    pass = PassId.B_OFFLINE,
                    state = WorkQueueState.READY,
                    priority = 0,
                    enqueuedAt = 1L,
                ),
            )
            db.workQueueDao().insert(
                WorkQueueItemEntity(
                    transmissionId = "TX1",
                    pass = PassId.B_OFFLINE,
                    state = WorkQueueState.LEASED,
                    priority = 0,
                    enqueuedAt = 2L,
                ),
            )
        }

        val db = OrtDatabase.create(context, name = DB_NAME, inMemory = false)
        try {
            runBlocking {
                val items = db.workQueueDao().findByTransmissionAndPass("TX1", PassId.B_OFFLINE.name)
                // R-1120 / constitution III: neither row was deleted.
                assertEquals(2, items.size)
                // The guard resolved the tie: at most one is still active.
                val active = items.filter {
                    it.state in setOf(WorkQueueState.READY, WorkQueueState.LEASED, WorkQueueState.DEFERRED)
                }
                assertEquals(1, active.size)
                // The loser is reachable, terminal, and honestly explains itself (constitution I).
                val loser = items.single { it.state == WorkQueueState.FAILED }
                assertEquals(OrtDatabase.DUPLICATE_ACTIVE_ITEM_REASON, loser.lastError)
            }
        } finally {
            db.close()
        }
    }

    @Test
    @Requirement("R-1120", "FR-STO-7")
    public fun opening_a_database_with_two_current_prior_adjustments_for_one_station_does_not_throw_and_keeps_both_reachable() {
        buildBareFixture { db ->
            // No foreign key covers prior_adjustment.stationId (see that entity's own kdoc), so no
            // station row is needed to reproduce this violation.
            db.stationIdentityDao().insertPriorAdjustment(priorAdjustment("P1", updatedAt = 1L))
            db.stationIdentityDao().insertPriorAdjustment(priorAdjustment("P2", updatedAt = 2L))
        }

        val db = OrtDatabase.create(context, name = DB_NAME, inMemory = false)
        try {
            runBlocking {
                val history = db.stationIdentityDao().priorWeightHistoryFor("N7XYZ", "on_this_repeater")
                // R-1120 / constitution III: neither row was deleted.
                assertEquals(setOf("P1", "P2"), history.map { it.id }.toSet())
                // The guard resolved the tie: exactly one current.
                assertEquals(1, history.count { it.isCurrent })
                assertEquals("P2", db.stationIdentityDao().currentPriorWeight("N7XYZ", "on_this_repeater")?.id)
            }
        } finally {
            db.close()
        }
    }

    private fun transcript(id: String, transmissionId: String, createdAt: Long) = TranscriptEntity(
        id = id,
        transmissionId = transmissionId,
        pass = TranscriptPass.B,
        text = "test",
        modelId = "m",
        modelVersion = "1",
        quantization = null,
        decodeParams = null,
        noSpeechProb = null,
        confidence = null,
        isCurrent = true,
        createdAt = createdAt,
    )

    private fun priorAdjustment(id: String, updatedAt: Long) = PriorAdjustmentEntity(
        id = id,
        stationId = "N7XYZ",
        name = "on_this_repeater",
        weight = 0.2,
        reason = null,
        isCurrent = true,
        updatedAt = updatedAt,
    )

    /**
     * Builds the violating fixture with a bare Room database — no [OrtDatabase.create], so no
     * hand-written index exists yet to reject [populate]'s deliberately-conflicting inserts —
     * matching [MigrationTest]'s own "plain `Room.databaseBuilder`" idiom.
     *
     * [BundledSQLiteDriver] is set here even though the guard itself never runs, deliberately
     * matching the driver [OrtDatabase.create] itself installs (that function's own kdoc) — two
     * on-disk databases opened back to back in one Robolectric JVM on Windows throw
     * `SQLITE_CANTOPEN` when the first uses the classic `SupportSQLiteOpenHelper` driver (Room's
     * default) and the second switches to [BundledSQLiteDriver] against the same file; confirmed
     * empirically running this test with and without this line. Every [DurabilityTest] case, which
     * reopens an [OrtDatabase.create]-built file a second time, already uses [BundledSQLiteDriver]
     * on both opens and does not hit this — the driver switch itself is what breaks, not
     * reopening on its own.
     */
    private fun buildBareFixture(populate: suspend (OrtDatabase) -> Unit) {
        val db = Room.databaseBuilder(context, OrtDatabase::class.java, DB_NAME)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setDriver(BundledSQLiteDriver())
            .build()
        try {
            runBlocking { populate(db) }
        } finally {
            db.close()
        }
    }

    private companion object {
        // Register R-1043's own lesson (see that row's CHANGELOG entry): Robolectric embeds this
        // test class's own *method* names into its per-test sandbox directory path, and this
        // file's method names are already long (carrying what each test establishes, per
        // constitution II — not shortened for this). A short `dbName` is the budget that stays
        // under Windows' MAX_PATH once SQLite's own `-wal`/`-shm` sidecar files add their suffix —
        // "the fix is the path, not the symptom".
        private const val DB_NAME = "hwsg-test.db"
    }
}
