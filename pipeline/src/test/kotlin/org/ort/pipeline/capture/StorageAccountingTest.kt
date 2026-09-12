package org.ort.pipeline.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals("no bundled_assets.manifest written — nothing bundled to exclude", 0L, accounting.bundledBytes)
    }

    @Test
    @Requirement("AC-139", "FR-AST-3a")
    fun `AC_139 bundled assets do not count against the budget and are reported separately`() {
        val filesDir = Files.createTempDirectory("storage-accounting-bundled-test").toFile()
        // A bundled model (per the manifest below) and a genuinely side-loaded one the operator
        // added themselves — both physically under models/, only one of them is bundled.
        writeFile(File(filesDir, "models/whisper-tiny-en-int8/tiny.en-encoder.int8.onnx"), 1_000)
        writeFile(File(filesDir, "models/side-loaded/replacement.onnx"), 300)
        File(filesDir, "bundled_assets.manifest").writeText(
            "models/whisper-tiny-en-int8/tiny.en-encoder.int8.onnx\n",
        )

        val accounting = runBlocking { measureStorageAccounting(filesDir, emptyList()) }

        assertEquals("only the genuinely side-loaded file counts toward the budget", 300L, accounting.modelBytes)
        assertEquals(
            "the bundled file is reported on its own, not folded into modelBytes",
            1_000L,
            accounting.bundledBytes,
        )
        assertEquals(
            "totalBytes (the retention budget figure) must exclude bundledBytes entirely",
            300L,
            accounting.totalBytes,
        )
    }

    @Test
    @Requirement("AC-139", "FR-AST-3a")
    fun `AC_139 a listed bundled destination that does not exist on disk contributes nothing`() {
        val filesDir = Files.createTempDirectory("storage-accounting-bundled-missing-test").toFile()
        File(filesDir, "bundled_assets.manifest").writeText("models/never-actually-installed.onnx\n")

        val accounting = runBlocking { measureStorageAccounting(filesDir, emptyList()) }

        assertEquals(0L, accounting.bundledBytes)
        assertEquals(0L, accounting.modelBytes)
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

    @Test
    @Requirement("FR-STO-3", "D39")
    fun `the archive's own directory is measured separately and excluded from totalBytes`() {
        val filesDir = Files.createTempDirectory("storage-accounting-archive-test").toFile()
        writeFile(File(filesDir, "audio/SESSION01/tx1.flac"), 100)
        writeFile(File(filesDir, "archive/SESSION01/chunk-0.flac"), 900)

        val accounting = runBlocking { measureStorageAccounting(filesDir, emptyList()) }

        assertEquals("the archive's own real size", 900L, accounting.archiveBytes)
        assertEquals(
            "the archive must never be folded into the over-audio budget figure",
            100L,
            accounting.totalBytes,
        )
    }

    @Test
    @Requirement("FR-STO-3e", "D40", "AC-157")
    fun `AC_157 the over-audio budget warning is a derived fact that survives a restart, not a one-time event`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // "A fresh reader over the same store" -- two independent SharedPreferences handles onto
        // the exact prefs file `:app`'s SharedPreferencesSettingsStore persists audioBudgetGb
        // under (this module cannot import that `:app` type -- module graph -- so the real,
        // shared-file contract is exercised directly by name, the same pattern already documented
        // for RealCaptureService.Dependencies.captureConfigurationStore).
        val firstProcessPrefs = context.getSharedPreferences("org.ort.app.settings", Context.MODE_PRIVATE)
        firstProcessPrefs.edit().putInt("audio_budget_gb", 1).apply() // a real, persisted 1 GB budget

        // "Real, measured usage over that budget" -- 2 GB, expressed directly as the fact
        // measureStorageAccounting would eventually report, so this test exercises the persisted
        // *setting*'s round trip without needing to write an actual 2 GB file to disk.
        val measuredUsedBytes = 2_000_000_000L

        val budgetGb = firstProcessPrefs.getInt("audio_budget_gb", -1)
        val stateBeforeRestart = overAudioBudgetState(usedBytes = measuredUsedBytes, budgetGb = budgetGb)
        assertTrue("usage over budget must read as exceeded", stateBeforeRestart.exceeded)

        // "Restart": a brand new SharedPreferences handle -- nothing carried over from the object
        // above, only the same on-disk prefs file.
        val secondProcessPrefs = context.getSharedPreferences("org.ort.app.settings", Context.MODE_PRIVATE)
        val restartedBudgetGb = secondProcessPrefs.getInt("audio_budget_gb", -1)
        val stateAfterRestart = overAudioBudgetState(usedBytes = measuredUsedBytes, budgetGb = restartedBudgetGb)

        assertEquals(1, restartedBudgetGb) // the persisted setting survives the "restart"
        assertTrue(
            "AC-157: the warning must still read exceeded after a restart, never a one-time notice",
            stateAfterRestart.exceeded,
        )
        assertEquals(stateBeforeRestart, stateAfterRestart)
    }

    @Test
    @Requirement("FR-STO-3e", "D40")
    fun `no budget set never reads as exceeded, and is distinct from an exceeded budget`() {
        assertFalse(overAudioBudgetState(usedBytes = 999_999_999_999L, budgetGb = null).exceeded)
        assertNull(overAudioBudgetState(usedBytes = 0L, budgetGb = null).budgetBytes)
    }

    @Test
    @Requirement("FR-STO-3e", "D40")
    fun `usage at exactly the budget is not exceeded -- only strictly over it is`() {
        val state = overAudioBudgetState(usedBytes = 1_000_000_000L, budgetGb = 1)
        assertFalse(state.exceeded)
        assertEquals(1_000_000_000L, state.budgetBytes)
    }

    private fun writeFile(file: File, byteCount: Int) {
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(byteCount))
    }
}
