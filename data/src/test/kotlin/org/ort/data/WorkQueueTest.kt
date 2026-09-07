package org.ort.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.PassId
import org.ort.core.TransmissionState
import org.ort.data.entity.WorkQueueState
import org.ort.testing.Requirement
import org.ort.testing.TestClock
import org.robolectric.RobolectricTestRunner

/**
 * The durable queue (technical design §7.1; functional spec §7.14) — build-plan P5's headline
 * tests. Each maps to one acceptance criterion named in the prompt.
 */
@RunWith(RobolectricTestRunner::class)
public class WorkQueueTest {

    private val clock = TestClock()
    private lateinit var db: OrtDatabase

    @Before
    public fun openDatabase() {
        db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
    }

    @Test
    @Requirement("AC-45")
    public fun segments_reach_the_durable_queue_with_every_pass_stalled(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session())
        db.transmissionDao().insert(TestFixtures.transmission("TX1"))
        val queue = WorkQueue(db, clock)

        // "Stalled" — nothing ever leases or executes the item. It must still be on disk.
        queue.enqueue("TX1", PassId.B_OFFLINE)

        val stored = db.workQueueDao().findByTransmissionAndPass("TX1", PassId.B_OFFLINE.name).single()
        assertEquals(WorkQueueState.READY, stored.state)
    }

    @Test
    @Requirement("AC-47")
    public fun killing_the_process_mid_pass_returns_processing_to_captured_and_completes_identically(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session())
        db.transmissionDao().insert(TestFixtures.transmission("TX1"))
        val queue = WorkQueue(db, clock)
        queue.enqueue("TX1", PassId.B_OFFLINE)

        // Run A leases it and "crashes" — never completes, never calls recoverStaleLeases itself.
        val leasedByRunA = queue.leaseBatch("run-A", limit = 10) { 60_000L }.single()
        assertEquals(TransmissionState.PROCESSING, db.transmissionDao().getById("TX1")!!.processingState)

        // The next process launches as run-B and recovers run-A's dangling lease (FR-RUN-8).
        val recovered = queue.recoverStaleLeases("run-B")
        assertEquals(1, recovered)
        assertEquals(TransmissionState.CAPTURED, db.transmissionDao().getById("TX1")!!.processingState)
        assertEquals(WorkQueueState.READY, db.workQueueDao().getById(leasedByRunA.id)!!.state)

        // Passes are idempotent (§5.2): run-B re-leases and completes with the identical result.
        val leasedByRunB = queue.leaseBatch("run-B", limit = 10) { 60_000L }.single()
        assertEquals(leasedByRunA.id, leasedByRunB.id)
        queue.completePass(leasedByRunB, TransmissionState.COMPLETE)

        assertEquals(TransmissionState.COMPLETE, db.transmissionDao().getById("TX1")!!.processingState)
        assertNull(db.workQueueDao().getById(leasedByRunB.id))
    }

    @Test
    @Requirement("AC-99")
    public fun a_hanging_pass_is_cancelled_at_its_deadline_and_the_queue_keeps_draining(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session())
        db.transmissionDao().insert(TestFixtures.transmission("TX-HANG"))
        db.transmissionDao().insert(TestFixtures.transmission("TX-OK"))
        // maxAttempts = 1: the first timeout is immediately terminal, matching AC-99's wording.
        val queue = WorkQueue(db, clock, maxAttempts = 1)
        queue.enqueue("TX-HANG", PassId.B_OFFLINE)
        queue.enqueue("TX-OK", PassId.B_OFFLINE)

        val leased = queue.leaseBatch("run-1", limit = 10) { 100L }
        val hangItem = leased.single { it.transmissionId == "TX-HANG" }
        val okItem = leased.single { it.transmissionId == "TX-OK" }

        // A FakeAsrEngine that never returns — the deadline, not the engine, ends this.
        val outcome = queue.runLeased(hangItem) {
            delay(Long.MAX_VALUE / 2)
            error("unreachable")
        }
        assertTrue(outcome is PassRunOutcome.Errored)
        assertEquals(WorkQueueState.FAILED, db.workQueueDao().getById(hangItem.id)!!.state)
        assertEquals(TransmissionState.FAILED, db.transmissionDao().getById("TX-HANG")!!.processingState)

        // The queue kept draining: the other item runs to completion right after.
        queue.runLeased(okItem) { PassRunOutcome.Finished(TransmissionState.COMPLETE) }
        assertNull(db.workQueueDao().getById(okItem.id))
        assertEquals(TransmissionState.COMPLETE, db.transmissionDao().getById("TX-OK")!!.processingState)
    }

    @Test
    @Requirement("AC-51")
    public fun a_repeatedly_failing_pass_lands_failed_without_blocking_the_queue(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session())
        db.transmissionDao().insert(TestFixtures.transmission("TX-BAD"))
        db.transmissionDao().insert(TestFixtures.transmission("TX-GOOD"))
        val queue = WorkQueue(db, clock, maxAttempts = 3)
        queue.enqueue("TX-BAD", PassId.B_OFFLINE)

        var item = queue.leaseBatch("run-1", limit = 10) { 60_000L }.single()
        repeat(2) {
            queue.failPass(item, "boom")
            val row = db.workQueueDao().getById(item.id)!!
            assertEquals(WorkQueueState.READY, row.state) // retries remain — the queue is not blocked
            item = queue.leaseBatch("run-1", limit = 10) { 60_000L }.single()
        }
        queue.failPass(item, "boom") // third failure exhausts maxAttempts = 3

        val finalRow = db.workQueueDao().getById(item.id)!!
        assertEquals(WorkQueueState.FAILED, finalRow.state)
        assertEquals("boom", finalRow.lastError)
        assertEquals(TransmissionState.FAILED, db.transmissionDao().getById("TX-BAD")!!.processingState)

        // A different item, enqueued only now, proves TX-BAD's exhausted retries never blocked the queue.
        queue.enqueue("TX-GOOD", PassId.B_OFFLINE)
        val good = queue.leaseBatch("run-1", limit = 10) { 60_000L }.single { it.transmissionId == "TX-GOOD" }
        queue.completePass(good, TransmissionState.COMPLETE)
        assertEquals(TransmissionState.COMPLETE, db.transmissionDao().getById("TX-GOOD")!!.processingState)
    }

    @Test
    @Requirement("AC-51")
    public fun re_enqueueing_a_completed_pass_succeeds(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session())
        db.transmissionDao().insert(TestFixtures.transmission("TX1"))
        val queue = WorkQueue(db, clock)

        queue.enqueue("TX1", PassId.B_OFFLINE)
        val leased = queue.leaseBatch("run-1", limit = 10) { 60_000L }.single()
        queue.completePass(leased, TransmissionState.COMPLETE)

        // The transmission needs to leave COMPLETE before a fresh PROCESSING lease is legal again.
        db.transmissionDao().requireLegalTransition("TX1", TransmissionState.PROCESSING)
        queue.enqueue("TX1", PassId.B_OFFLINE) // must not throw — the partial index covers active states only
        val second = db.workQueueDao().findByTransmissionAndPass("TX1", PassId.B_OFFLINE.name).single()
        assertEquals(WorkQueueState.READY, second.state)
    }

    @Test
    public fun a_second_pass_completing_after_the_transmission_is_already_final_does_not_throw(): Unit = runTest {
        // technical design §7.1: idx_wq_active is keyed on (transmission_id, pass) because more
        // than one pass can be leased for the same transmission at once (residency table:
        // "Pass B, C, E, FUSE" concurrently). Whichever pass reports last must not crash trying
        // to re-transition an already-final transmission.
        db.sessionDao().insert(TestFixtures.session())
        db.transmissionDao().insert(TestFixtures.transmission("TX1"))
        val queue = WorkQueue(db, clock)
        queue.enqueue("TX1", PassId.B_OFFLINE)
        queue.enqueue("TX1", PassId.D_RESOLVE)

        val leased = queue.leaseBatch("run-1", limit = 10) { 60_000L }
        val passB = leased.single { it.pass == PassId.B_OFFLINE }
        val passD = leased.single { it.pass == PassId.D_RESOLVE }

        // Pass B finishes first and commits the transmission to COMPLETE.
        queue.completePass(passB, TransmissionState.COMPLETE)
        assertEquals(TransmissionState.COMPLETE, db.transmissionDao().getById("TX1")!!.processingState)

        // Pass D finishes after — this must not throw, and must not clobber Pass B's COMPLETE.
        queue.completePass(passD, TransmissionState.COMPLETE)
        assertEquals(TransmissionState.COMPLETE, db.transmissionDao().getById("TX1")!!.processingState)
        assertNull(db.workQueueDao().getById(passB.id))
        assertNull(db.workQueueDao().getById(passD.id))
    }

    @Test
    public fun a_second_pass_failing_after_the_transmission_is_already_complete_does_not_throw(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session())
        db.transmissionDao().insert(TestFixtures.transmission("TX1"))
        val queue = WorkQueue(db, clock, maxAttempts = 1)
        queue.enqueue("TX1", PassId.B_OFFLINE)
        queue.enqueue("TX1", PassId.D_RESOLVE)

        val leased = queue.leaseBatch("run-1", limit = 10) { 60_000L }
        val passB = leased.single { it.pass == PassId.B_OFFLINE }
        val passD = leased.single { it.pass == PassId.D_RESOLVE }

        queue.completePass(passB, TransmissionState.COMPLETE)
        // Pass D fails after the transmission is already COMPLETE — must not throw, and COMPLETE
        // (a real, useful result) must not be overwritten by a sibling pass's failure.
        queue.failPass(passD, "boom")

        assertEquals(TransmissionState.COMPLETE, db.transmissionDao().getById("TX1")!!.processingState)
        assertEquals(WorkQueueState.FAILED, db.workQueueDao().getById(passD.id)!!.state)
    }

    @Test
    public fun the_active_state_index_does_not_restrict_a_terminal_failed_duplicate(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session())
        db.transmissionDao().insert(TestFixtures.transmission("TX1"))
        val dao = db.workQueueDao()

        val failedId = dao.insert(
            org.ort.data.entity.WorkQueueItemEntity(
                transmissionId = "TX1",
                pass = PassId.B_OFFLINE,
                state = WorkQueueState.FAILED,
                priority = 0,
                enqueuedAt = 0L,
            ),
        )
        // A second, active row for the same (transmission, pass) is fine: FAILED sits outside
        // the partial index's active-state set (technical design §7.1).
        val readyId = dao.insert(
            org.ort.data.entity.WorkQueueItemEntity(
                transmissionId = "TX1",
                pass = PassId.B_OFFLINE,
                state = WorkQueueState.READY,
                priority = 0,
                enqueuedAt = 1L,
            ),
        )
        assertTrue(failedId != readyId)

        // But two ACTIVE rows for the same (transmission, pass) collide.
        var threw = false
        try {
            dao.insert(
                org.ort.data.entity.WorkQueueItemEntity(
                    transmissionId = "TX1",
                    pass = PassId.B_OFFLINE,
                    state = WorkQueueState.LEASED,
                    priority = 0,
                    enqueuedAt = 2L,
                ),
            )
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            threw = true
        }
        assertTrue("a second active row for the same (transmission, pass) must violate idx_wq_active", threw)
    }
}
