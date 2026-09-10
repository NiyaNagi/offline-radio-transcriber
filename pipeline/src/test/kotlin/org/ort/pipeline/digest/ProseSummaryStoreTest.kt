package org.ort.pipeline.digest

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProseSummaryStoreTest {

    private fun summary(threadId: String, text: String, generatedAtMillis: Long = 1_000L) = ProseSummary(
        threadId = threadId,
        text = text,
        sourceTransmissionIds = listOf("t1"),
        generatedAtMillis = generatedAtMillis,
        modelId = "fake-model-1",
    )

    @Test
    fun `store replaces the previous summary for the same thread`() = runTest {
        val store = InMemoryProseSummaryStore()

        store.store(summary("thread-1", "first draft"))
        store.store(summary("thread-1", "second draft", generatedAtMillis = 2_000L))

        val result = store.forThread("thread-1")
        assertEquals("second draft", result?.text)
        assertEquals(1, store.all().size)
    }

    @Test
    fun `forThread returns null for a thread with no summary`() = runTest {
        val store = InMemoryProseSummaryStore()

        assertNull(store.forThread("never-summarized"))
    }

    @Test
    fun `forThreads returns only the known threads, in no particular order guarantee`() = runTest {
        val store = InMemoryProseSummaryStore()
        store.store(summary("thread-1", "a"))
        store.store(summary("thread-2", "b"))

        val result = store.forThreads(listOf("thread-1", "thread-3"))

        assertEquals(listOf("thread-1"), result.map { it.threadId })
    }

    @Test
    fun `FakeProseSummaryStore can be told to fail a store call`() = runTest {
        val store = FakeProseSummaryStore(failStoreWith = IllegalStateException("disk full"))

        val thrown = try {
            store.store(summary("thread-1", "a"))
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertEquals("disk full", thrown?.message)
        assertEquals(0, store.stored.size)
    }

    @Test
    fun `FakeProseSummaryStore records every successful store in call order`() = runTest {
        val store = FakeProseSummaryStore()

        store.store(summary("thread-1", "a"))
        store.store(summary("thread-2", "b"))
        store.store(summary("thread-1", "a-revised"))

        assertEquals(listOf("a", "b", "a-revised"), store.stored.map { it.text })
        assertEquals(2, store.all().size)
    }
}
