package org.ort.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import java.io.File

/** FR-AST-8 → AC-54 — orphaned files and dangling rows are reported, and neither is touched. */
@RunWith(RobolectricTestRunner::class)
public class ReconciliationTest {

    private lateinit var db: OrtDatabase
    private lateinit var audioRoot: File

    @Before
    public fun setUp() {
        db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        audioRoot = File.createTempFile("audio-root", "").apply {
            delete()
            mkdirs()
        }
    }

    @Test
    @Requirement("AC-54")
    public fun reconciliation_reports_an_orphaned_file_and_a_dangling_row_and_deletes_neither(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("SESSION01"))

        // A row with no file on disk — dangling.
        db.transmissionDao().insert(TestFixtures.transmission("TX-DANGLING", sessionId = "SESSION01"))

        // A file with no row behind it — orphaned. Matches technical design §12.2's layout.
        val orphanFile = File(audioRoot, "SESSION01/TX-ORPHAN.flac")
        orphanFile.parentFile!!.mkdirs()
        orphanFile.writeText("not real audio, just a fixture")

        val report = reconcile(audioRoot, db.transmissionDao())

        assertEquals(listOf("SESSION01/TX-ORPHAN.flac"), report.orphanedFiles)
        assertEquals(listOf("TX-DANGLING"), report.danglingRows)

        // Deletes neither.
        assertTrue(orphanFile.exists())
        assertEquals("TX-DANGLING", db.transmissionDao().getById("TX-DANGLING")!!.id)
    }
}
