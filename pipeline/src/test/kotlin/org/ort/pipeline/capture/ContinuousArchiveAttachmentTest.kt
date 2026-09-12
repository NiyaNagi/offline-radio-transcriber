package org.ort.pipeline.capture

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ort.testing.Requirement

/**
 * WPARC (constitution IV "capture never blocks, never drops, never lies"; FR-RUN-12). Register
 * R-1038: a *stalled* writer (as opposed to a merely slow one) must not grow this queue on the
 * heap without bound — bounded here as a stated [ContinuousArchiveAttachment.ARCHIVE_QUEUE_BOUND_SECONDS]
 * of audio, past which [ContinuousArchiveAttachment.offer] drops the frame and coalesces the
 * dropped span into one report rather than buffering forever. Every case here drives
 * [ContinuousArchiveAttachment]'s own [kotlinx.coroutines.test.TestCoroutineScheduler] directly —
 * no real sleep anywhere, and a "stalled" writer is modelled with a suspended
 * [CompletableDeferred] under virtual time, never a real blocking call, so a mistake here cannot
 * hang the suite.
 */
class ContinuousArchiveAttachmentTest {

    @Test
    @Requirement("FR-RUN-12")
    fun `a stalled archive writer never blocks or drops a later offer, up to its stated capacity`() = runTest {
        val unstick = CompletableDeferred<Unit>()
        val appended = mutableListOf<Long>()
        // A capacity comfortably above everything this test offers -- proving the "never blocks,
        // never drops" half of the contract holds up to a stated bound, distinct from the
        // R-1038 tests below, which prove what happens *past* that bound.
        val attachment = ContinuousArchiveAttachment(
            append = { _, framePosition ->
                // Permanently "stalled" until the test says otherwise -- a suspend await, never a
                // real sleep or a real blocking call, so a bug here cannot hang the suite.
                if (framePosition == 0L) unstick.await()
                appended.add(framePosition)
            },
            finishWriter = {},
            capacity = 2_000,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        // 1000 offers before the consumer coroutine has run even once -- offer() must return for
        // every one of them without suspending. This is the structural proof: Channel.trySend
        // never suspends, and a channel with room left never fails for capacity either.
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

    // --- Register R-1038: a stalled (not merely slow) writer must not grow the queue unbounded ---

    @Test
    @Requirement("R-1038")
    fun `R_1038 a writer that never returns keeps queue growth bounded and coalesces one hole for the whole stall`() =
        runTest {
            val neverReturns = CompletableDeferred<Unit>()
            var appendCount = 0
            var overflowStart: Long? = null
            var overflowCount: Int? = null
            val capacity = 5
            val attachment = ContinuousArchiveAttachment(
                append = { _, framePosition ->
                    appendCount++
                    if (framePosition == 0L) neverReturns.await() // stalls forever on the very first message
                },
                finishWriter = {},
                onOverflow = { start, count ->
                    overflowStart = start
                    overflowCount = count
                },
                capacity = capacity,
                dispatcher = StandardTestDispatcher(testScheduler),
            )

            // "Fed hours of virtual-time frames": 1600-sample (100ms) chunks, one real hour's
            // worth -- 36,000 of them -- offered before the consumer ever runs. An unbounded
            // channel would buffer all 36,000 (57.6 MB of ShortArray references); this one must
            // only ever hold `capacity` of them.
            val framesPerHour = 36_000
            for (i in 0 until framesPerHour) {
                attachment.offer(ShortArray(1_600), framePosition = i * 1_600L)
            }

            testScheduler.runCurrent() // the consumer picks up message 0 and stalls on it forever
            assertEquals(
                "only the queue's own capacity worth of messages can ever have been buffered; " +
                    "the consumer processes exactly the first one before stalling",
                1,
                appendCount,
            )

            // Session end: the writer still never returned. The whole dropped span -- from the
            // (capacity+1)-th frame through the very last one offered -- is reported as ONE hole,
            // not one row per dropped frame.
            attachment.finishAndAwait(timeoutMillis = 100)

            val expectedDroppedFrames = framesPerHour - capacity
            assertEquals(
                "the hole starts at the first frame that could not be queued",
                capacity * 1_600L,
                overflowStart,
            )
            assertEquals(
                "the hole covers every dropped frame, coalesced into one span",
                expectedDroppedFrames * 1_600,
                overflowCount,
            )
        }

    @Test
    @Requirement("R-1038")
    fun `R_1038 a writer that stalls then recovers closes the hole where archiving resumed`() = runTest {
        val unstick = CompletableDeferred<Unit>()
        val appended = mutableListOf<Long>()
        var overflowStart: Long? = null
        var overflowCount: Int? = null
        val capacity = 3
        val attachment = ContinuousArchiveAttachment(
            append = { _, framePosition ->
                if (framePosition == 0L) unstick.await()
                appended.add(framePosition)
            },
            finishWriter = {},
            onOverflow = { start, count ->
                overflowStart = start
                overflowCount = count
            },
            capacity = capacity,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        // Fill the queue (3 messages), then overflow it with 7 more -- all offered before the
        // consumer has run, so this is deterministic: exactly `capacity` are buffered.
        for (i in 0 until 10) attachment.offer(ShortArray(10), framePosition = i * 10L)

        testScheduler.runCurrent() // the consumer picks up message 0 and stalls
        assertEquals("nothing reported while the stall is still ongoing", null, overflowStart)

        unstick.complete(Unit)
        testScheduler.advanceUntilIdle() // the consumer drains its buffered backlog (messages 0-2)

        // The writer has "recovered" (the consumer is idle, waiting for more) -- a fresh offer
        // now succeeds immediately, which is exactly what closes a coalesced span.
        attachment.offer(ShortArray(10), framePosition = 200L)
        testScheduler.advanceUntilIdle()

        assertEquals("the hole starts at the first dropped frame", capacity * 10L, overflowStart)
        assertEquals("the hole covers only the frames actually dropped, not the ones queued", 70, overflowCount)
        assertTrue("archiving must have resumed with the frame that closed the hole", 200L in appended)
    }

    @Test
    @Requirement("R-1038")
    fun `R_1038 a permanently wedged writer does not hang finishAndAwait`() = runTest {
        val attachment = ContinuousArchiveAttachment(
            append = { _, _ -> CompletableDeferred<Unit>().await() }, // never returns, ever
            finishWriter = { error("must not be called against a wedged writer") },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        attachment.offer(ShortArray(1), 0L)
        testScheduler.runCurrent() // the consumer picks up the one message and wedges on it

        // Must return (give up), not hang, once its own bounded timeout elapses -- proven by this
        // suspend call itself returning inside runTest's own virtual-time budget.
        attachment.finishAndAwait(timeoutMillis = 50)
    }
}
