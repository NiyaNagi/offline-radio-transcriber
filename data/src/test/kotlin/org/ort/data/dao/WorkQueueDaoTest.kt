package org.ort.data.dao

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.PassId
import org.ort.data.OrtDatabase
import org.ort.data.TestFixtures
import org.ort.data.WorkQueue
import org.ort.testing.Requirement
import org.ort.testing.TestClock
import org.robolectric.RobolectricTestRunner

/**
 * WPDATA (FR-STO-3): [WorkQueueDao.countActiveForSession] — the check a session's audio deletion
 * refuses against.
 */
@RunWith(RobolectricTestRunner::class)
public class WorkQueueDaoTest {

    private lateinit var db: OrtDatabase
    private val clock = TestClock()

    @Before
    public fun openDatabase() {
        db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
    }

    @Test
    @Requirement("FR-STO-3")
    public fun a_session_with_no_queued_work_counts_zero(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("S1"))
        db.transmissionDao().insert(TestFixtures.transmission("TX1", sessionId = "S1"))

        assertEquals(0, db.workQueueDao().countActiveForSession("S1"))
    }

    @Test
    @Requirement("FR-STO-3")
    public fun a_ready_item_for_the_sessions_transmission_counts_as_active(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("S1"))
        db.transmissionDao().insert(TestFixtures.transmission("TX1", sessionId = "S1"))
        WorkQueue(db, clock).enqueue("TX1", PassId.B_OFFLINE)

        assertEquals(1, db.workQueueDao().countActiveForSession("S1"))
    }

    @Test
    @Requirement("FR-STO-3")
    public fun a_leased_item_still_counts_as_active(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("S1"))
        db.transmissionDao().insert(TestFixtures.transmission("TX1", sessionId = "S1"))
        val queue = WorkQueue(db, clock)
        queue.enqueue("TX1", PassId.B_OFFLINE)
        queue.leaseBatch("run-A", limit = 10) { 60_000L }

        assertEquals(1, db.workQueueDao().countActiveForSession("S1"))
    }

    @Test
    @Requirement("FR-STO-3")
    public fun a_completed_items_transmission_no_longer_counts(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("S1"))
        db.transmissionDao().insert(TestFixtures.transmission("TX1", sessionId = "S1"))
        val queue = WorkQueue(db, clock)
        queue.enqueue("TX1", PassId.B_OFFLINE)
        val leased = queue.leaseBatch("run-A", limit = 10) { 60_000L }.single()
        queue.completePass(leased, org.ort.core.TransmissionState.COMPLETE)

        assertEquals(0, db.workQueueDao().countActiveForSession("S1"))
    }

    @Test
    @Requirement("FR-STO-3")
    public fun active_work_in_a_different_session_is_never_counted(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("S1"))
        db.sessionDao().insert(TestFixtures.session("S2"))
        db.transmissionDao().insert(TestFixtures.transmission("TX1", sessionId = "S1"))
        db.transmissionDao().insert(TestFixtures.transmission("TX2", sessionId = "S2"))
        WorkQueue(db, clock).enqueue("TX2", PassId.B_OFFLINE)

        assertEquals(0, db.workQueueDao().countActiveForSession("S1"))
        assertEquals(1, db.workQueueDao().countActiveForSession("S2"))
    }
}
