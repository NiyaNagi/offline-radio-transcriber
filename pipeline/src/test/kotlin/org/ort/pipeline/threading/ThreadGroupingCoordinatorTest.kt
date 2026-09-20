package org.ort.pipeline.threading

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.ort.data.entity.ThreadJoinReason
import org.ort.data.entity.ThreadKind
import org.ort.pipeline.threading.fake.FakeThreadRepository

/**
 * [ThreadGroupingCoordinator] driven against [FakeThreadRepository] — no Room, no Robolectric.
 * The true end-to-end proof against a real database is
 * `org.ort.pipeline.passb.DataPassBResultSinkThreadingTest` (AC-163..165 "verified end to end
 * against a tape").
 */
class ThreadGroupingCoordinatorTest {

    private fun closure(
        id: String,
        startedAtUtc: Long,
        endedAtUtc: Long,
        frequencyHz: Long?,
        samplePosition: Long,
        stationId: String? = null,
        voiceprintId: String? = null,
        sessionId: String = "S1",
    ) = TransmissionClosure(
        id, sessionId, samplePosition, startedAtUtc, endedAtUtc, frequencyHz, null, stationId, voiceprintId,
    )

    @Test
    fun `AC_163 a two-station QSO threads its overs into one thread`() = runTest {
        val repo = FakeThreadRepository()
        val coordinator = ThreadGroupingCoordinator(repo)
        val freq = 146_520_000L
        repo.seedClosure(closure("TX1", 0, 5_000, freq, 0, stationId = "K7ABC"))
        repo.seedClosure(closure("TX2", 10_000, 15_000, freq, 1, stationId = "W7XYZ"))
        repo.seedClosure(closure("TX3", 20_000, 25_000, freq, 2, stationId = "K7ABC"))

        coordinator.onTransmissionClosed("TX1")
        coordinator.onTransmissionClosed("TX2")
        coordinator.onTransmissionClosed("TX3")

        val threadId = repo.threadIdFor("TX1")
        assertNotNull(threadId)
        assertEquals(threadId, repo.threadIdFor("TX2"))
        assertEquals(threadId, repo.threadIdFor("TX3"))
        assertEquals(3, repo.transmissionCountOf(threadId!!))
        assertEquals(ThreadKind.QSO, repo.kindOf(threadId))
    }

    @Test
    fun `AC_165 grouping runs on Pass B closure -- an over whose transcript never completes still joins`() = runTest {
        val repo = FakeThreadRepository()
        val coordinator = ThreadGroupingCoordinator(repo)
        val freq = 462_562_500L
        repo.seedClosure(closure("TX1", 0, 5_000, freq, 0, stationId = "KE7AAA"))
        // TX2 has no resolved callsign at all -- exactly "a transcript that never completes":
        // Pass B rejected/failed to resolve anything, but it still closed the transmission.
        repo.seedClosure(closure("TX2", 10_000, 15_000, freq, 1, stationId = null, voiceprintId = null))

        coordinator.onTransmissionClosed("TX1")
        coordinator.onTransmissionClosed("TX2")

        val threadId = repo.threadIdFor("TX1")
        assertNotNull(threadId)
        assertEquals(threadId, repo.threadIdFor("TX2"), "grouping must join by frequency/gap alone")
    }

    @Test
    fun `two interleaved frequencies from a dual-receive rig never merge into one thread`() = runTest {
        val repo = FakeThreadRepository()
        val coordinator = ThreadGroupingCoordinator(repo)
        val bandA = 146_520_000L
        val bandB = 446_000_000L
        // Interleaved arrival order: A, B, A, B -- a dual-band rig mixing both receivers' audio.
        repo.seedClosure(closure("A1", 0, 1_000, bandA, 0))
        repo.seedClosure(closure("B1", 500, 1_500, bandB, 1))
        repo.seedClosure(closure("A2", 2_000, 3_000, bandA, 2))
        repo.seedClosure(closure("B2", 2_500, 3_500, bandB, 3))

        listOf("A1", "B1", "A2", "B2").forEach { coordinator.onTransmissionClosed(it) }

        val threadA = repo.threadIdFor("A1")
        val threadB = repo.threadIdFor("B1")
        assertNotNull(threadA)
        assertNotNull(threadB)
        assertNotEquals(threadA, threadB)
        assertEquals(threadA, repo.threadIdFor("A2"), "A2 must join A1's thread, not B1's")
        assertEquals(threadB, repo.threadIdFor("B2"), "B2 must join B1's thread, not A1's")
        assertEquals(2, repo.transmissionCountOf(threadA!!))
        assertEquals(2, repo.transmissionCountOf(threadB!!))
    }

    @Test
    fun `a gap beyond the threshold starts a second thread on the same frequency`() = runTest {
        val repo = FakeThreadRepository()
        val grouper = ThreadGrouper(ThreadGroupingConfig(gapThresholdMillis = 60_000L))
        val coordinator = ThreadGroupingCoordinator(repo, grouper)
        val freq = 146_520_000L
        repo.seedClosure(closure("TX1", 0, 5_000, freq, 0))
        repo.seedClosure(closure("TX2", 5_000 + 61_000, 5_000 + 61_000 + 1_000, freq, 1)) // 61s gap

        coordinator.onTransmissionClosed("TX1")
        coordinator.onTransmissionClosed("TX2")

        assertNotEquals(repo.threadIdFor("TX1"), repo.threadIdFor("TX2"))
    }

    @Test
    fun `an unlistened capture gap between two same-frequency overs starts a new thread`() = runTest {
        val repo = FakeThreadRepository()
        val coordinator = ThreadGroupingCoordinator(repo)
        val freq = 146_520_000L
        repo.seedClosure(closure("TX1", 0, 5_000, freq, 0))
        repo.seedClosure(closure("TX2", 6_000, 7_000, freq, 1)) // 1s gap -- would join by arithmetic alone
        repo.seedCaptureGap("S1", fromUtc = 5_000, toUtc = 6_000)

        coordinator.onTransmissionClosed("TX1")
        coordinator.onTransmissionClosed("TX2")

        assertNotEquals(repo.threadIdFor("TX1"), repo.threadIdFor("TX2"))
    }

    @Test
    fun `a single over starts and stays its own one-transmission thread`() = runTest {
        val repo = FakeThreadRepository()
        val coordinator = ThreadGroupingCoordinator(repo)
        repo.seedClosure(closure("TX1", 0, 5_000, 146_520_000L, 0))

        coordinator.onTransmissionClosed("TX1")

        val threadId = repo.threadIdFor("TX1")
        assertNotNull(threadId)
        assertEquals(1, repo.transmissionCountOf(threadId!!))
    }

    @Test
    fun `a retried Pass B attempt is idempotent -- calling onTransmissionClosed twice does not double count`() =
        runTest {
            val repo = FakeThreadRepository()
            val coordinator = ThreadGroupingCoordinator(repo)
            repo.seedClosure(closure("TX1", 0, 5_000, 146_520_000L, 0))

            coordinator.onTransmissionClosed("TX1")
            coordinator.onTransmissionClosed("TX1") // e.g. a Failed attempt's later Accepted retry

            val threadId = repo.threadIdFor("TX1")
            assertNotNull(threadId)
            assertEquals(1, repo.transmissionCountOf(threadId!!))
        }

    @Test
    fun `a transmission with no persisted row is a harmless no-op`() = runTest {
        val repo = FakeThreadRepository()
        val coordinator = ThreadGroupingCoordinator(repo)

        coordinator.onTransmissionClosed("does-not-exist")

        assertNull(repo.threadIdFor("does-not-exist"))
    }

    // ---- R-1098: ThreadGrouper.decide's own verdict reaches the repository, not just the thread ----

    @Test
    fun `R_1098 the coordinator hands ThreadGrouper's real reason to the repository for every decision`() = runTest {
        val repo = FakeThreadRepository()
        val coordinator = ThreadGroupingCoordinator(repo)
        val freq = 146_520_000L
        repo.seedClosure(closure("TX1", 0, 5_000, freq, 0))
        repo.seedClosure(closure("TX2", 10_000, 15_000, freq, 1)) // within the gap: joins TX1's thread

        coordinator.onTransmissionClosed("TX1")
        coordinator.onTransmissionClosed("TX2")

        assertEquals(
            ThreadJoinReason.FIRST_TRANSMISSION_IN_SESSION,
            repo.joinReasonFor("TX1"),
            "the first transmission in a session must record its own real start reason",
        )
        assertEquals(
            ThreadJoinReason.SAME_FREQUENCY_WITHIN_GAP,
            repo.joinReasonFor("TX2"),
            "a transmission that joins by matching frequency must record that real reason, not the " +
                "other transmission's",
        )
    }

    @Test
    fun `R_1098 a gap-exceeded new thread records its own distinct reason, not FIRST_TRANSMISSION`() = runTest {
        val repo = FakeThreadRepository()
        val grouper = ThreadGrouper(ThreadGroupingConfig(gapThresholdMillis = 60_000L))
        val coordinator = ThreadGroupingCoordinator(repo, grouper)
        val freq = 146_520_000L
        repo.seedClosure(closure("TX1", 0, 5_000, freq, 0))
        repo.seedClosure(closure("TX2", 5_000 + 61_000, 5_000 + 61_000 + 1_000, freq, 1)) // 61s gap

        coordinator.onTransmissionClosed("TX1")
        coordinator.onTransmissionClosed("TX2")

        assertEquals(ThreadJoinReason.FIRST_TRANSMISSION_IN_SESSION, repo.joinReasonFor("TX1"))
        assertEquals(ThreadJoinReason.NEW_THREAD_GAP_EXCEEDED, repo.joinReasonFor("TX2"))
    }

    @Test
    fun `R_1098 an unlistened capture gap records its own distinct reason`() = runTest {
        val repo = FakeThreadRepository()
        val coordinator = ThreadGroupingCoordinator(repo)
        val freq = 146_520_000L
        repo.seedClosure(closure("TX1", 0, 5_000, freq, 0))
        repo.seedClosure(closure("TX2", 6_000, 7_000, freq, 1))
        repo.seedCaptureGap("S1", fromUtc = 5_000, toUtc = 6_000)

        coordinator.onTransmissionClosed("TX1")
        coordinator.onTransmissionClosed("TX2")

        assertEquals(ThreadJoinReason.NEW_THREAD_UNLISTENED_CAPTURE_GAP, repo.joinReasonFor("TX2"))
    }
}
