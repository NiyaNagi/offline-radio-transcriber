package org.ort.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
 * **No longer conditional.** Before register R-204, this test [org.junit.Assume]d itself skipped
 * whenever the host SQLite build lacked the `fts5` module — true both on this project's pinned
 * Robolectric/host-JVM SQLite *and*, it turned out, on the API 34 reference emulator's platform
 * SQLite ("no such module: fts5"), which is the bug R-204 names. [OrtDatabase.create] now always
 * installs `BundledSQLiteDriver` (`androidx.sqlite:sqlite-bundled`), a SQLite build with fts5
 * compiled in, bypassing the platform/host SQLite entirely — so this test now runs unconditionally,
 * on every machine, and a regression back to "fts5 unavailable" fails it loudly instead of quietly
 * skipping it. See `FtsIndexRepairTest` for the driver-availability and repair-on-open checks R-204
 * added.
 */
@RunWith(RobolectricTestRunner::class)
public class SearchDaoFullTextTest {

    private lateinit var db: OrtDatabase

    @Before
    public fun openDatabase() {
        db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
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
    @Requirement("FR-UI-3", "FR-STO-1", "R-204")
    public fun `full-text search returns a transmission by a word in its transcript`(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session())
        db.transmissionDao().insert(TestFixtures.transmission("TX1", samplePosition = 1L))
        db.transmissionDao().insert(TestFixtures.transmission("TX2", samplePosition = 2L))
        db.transcriptDao().supersede(transcript("T1", "TX1", "this is whiskey seven november papa charlie"))
        db.transcriptDao().supersede(transcript("T2", "TX2", "unrelated traffic on the repeater"))

        val result = db.searchDao().search(text = "whiskey")

        assertEquals(listOf("TX1"), result.map { it.id })
    }
}
