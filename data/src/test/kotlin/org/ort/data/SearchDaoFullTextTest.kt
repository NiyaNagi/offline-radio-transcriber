package org.ort.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TranscriptPass
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * FR-UI-3's full-text half, over the real `transcript_fts` table [OrtDatabase] builds.
 *
 * **Empirically confirmed for this build-plan P15 session**: under this project's pinned
 * Robolectric/Android-Gradle-Plugin versions, even with `robolectric.properties` set to
 * `sqliteMode=NATIVE` (the real Android SQLite amalgamation, not the legacy sqlite4java shadow),
 * creating an `fts5` virtual table fails with `no such module: fts5` — the exact message
 * [OrtDatabase.createFtsIndex] already catches narrowly and skips. This was verified directly
 * with a throwaway probe (`CREATE VIRTUAL TABLE probe_fts USING fts5(text)` against
 * `OrtDatabase.create(..., inMemory = true)`) before this test was written, per this prompt's own
 * instruction not to assume either way.
 *
 * [assumeTrue] below means this test is honestly **skipped**, not silently passed, whenever the
 * host's SQLite build lacks fts5 — it must never report green without having exercised MATCH
 * against a real index. `minSdk 26`'s bundled SQLite always ships fts5 (see [OrtDatabase]'s own
 * comment), so production devices are unaffected; this gap is test-environment-only, and is
 * reported as such rather than worked around.
 */
@RunWith(RobolectricTestRunner::class)
public class SearchDaoFullTextTest {

    private lateinit var db: OrtDatabase

    @Before
    public fun openDatabase() {
        db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
    }

    /**
     * A `sqlite_master` row for `transcript_fts` can exist even when fts5 is genuinely
     * unavailable — `CREATE VIRTUAL TABLE IF NOT EXISTS ... USING fts5(...)` can leave a schema
     * entry behind even though the module lookup that follows it fails with
     * `no such module: fts5` (confirmed empirically in this build-plan P15 session against a
     * file-backed database: a `sqlite_master` check alone reported "available" while every real
     * query against the table then threw exactly that error). Actually preparing a statement
     * against the table is the only reliable check.
     */
    private fun fts5Available(): Boolean = try {
        db.openHelper.writableDatabase.query("SELECT count(*) FROM transcript_fts").use { it.moveToFirst() }
        true
    } catch (e: android.database.sqlite.SQLiteException) {
        false
    }

    private fun transcript(id: String, transmissionId: String, text: String) = TranscriptEntity(
        id = id,
        transmissionId = transmissionId,
        pass = TranscriptPass.B,
        text = text,
        modelId = "distil-small.en",
        modelVersion = "1",
        quantization = null,
        decodeParams = null,
        noSpeechProb = null,
        confidence = 0.9,
        isCurrent = true,
        createdAt = 0L,
    )

    @Test
    @Requirement("FR-UI-3")
    public fun `full-text search returns a transmission by a word in its transcript`(): Unit = runTest {
        assumeTrue(
            "fts5 module unavailable under this Robolectric host SQLite build (confirmed: " +
                "'no such module: fts5') — see this class's doc comment. Real minSdk-26 devices " +
                "always carry fts5, but this exact behaviour cannot be exercised here.",
            fts5Available(),
        )
        db.sessionDao().insert(TestFixtures.session())
        db.transmissionDao().insert(TestFixtures.transmission("TX1", samplePosition = 1L))
        db.transmissionDao().insert(TestFixtures.transmission("TX2", samplePosition = 2L))
        db.transcriptDao().supersede(transcript("T1", "TX1", "this is whiskey seven november papa charlie"))
        db.transcriptDao().supersede(transcript("T2", "TX2", "unrelated traffic on the repeater"))

        val result = db.searchDao().search(text = "whiskey")

        assertEquals(listOf("TX1"), result.map { it.id })
    }
}
