package org.ort.pipeline.capture

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.data.OrtDatabase
import org.ort.pipeline.PipelineTestFixtures
import org.ort.testing.Requirement
import org.ort.testing.TestClock
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * register R-133 (FR-STO-5): `Settings-Storage.dc.html`'s four-segment usage bar and "Next
 * deletion" row, sourced from real files and real session rows rather than invented. Uses a plain
 * JVM temp directory as the fake filesystem `R_133_accounting` writes into (no Robolectric needed
 * for pure `File` arithmetic) and hand-built [SessionStorageSummary] fakes for `R_133_next_deletion`
 * (no database needed to prove the pure prune-candidate rule); the final test proves the one
 * real-database assembler this file also adds, against Robolectric's real Room (the class runs
 * under [RobolectricTestRunner] for that one test's sake — the others need nothing it provides).
 */
@RunWith(RobolectricTestRunner::class)
class StorageAccountingTest {

    @Test
    @Requirement("R-133", "FR-STO-5")
    fun `R_133_accounting measures audio, models, records and lexicon bytes from real files`() {
        val filesDir = Files.createTempDirectory("storage-accounting-test").toFile()
        writeFile(File(filesDir, "audio/SESSION01/tx1.flac"), 100)
        writeFile(File(filesDir, "audio/SESSION01/tx2.flac"), 50)
        writeFile(File(filesDir, "models/sherpa-onnx/model.onnx"), 400)
        writeFile(File(filesDir, "lexicon/lexicon.tsv"), 25)
        val dbFile = File(filesDir, "ort.db")
        val walFile = File(filesDir, "ort.db-wal")
        writeFile(dbFile, 200)
        writeFile(walFile, 30)
        val missingShmFile = File(filesDir, "ort.db-shm") // never written -- WAL checkpointed away

        val clock = TestClock(startWallMillis = 5_000L)

        val accounting = runBlocking {
            measureStorageAccounting(filesDir, listOf(dbFile, walFile, missingShmFile), clock)
        }

        assertEquals("audio: tx1 + tx2", 150L, accounting.audioBytes)
        assertEquals("models: one file", 400L, accounting.modelBytes)
        assertEquals("records: db + wal, missing shm silently excluded", 230L, accounting.recordBytes)
        assertEquals("lexicon: one file", 25L, accounting.lexiconBytes)
        assertEquals(5_000L, accounting.measuredAtMillis)
        assertEquals(150L + 400L + 230L + 25L, accounting.totalBytes)
    }

    @Test
    @Requirement("R-133", "FR-STO-5")
    fun `R_133_accounting a directory that has never been written reads an honest zero, not a placeholder`() {
        val filesDir = Files.createTempDirectory("storage-accounting-empty-test").toFile()

        val accounting = runBlocking { measureStorageAccounting(filesDir, emptyList()) }

        assertEquals(0L, accounting.audioBytes)
        assertEquals(0L, accounting.modelBytes)
        assertEquals(0L, accounting.recordBytes)
        assertEquals(0L, accounting.lexiconBytes)
    }

    @Test
    @Requirement("R-133", "FR-STO-3a", "D26")
    fun `R_133_next_deletion the oldest session is the first prune candidate once over budget`() {
        val sessions = listOf(
            SessionStorageSummary("SESSION-OLDEST", startedAtMillis = 1_000L, overCount = 3, bytes = 900L),
            SessionStorageSummary("SESSION-MIDDLE", startedAtMillis = 2_000L, overCount = 5, bytes = 400L),
            SessionStorageSummary("SESSION-NEWEST", startedAtMillis = 3_000L, overCount = 1, bytes = 100L),
        )

        val nextDeletion = computeNextDeletion(
            sessionsOldestFirst = sessions,
            audioBytesUsed = 1_400L,
            budgetBytes = 1_000L,
            floorBytes = 100L,
            freeBytes = 5_000L,
            nowMillis = 9_999L,
        )

        assertEquals(
            NextDeletion(
                sessionId = "SESSION-OLDEST",
                startedAtMillis = 1_000L,
                overCount = 3,
                bytes = 900L,
                predictedAtMillis = 9_999L,
            ),
            nextDeletion,
        )
    }

    @Test
    @Requirement("R-133", "FR-STO-4")
    fun `R_133_next_deletion the floor also forces a prune candidate even with no budget set`() {
        val sessions =
            listOf(SessionStorageSummary("SESSION-ONLY", startedAtMillis = 1_000L, overCount = 2, bytes = 50L))

        val nextDeletion = computeNextDeletion(
            sessionsOldestFirst = sessions,
            audioBytesUsed = 50L,
            budgetBytes = null,
            floorBytes = 1_000L,
            freeBytes = 900L, // at/below the floor
            nowMillis = 1L,
        )

        assertEquals("SESSION-ONLY", nextDeletion?.sessionId)
    }

    @Test
    @Requirement("R-133", "FR-STO-3a")
    fun `R_133_next_deletion neither over budget nor at the floor reports null, not an invented session`() {
        val sessions =
            listOf(SessionStorageSummary("SESSION-FINE", startedAtMillis = 1_000L, overCount = 1, bytes = 50L))

        val nextDeletion = computeNextDeletion(
            sessionsOldestFirst = sessions,
            audioBytesUsed = 50L,
            budgetBytes = 1_000L,
            floorBytes = 100L,
            freeBytes = 5_000L,
            nowMillis = 1L,
        )

        assertNull(nextDeletion)
    }

    @Test
    @Requirement("R-133", "FR-STO-3a")
    fun `R_133_next_deletion an empty session list reports null even when over budget`() {
        val nextDeletion = computeNextDeletion(
            sessionsOldestFirst = emptyList(),
            audioBytesUsed = 5_000L,
            budgetBytes = 1_000L,
            floorBytes = 100L,
            freeBytes = 5_000L,
            nowMillis = 1L,
        )

        assertNull(nextDeletion)
    }

    @Test
    @Requirement("R-133", "FR-STO-5")
    fun `collectSessionStorageSummaries orders oldest first with real per-session over counts and bytes`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = OrtDatabase.create(context, inMemory = true)
        val filesDir = context.filesDir

        runBlocking {
            db.sessionDao().insert(PipelineTestFixtures.session("NEWER").copy(startedAt = 2_000L))
            db.sessionDao().insert(PipelineTestFixtures.session("OLDER").copy(startedAt = 1_000L))
            db.transmissionDao().insert(PipelineTestFixtures.transmission("TX-OLDER-1", sessionId = "OLDER"))
            db.transmissionDao().insert(PipelineTestFixtures.transmission("TX-OLDER-2", sessionId = "OLDER"))
            db.transmissionDao().insert(PipelineTestFixtures.transmission("TX-NEWER-1", sessionId = "NEWER"))
        }
        writeFile(File(filesDir, "audio/OLDER/TX-OLDER-1.flac"), 10)
        writeFile(File(filesDir, "audio/OLDER/TX-OLDER-2.flac"), 20)
        writeFile(File(filesDir, "audio/NEWER/TX-NEWER-1.flac"), 5)

        val summaries = runBlocking { collectSessionStorageSummaries(db, filesDir) }

        assertEquals(listOf("OLDER", "NEWER"), summaries.map { it.sessionId })
        assertEquals(2, summaries[0].overCount)
        assertEquals(30L, summaries[0].bytes)
        assertEquals(1, summaries[1].overCount)
        assertEquals(5L, summaries[1].bytes)
    }

    private fun writeFile(file: File, byteCount: Int) {
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(byteCount))
    }
}
