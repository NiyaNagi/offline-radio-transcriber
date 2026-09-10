package org.ort.pipeline.digest

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.data.OrtDatabase
import org.robolectric.RobolectricTestRunner

/**
 * E2-A03 (`results/e2e-audit/checklist.md`), FR-DIG-3, FR-DIG-11: [RoomProseSummaryStore] over a
 * real (in-memory) `OrtDatabase` — the same DAO path `:data`'s `MigrationTest` proves survives
 * migration 7→8, exercised here through the [ProseSummaryStore] contract every other test in this
 * package already runs against [FakeProseSummaryStore]/[InMemoryProseSummaryStore].
 */
@RunWith(RobolectricTestRunner::class)
class RoomProseSummaryStoreTest {

    private fun summary(threadId: String, text: String, generatedAtMillis: Long = 1_000L) = ProseSummary(
        threadId = threadId,
        text = text,
        sourceTransmissionIds = listOf("tx-1", "tx-2"),
        generatedAtMillis = generatedAtMillis,
        modelId = "gemma3-1b-it-int4",
    )

    @Test
    fun `store then forThread round-trips through the real database`() = runTest {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        val store = RoomProseSummaryStore(db)

        store.store(summary("thread-1", "Exchanged signal reports."))

        val result = store.forThread("thread-1")
        assertEquals("Exchanged signal reports.", result?.text)
        assertEquals(listOf("tx-1", "tx-2"), result?.sourceTransmissionIds)
        assertEquals("gemma3-1b-it-int4", result?.modelId)
    }

    @Test
    fun `a second store for the same thread replaces the row, not accumulates`() = runTest {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        val store = RoomProseSummaryStore(db)

        store.store(summary("thread-1", "first draft"))
        store.store(summary("thread-1", "second draft", generatedAtMillis = 2_000L))

        assertEquals("second draft", store.forThread("thread-1")?.text)
        assertEquals(1, store.all().size)
    }

    @Test
    fun `forThreads returns only the known threads`() = runTest {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        val store = RoomProseSummaryStore(db)
        store.store(summary("thread-1", "a"))
        store.store(summary("thread-2", "b"))

        val result = store.forThreads(listOf("thread-1", "thread-3"))

        assertEquals(listOf("thread-1"), result.map { it.threadId })
    }

    @Test
    fun `forThread returns null for a thread with no summary`() = runTest {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        val store = RoomProseSummaryStore(db)

        assertNull(store.forThread("never-summarized"))
    }
}
