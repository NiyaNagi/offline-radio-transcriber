package org.ort.pipeline.capture

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ort.testing.Requirement

/**
 * WPARC (constitution IV "capture never blocks, never drops, never lies"; FR-RUN-12). Every case
 * here drives [ContinuousArchiveAttachment]'s own [kotlinx.coroutines.test.TestCoroutineScheduler]
 * directly — no real sleep anywhere, and a "stalled" writer is modelled with `delay(Long.MAX_VALUE)`
 * under virtual time, never a real blocking call, so a mistake here cannot hang the suite.
 */
class ContinuousArchiveAttachmentTest {

    @Test
    @Requirement("FR-RUN-12")
    fun `a stalled archive writer never blocks or drops a later offer, proven with virtual time`() = runTest {
        val unstick = CompletableDeferred<Unit>()
        val appended = mutableListOf<Long>()
        val attachment = ContinuousArchiveAttachment(
            append = { _, framePosition ->
                // Permanently "stalled" until the test says otherwise -- a suspend await, never a
                // real sleep or a real blocking call, so a bug here cannot hang the suite.
                if (framePosition == 0L) unstick.await()
                appended.add(framePosition)
            },
            finishWriter = {},
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        // 1000 offers before the consumer coroutine has run even once -- offer() must return for
        // every one of them without suspending. This is the structural proof: Channel.trySend on
        // an UNLIMITED channel can never suspend and can never fail for capacity, so it can never
        // silently drop a message either -- both properties this test goes on to check.
        repeat(1_000) { attachment.offer(ShortArray(1), it.toLong()) }

        testScheduler.runCurrent() // the consumer starts, picks up message 0, and blocks on `unstick`
        assertEquals("nothing should have been applied yet", emptyList<Long>(), appended)

        // The frame path can still enqueue more work while the consumer is permanently stuck.
        attachment.offer(ShortArray(1), 1_000L)

        unstick.complete(Unit)
        testScheduler.advanceUntilIdle()

        // Every offer -- the 1000 sent before the consumer ever ran, and the one sent while it
        // was stuck -- was eventually applied, in order, none silently dropped.
        assertEquals((0L..1_000L).toList(), appended)
    }

    @Test
    @Requirement("FR-RUN-12")
    fun `an append that throws records a hole through onFailure and the consumer keeps draining`() = runTest {
        var reportedStart: Long? = null
        var reportedCount: Int? = null
        var reportedReason: String? = null
        var laterAppends = 0
        val attachment = ContinuousArchiveAttachment(
            append = { _, framePosition ->
                if (framePosition == 0L) error("simulated disk failure") else laterAppends++
            },
            finishWriter = {},
            onFailure = { start, count, reason ->
                reportedStart = start
                reportedCount = count
                reportedReason = reason
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        attachment.offer(ShortArray(5), framePosition = 0L)
        attachment.offer(ShortArray(5), framePosition = 5L)
        testScheduler.advanceUntilIdle()

        assertEquals(0L, reportedStart)
        assertEquals(5, reportedCount)
        assertEquals("IllegalStateException", reportedReason)
        assertEquals("the consumer must keep draining after a failure, never stop silently", 1, laterAppends)
    }

    @Test
    @Requirement("FR-RUN-12")
    fun `finishAndAwait waits for every prior offer before flushing the writer`() = runTest {
        val order = mutableListOf<String>()
        val attachment = ContinuousArchiveAttachment(
            append = { _, _ -> order.add("append") },
            finishWriter = { order.add("finish") },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        attachment.offer(ShortArray(1), 0L)
        attachment.offer(ShortArray(1), 1L)
        attachment.finishAndAwait()

        assertEquals(listOf("append", "append", "finish"), order)
    }
}
