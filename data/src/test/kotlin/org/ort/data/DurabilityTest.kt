package org.ort.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.PassId
import org.ort.data.entity.WorkQueueState
import org.ort.testing.Requirement
import org.ort.testing.TestClock
import org.robolectric.RobolectricTestRunner

/**
 * FR-RUN-2 and NFR-4b both live on the same fact — that the work queue and the rest of the
 * schema sit in a real on-disk SQLite file, not an in-memory one — so they share this file.
 * Every other test in this module uses [OrtDatabase.create]'s `inMemory = true` for speed; these
 * two deliberately use a real file under the Robolectric app's database directory because the
 * behaviour under test — "survives process death", "survives a kill mid-write" — is precisely
 * about what happens when the in-memory option would not.
 */
@RunWith(RobolectricTestRunner::class)
public class DurabilityTest {

    private val dbName = "durability-test.db"
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    public fun deleteAnyPriorFile() {
        context.deleteDatabase(dbName)
    }

    @After
    public fun cleanUp() {
        context.deleteDatabase(dbName)
    }

    /**
     * FR-RUN-2: "A queued segment survives process death." Robolectric cannot kill the JVM
     * process mid-test, so this proves the strongest adjacent claim actually checkable here: a
     * *second, independent* [OrtDatabase] instance opened against the same on-disk file — after
     * the first instance is closed, standing in for the process that wrote it going away — sees
     * the queued item exactly as the first instance left it. An in-memory database could never
     * pass this, because closing it discards the data; a real file does not.
     */
    @Test
    @Requirement("FR-RUN-2")
    public fun a_queued_item_is_visible_to_a_fresh_database_instance_opened_against_the_same_file(): Unit = runTest {
        val runA = OrtDatabase.create(context, name = dbName, inMemory = false)
        try {
            runA.sessionDao().insert(TestFixtures.session())
            runA.transmissionDao().insert(TestFixtures.transmission("TX1"))
            WorkQueue(runA, TestClock()).enqueue("TX1", PassId.B_OFFLINE)
        } finally {
            runA.close() // stands in for the process that enqueued it dying
        }

        // A brand new instance, as the next process launch would open — not the same Room object.
        val runB = OrtDatabase.create(context, name = dbName, inMemory = false)
        try {
            val stored = runB.workQueueDao().findByTransmissionAndPass("TX1", PassId.B_OFFLINE.name).single()
            assertEquals(WorkQueueState.READY, stored.state)
            assertEquals("TX1", runB.transmissionDao().getById("TX1")!!.id)
        } finally {
            runB.close()
        }
    }

    /**
     * NFR-4b: "The database SHALL survive process kill mid-write." Robolectric/JVM cannot induce
     * an actual kill -9 mid-transaction and observe recovery — that needs the reference device
     * (build-plan P9's device matrix). What *is* checkable here, and is the mechanism the
     * requirement actually rests on, is that [OrtDatabase.create] configures the durability
     * primitive that makes crash-safety possible at all: WAL journal mode, which keeps committed
     * transactions replayable from the write-ahead log after an unclean shutdown. This is
     * reported as configuration verified, not as a proven survived kill.
     */
    @Test
    @Requirement("NFR-4b")
    public fun the_file_backed_database_runs_in_wal_journal_mode(): Unit = runTest {
        val db = OrtDatabase.create(context, name = dbName, inMemory = false)
        try {
            db.openHelper.writableDatabase.query("PRAGMA journal_mode").use { cursor ->
                cursor.moveToFirst()
                assertEquals("wal", cursor.getString(0).lowercase())
            }
        } finally {
            db.close()
        }
    }
}
