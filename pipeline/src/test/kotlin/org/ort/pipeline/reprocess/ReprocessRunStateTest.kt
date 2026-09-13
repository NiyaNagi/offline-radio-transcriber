package org.ort.pipeline.reprocess

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * register R-1067 (FR-REP-11): [ReprocessRunState] is [ReprocessWorker]'s own durable resume
 * checkpoint — no Android/WorkManager machinery needed to test it (constitution II), plain file
 * I/O against a real temp directory.
 */
class ReprocessRunStateTest {

    @Test
    fun `a fresh work id starts with every id handed to it, and persists the original total`(@TempDir dir: File) {
        val state = ReprocessRunState(dir, "work-1")

        val remaining = state.remainingOrInit(listOf("TX1", "TX2", "TX3"))

        assertEquals(listOf("TX1", "TX2", "TX3"), remaining)
        assertEquals(3, state.originalTotal(0))
    }

    @Test
    fun `a second call for the same work id resumes whatever markDone has not yet removed`(@TempDir dir: File) {
        val state = ReprocessRunState(dir, "work-2")
        state.remainingOrInit(listOf("TX1", "TX2", "TX3"))
        state.markDone("TX1")

        // A fresh ReprocessRunState instance, same work id -- simulating a new worker attempt
        // after process death re-reading the checkpoint from disk, not the same in-memory object.
        val resumed = ReprocessRunState(dir, "work-2")
        val remaining = resumed.remainingOrInit(listOf("TX1", "TX2", "TX3"))

        assertEquals(listOf("TX2", "TX3"), remaining, "TX1 was already marked done -- a resume must not repeat it")
        assertEquals(3, resumed.originalTotal(0), "the ORIGINAL total must survive a resume, not shrink to 2")
    }

    @Test
    fun `markDone never loses a sibling id it was not asked to remove`(@TempDir dir: File) {
        val state = ReprocessRunState(dir, "work-3")
        state.remainingOrInit(listOf("TX1", "TX2", "TX3"))

        state.markDone("TX2")

        assertEquals(listOf("TX1", "TX3"), state.remaining())
    }

    @Test
    fun `clear deletes the checkpoint so a later call for the same id starts fresh`(@TempDir dir: File) {
        val state = ReprocessRunState(dir, "work-4")
        state.remainingOrInit(listOf("TX1", "TX2"))
        state.markDone("TX1")

        state.clear()

        val after = ReprocessRunState(dir, "work-4")
        assertEquals(listOf("TX1", "TX2"), after.remainingOrInit(listOf("TX1", "TX2")))
    }

    @Test
    fun `a different work id never sees another run's checkpoint`(@TempDir dir: File) {
        val first = ReprocessRunState(dir, "work-5a")
        first.remainingOrInit(listOf("TX1"))
        first.markDone("TX1")

        val second = ReprocessRunState(dir, "work-5b")

        val resumed = second.remainingOrInit(listOf("TX9"))
        assertTrue(resumed == listOf("TX9"), "a new work id must never resume a stranger's checkpoint")
    }
}
