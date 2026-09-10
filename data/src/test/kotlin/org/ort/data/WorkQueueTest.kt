package org.ort.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
        // CI cross-platform diagnostic task (see SqliteDiagnostics's own kdoc): always-on, printed
        // for this exact instance so a Linux CI run's report is directly comparable to this class's
        // own failing assertion below.
        runBlocking { SqliteDiagnostics.report(db, "WorkQueueTest.openDatabase") }
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
    @Requirement("FR-RUN-9")
    public fun FR_RUN_9_a_failed_item_past_max_attempts_is_requeued_to_ready_with_attempts_reset(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session())
        db.transmissionDao().insert(TestFixtures.transmission("TX-BAD"))
        val queue = WorkQueue(db, clock, maxAttempts = 1)
        queue.enqueue("TX-BAD", PassId.B_OFFLINE)
        val item = queue.leaseBatch("run-1", limit = 10) { 60_000L }.single()
        queue.failPass(item, "no ASR model installed") // exhausts maxAttempts = 1 immediately
        val exhausted = db.workQueueDao().getById(item.id)!!
        assertEquals(WorkQueueState.FAILED, exhausted.state)
        assertEquals(TransmissionState.FAILED, db.transmissionDao().getById("TX-BAD")!!.processingState)

        val requeued = queue.requeueFailed()

        assertEquals(1, requeued)
        val row = db.workQueueDao().getById(item.id)!!
        assertEquals(WorkQueueState.READY, row.state)
        assertEquals(0, row.attemptCount)
        // The prior error stays reachable rather than being erased silently (constitution III).
        assertEquals("no ASR model installed", row.lastError)
        // FAILED -> PROCESSING is the state machine's documented reprocess path (core/TransmissionState.kt).
        assertEquals(TransmissionState.PROCESSING, db.transmissionDao().getById("TX-BAD")!!.processingState)
    }

    @Test
    @Requirement("FR-RUN-9")
    public fun FR_RUN_9_items_not_failed_are_untouched(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session())
        db.transmissionDao().insert(TestFixtures.transmission("TX-READY"))
        db.transmissionDao().insert(TestFixtures.transmission("TX-LEASED"))
        val queue = WorkQueue(db, clock)
        queue.enqueue("TX-LEASED", PassId.D_RESOLVE)
        val leasedItem = queue.leaseBatch("run-1", limit = 10) { 60_000L }.single { it.transmissionId == "TX-LEASED" }
        queue.enqueue("TX-READY", PassId.B_OFFLINE) // enqueued after the lease, so it stays READY

        val requeued = queue.requeueFailed()

        assertEquals(0, requeued)
        val readyRow = db.workQueueDao().findByTransmissionAndPass("TX-READY", PassId.B_OFFLINE.name).single()
        assertEquals(WorkQueueState.READY, readyRow.state)
        val leasedRow = db.workQueueDao().getById(leasedItem.id)!!
        assertEquals(WorkQueueState.LEASED, leasedRow.state)
        assertEquals(TransmissionState.PROCESSING, db.transmissionDao().getById("TX-LEASED")!!.processingState)
    }

    @Test
    @Requirement("FR-RUN-9")
    public fun FR_RUN_9_requeueFailed_returns_the_count_and_honours_the_error_prefix_filter(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session())
        db.transmissionDao().insert(TestFixtures.transmission("TX-NO-MODEL"))
        db.transmissionDao().insert(TestFixtures.transmission("TX-OTHER"))
        val queue = WorkQueue(db, clock, maxAttempts = 1)
        queue.enqueue("TX-NO-MODEL", PassId.B_OFFLINE)
        queue.enqueue("TX-OTHER", PassId.B_OFFLINE)
        val leased = queue.leaseBatch("run-1", limit = 10) { 60_000L }
        queue.failPass(leased.single { it.transmissionId == "TX-NO-MODEL" }, "no ASR model installed")
        queue.failPass(leased.single { it.transmissionId == "TX-OTHER" }, "decoder crashed")

        val requeued = queue.requeueFailed(lastErrorPrefix = "no ASR model")

        assertEquals(1, requeued)
        val stillFailed = db.workQueueDao().findByTransmissionAndPass("TX-OTHER", PassId.B_OFFLINE.name).single()
        assertEquals(WorkQueueState.FAILED, stillFailed.state)
        val nowReady = db.workQueueDao().findByTransmissionAndPass("TX-NO-MODEL", PassId.B_OFFLINE.name).single()
        assertEquals(WorkQueueState.READY, nowReady.state)
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
        } catch (e: android.database.SQLException) {
            // BundledSQLiteDriver (register R-204) throws the base `android.database.SQLException`
            // for a constraint failure, not always the `SQLiteConstraintException` subclass the
            // classic framework SQLite driver used — confirmed empirically at this exact call site.
            threw = e.message?.contains("UNIQUE constraint failed") == true
        }
        // CI cross-platform diagnostic task, this round: does the *identical* duplicate insert
        // throw when issued as raw SQL straight through the driver, bypassing Room's generated DAO
        // adapter entirely? Run unconditionally (not just on failure) so this prints on every
        // platform, including the Windows run this test already passes on, for a direct comparison
        // against whatever Linux CI reports next. Its own SAVEPOINT/ROLLBACK TO means it never
        // leaves a row behind for the assertion below.
        val rawProbe = SqliteDiagnostics.rawDuplicateInsertProbe(
            db,
            "WorkQueueTest.the_active_state_index_does_not_restrict...",
            "INSERT INTO work_queue_item (transmissionId, pass, state, priority, attemptCount, lastError, " +
                "shedLevel, leaseRunId, deadlineAt, enqueuedAt, startedAt) VALUES ('TX1', 'B_OFFLINE', " +
                "'LEASED', 0, 0, NULL, 0, NULL, NULL, 3, NULL)",
        )
        println(rawProbe)
        // CI cross-platform diagnostic task: on failure, fold the same evidence
        // SqliteDiagnostics.report already printed into the assertion message itself, so the
        // index listing that disproves the constraint travels with the failure, not just the log.
        // This round adds the decisive question: what is actually in `work_queue_item` for TX1
        // after the third insert that was supposed to fail? Row count alone distinguishes "three
        // rows, index not enforcing" from "two rows, the second write replaced rather than
        // inserted"; typeof(state) catches a binding/affinity difference the row's own value would
        // hide.
        val evidence = if (!threw) {
            SqliteDiagnostics.report(db, "FAILURE: the_active_state_index_does_not_restrict...") +
                SqliteDiagnostics.dumpRows(
                    db,
                    label = "FAILURE: the_active_state_index_does_not_restrict...",
                    table = "work_queue_item",
                    selectSql = "SELECT rowid, transmissionId, pass, state, typeof(state) FROM work_queue_item " +
                        "WHERE transmissionId = ?",
                    transmissionId = "TX1",
                    columnLabels = listOf("rowid", "transmissionId", "pass", "state", "typeof(state)"),
                ) +
                rawProbe
        } else {
            ""
        }
        assertTrue(
            "a second active row for the same (transmission, pass) must violate idx_wq_active\n$evidence",
            threw,
        )
    }

    /**
     * CI cross-platform fix (this task): the previous test proves `idx_wq_active` itself, via a
     * raw DAO insert that deliberately bypasses [WorkQueue]. This one proves the *application*
     * level guard [WorkQueue.enqueue] now carries for the same invariant — at most one active
     * (`READY`/`LEASED`/`DEFERRED`) row per `(transmissionId, pass)` — through the real production
     * entry point, so the invariant holds even on a platform where the partial unique index alone
     * turned out not to enforce it (Linux CI; see `data/build.gradle.kts`'s comment on this task).
     */
    @Test
    @Requirement("FR-RUN-2")
    public fun FR_RUN_2_enqueue_does_not_create_a_second_active_row_for_the_same_transmission_and_pass(): Unit =
        runTest {
            db.sessionDao().insert(TestFixtures.session())
            db.transmissionDao().insert(TestFixtures.transmission("TX1"))
            val queue = WorkQueue(db, clock)

            val firstId = queue.enqueue("TX1", PassId.B_OFFLINE)
            // Still READY — genuinely still active — unlike re_enqueueing_a_completed_pass_succeeds.
            val secondId = queue.enqueue("TX1", PassId.B_OFFLINE)

            assertEquals(firstId, secondId)
            val rows = db.workQueueDao().findByTransmissionAndPass("TX1", PassId.B_OFFLINE.name)
            assertEquals(1, rows.size)
            assertEquals(WorkQueueState.READY, rows.single().state)
        }

    @Test
    @Requirement("R-426")
    public fun R_426_failPass_writes_a_durable_attempt_row_with_the_real_reason_and_timing(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session())
        db.transmissionDao().insert(TestFixtures.transmission("TX1"))
        val queue = WorkQueue(db, clock)
        queue.enqueue("TX1", PassId.B_OFFLINE)

        val leased = queue.leaseBatch("run-1", limit = 10) { 60_000L }.single()
        val startedAt = clock.wallMillis() // leaseBatch stamps startedAt from the clock at this instant
        clock.advance(1_500)
        queue.failPass(leased, "out of memory in the decoder")

        val attempt = db.workQueueDao().attemptsFor(leased.id).single()
        assertEquals(1, attempt.attemptNo)
        assertEquals(startedAt, attempt.startedAtMillis)
        assertEquals(startedAt + 1_500, attempt.finishedAtMillis)
        assertEquals(org.ort.data.entity.WorkAttemptOutcome.FAILED, attempt.outcome)
        assertEquals("out of memory in the decoder", attempt.reason) // the same text failureReasons carries verbatim
    }

    @Test
    @Requirement("R-426")
    public fun R_426_a_deadline_timeout_is_recorded_as_its_own_outcome_not_a_generic_failure(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session())
        db.transmissionDao().insert(TestFixtures.transmission("TX-HANG"))
        val queue = WorkQueue(db, clock, maxAttempts = 1)
        queue.enqueue("TX-HANG", PassId.B_OFFLINE)
        val item = queue.leaseBatch("run-1", limit = 10) { 100L }.single()

        queue.runLeased(item) {
            delay(Long.MAX_VALUE / 2)
            error("unreachable")
        }

        val attempt = db.workQueueDao().attemptsFor(item.id).single()
        assertEquals(org.ort.data.entity.WorkAttemptOutcome.TIMEOUT, attempt.outcome)
        assertEquals("timeout", attempt.reason)
    }

    @Test
    @Requirement("R-426")
    public fun R_426_attemptsFor_lists_every_retry_in_order_ending_at_the_terminal_failure(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session())
        db.transmissionDao().insert(TestFixtures.transmission("TX-BAD"))
        val queue = WorkQueue(db, clock, maxAttempts = 3)
        queue.enqueue("TX-BAD", PassId.B_OFFLINE)

        var item = queue.leaseBatch("run-1", limit = 10) { 60_000L }.single()
        val reasons = listOf("out of memory in the decoder", "engine crashed", "out of memory in the decoder")
        reasons.forEachIndexed { index, reason ->
            queue.failPass(item, reason)
            if (index < reasons.lastIndex) item = queue.leaseBatch("run-1", limit = 10) { 60_000L }.single()
        }

        // Every id used by this item across its retries stays the same row (only the state cycles
        // READY <-> LEASED until the terminal FAILED), so one itemId covers the whole history.
        val attempts = db.workQueueDao().attemptsFor(item.id)
        assertEquals(listOf(1, 2, 3), attempts.map { it.attemptNo })
        assertEquals(reasons, attempts.map { it.reason })
        assertEquals(WorkQueueState.FAILED, db.workQueueDao().getById(item.id)!!.state)
    }

    @Test
    @Requirement("R-426")
    public fun R_426_an_attempt_row_outlives_the_queue_item_once_a_later_retry_succeeds(): Unit = runTest {
        // Constitution III, "nothing is deleted quietly": completePass deletes the work_queue_item
        // row outright (technical design 7.1, "the queue is not a history") -- the failed attempts
        // that came before the eventual success must still be reachable afterwards.
        db.sessionDao().insert(TestFixtures.session())
        db.transmissionDao().insert(TestFixtures.transmission("TX1"))
        val queue = WorkQueue(db, clock, maxAttempts = 5)
        queue.enqueue("TX1", PassId.B_OFFLINE)

        var item = queue.leaseBatch("run-1", limit = 10) { 60_000L }.single()
        queue.failPass(item, "transient decoder error")
        item = queue.leaseBatch("run-1", limit = 10) { 60_000L }.single()
        queue.completePass(item, TransmissionState.COMPLETE)

        assertNull(db.workQueueDao().getById(item.id)) // the queue row itself is gone
        val attempts = db.workQueueDao().attemptsFor(item.id)
        assertEquals(1, attempts.size) // its one failed attempt is still on record
        assertEquals("transient decoder error", attempts.single().reason)
    }
}
