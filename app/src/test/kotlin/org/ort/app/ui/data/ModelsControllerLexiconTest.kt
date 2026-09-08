package org.ort.app.ui.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.data.OrtDatabase
import org.ort.lexicon.import.CheckStatus
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Register R-154 (`Fail-Lexicon.dc.html`, FR-LEX-12/FR-LEX-30/FR-AST-2):
 * [ModelsController.installLexicon] — the Models screen's real "Install a lexicon from a file" call
 * site — against [RoomActiveLexiconStore], `:data`'s real (file-backed) implementation of
 * [org.ort.lexicon.import.ActiveLexiconStore]. Complements
 * `app/src/test/kotlin/org/ort/lexicon/import/LexiconImportValidatorTest.kt`, which covers the
 * validator itself with an in-memory fake store; these tests cover the `:app`/`:data` wiring.
 */
@RunWith(RobolectricTestRunner::class)
class ModelsControllerLexiconTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: OrtDatabase
    private lateinit var dir: File

    @Before
    fun setUp() {
        db = OrtDatabase.create(context)
        dir = Files.createTempDirectory("lexicon-controller-test").toFile()
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") {
            "%02x".format(it)
        }

    private fun writeLexiconFile(
        rows: List<String>,
        declaredChecksum: String? = null,
        declaredRecords: Int? = null,
    ): File {
        val data = rows.joinToString("\n")
        val checksum = declaredChecksum ?: sha256Hex(data)
        val records = declaredRecords ?: rows.size
        val file = File(dir, "import.tsv")
        file.writeText("# version 2026.09\n# records $records\n# sha256 $checksum\n$data\n")
        return file
    }

    @Test
    @Requirement("R-154", "FR-LEX-30")
    fun `installLexicon activates a well-formed file through the real Room store`() = runTest {
        val file = writeLexiconFile(listOf("K7ABC\tOperator\tWA", "W7NPC\tOperator\tWA"))

        val result = ModelsController.installLexicon(context, file, RoomActiveLexiconStore(context))

        assertTrue("expected Accepted, got $result", result is LexiconImportViewState.Accepted)
        val versions = db.catalogDao().versionsFor(ModelsController.CALLSIGN_LEXICON_ASSET_ID)
        assertEquals(1, versions.size)
        assertEquals("2026.09", versions.single().version)
        assertEquals(2, versions.single().recordCount)
    }

    @Test
    @Requirement("R-154", "FR-LEX-12")
    fun `FR_LEX_12_a_corrupt_file_is_rejected_and_the_previous_lexicon_stays_active`() = runTest {
        val store = RoomActiveLexiconStore(context)
        val goodFile = writeLexiconFile(listOf("K7ABC\tOperator\tWA", "W7NPC\tOperator\tWA"))
        val accepted = ModelsController.installLexicon(context, goodFile, store)
        assertTrue(accepted is LexiconImportViewState.Accepted)

        // A truncated "download": manifest still declares the original checksum/record count.
        val corruptFile = writeLexiconFile(
            listOf("K7ABC\tOperator\tWA"),
            declaredChecksum = sha256Hex(listOf("K7ABC\tOperator\tWA", "W7NPC\tOperator\tWA").joinToString("\n")),
            declaredRecords = 2,
        )

        val result = ModelsController.installLexicon(context, corruptFile, store)

        assertTrue("expected Rejected, got $result", result is LexiconImportViewState.Rejected)
        result as LexiconImportViewState.Rejected
        assertEquals("Callsign lexicon 2026.09 · 2 records", result.stillActiveLabel)

        // The store still reports only the one, original, accepted version.
        val versions = db.catalogDao().versionsFor(ModelsController.CALLSIGN_LEXICON_ASSET_ID)
        assertEquals(1, versions.size)
        assertEquals("2026.09", versions.single().version)
    }

    @Test
    @Requirement("FR-LEX-12")
    fun `FR_LEX_12_every_check_reports_pass_or_fail_with_a_reason`() = runTest {
        val file = writeLexiconFile(listOf("NOTACALL9\tOperator\tWA"))

        val result = ModelsController.installLexicon(context, file, RoomActiveLexiconStore(context))

        assertTrue(result is LexiconImportViewState.Rejected)
        result as LexiconImportViewState.Rejected
        assertTrue(result.checks.isNotEmpty())
        result.checks.forEach { check -> assertTrue("${check.name} had a blank detail", check.detail.isNotBlank()) }
        assertEquals(CheckStatus.FAILED, result.checks.first { it.name == "Callsign grammar sample" }.status)
    }

    @Test
    @Requirement("R-154")
    fun `a first-ever import that fails reports stillActiveLabel as null, not omitted`() = runTest {
        val file = writeLexiconFile(listOf("K7ABC\tOperator\tWA"), declaredRecords = 999)

        val result = ModelsController.installLexicon(context, file, RoomActiveLexiconStore(context))

        assertTrue(result is LexiconImportViewState.Rejected)
        assertNull((result as LexiconImportViewState.Rejected).stillActiveLabel)
    }
}
