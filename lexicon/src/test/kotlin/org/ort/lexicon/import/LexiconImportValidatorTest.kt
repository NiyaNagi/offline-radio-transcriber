package org.ort.lexicon.import

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** FR-LEX-12 (register R-154, `Fail-Lexicon.dc.html`), FR-LEX-30, FR-AST-2. */
class LexiconImportValidatorTest {

    @TempDir
    lateinit var dir: File

    private val assetId = "callsign-lexicon"

    @Test
    fun `a well-formed file passes every check and is Accepted`() {
        val file = writeLexiconFile(dir)

        val result = LexiconImportValidator.validate(file, assetId)

        assertTrue(result is LexiconImportResult.Accepted, "expected Accepted, got $result")
        result as LexiconImportResult.Accepted
        assertEquals(VALID_CALLSIGNS.size, result.recordCount)
        assertEquals("2026.09", result.version)
        assertTrue(result.checks.all { it.status == CheckStatus.PASSED })
        assertEquals(
            listOf(
                LexiconImportValidator.CHECK_MANIFEST,
                LexiconImportValidator.CHECK_CHECKSUM,
                LexiconImportValidator.CHECK_RECORD_SHAPE,
                LexiconImportValidator.CHECK_GRAMMAR_SAMPLE,
                LexiconImportValidator.CHECK_DUPLICATE_KEYS,
            ),
            result.checks.map { it.name },
        )
    }

    @Test
    fun `FR_LEX_12_a_corrupt_file_is_rejected_and_the_previous_lexicon_stays_active`() {
        val store = FakeActiveLexiconStore(
            initial = ActiveLexiconRecord(assetId, version = "2026.08", recordCount = 1_104_208),
        )
        // A truncated download: the manifest still declares the full file's checksum and record
        // count (that is what a manifest is — a promise about the *whole* file), but only some of
        // the data actually landed on disk — exactly the board's "a partial download, most likely".
        val corrupt = writeLexiconFile(dir, truncateDataAfterLines = 2)

        val result = LexiconImportInstaller.installValidated(corrupt, assetId, store)

        assertTrue(result is LexiconImportResult.Rejected, "expected Rejected, got $result")
        result as LexiconImportResult.Rejected
        assertEquals("2026.08", result.stillActive?.version)
        assertEquals(1_104_208, result.stillActive?.recordCount)
        // The previous lexicon is untouched: activate() was never called, and the store still
        // reports the same version it did before this import attempt.
        assertTrue(store.activated.isEmpty())
        assertEquals("2026.08", store.current()?.version)
    }

    @Test
    fun `FR_LEX_12_every_check_reports_pass_or_fail_with_a_reason`() {
        val good = LexiconImportValidator.validate(writeLexiconFile(dir, name = "good.tsv"), assetId)
        for (check in good.checks) {
            assertTrue(check.detail.isNotBlank(), "${check.name} had a blank detail")
            assertTrue(
                check.status == CheckStatus.PASSED,
                "${check.name} should have run and passed against a well-formed file, was ${check.status}",
            )
        }

        val corrupt = writeLexiconFile(dir, name = "corrupt.tsv", truncateDataAfterLines = 1)
        val bad = LexiconImportValidator.validate(corrupt, assetId)
        // Every check in the result — whether it ran or was skipped — carries a non-blank reason;
        // a NOT_REACHED check's reason is the literal "not reached", never an empty string.
        for (check in bad.checks) {
            assertTrue(check.detail.isNotBlank(), "${check.name} had a blank detail")
        }
        val byName = bad.checks.associateBy { it.name }
        assertEquals(CheckStatus.PASSED, byName.getValue(LexiconImportValidator.CHECK_MANIFEST).status)
        assertEquals(CheckStatus.FAILED, byName.getValue(LexiconImportValidator.CHECK_CHECKSUM).status)
        assertEquals(CheckStatus.FAILED, byName.getValue(LexiconImportValidator.CHECK_RECORD_SHAPE).status)
        assertEquals(CheckStatus.NOT_REACHED, byName.getValue(LexiconImportValidator.CHECK_GRAMMAR_SAMPLE).status)
        assertEquals("not reached", byName.getValue(LexiconImportValidator.CHECK_GRAMMAR_SAMPLE).detail)
        assertEquals(CheckStatus.NOT_REACHED, byName.getValue(LexiconImportValidator.CHECK_DUPLICATE_KEYS).status)
    }

    @Test
    fun `an unreadable file fails Manifest readable and every later check is not reached`() {
        val missing = File(dir, "does-not-exist.tsv")

        val result = LexiconImportValidator.validate(missing, assetId)

        assertTrue(result is LexiconImportResult.Rejected)
        result as LexiconImportResult.Rejected
        val byName = result.checks.associateBy { it.name }
        assertEquals(CheckStatus.FAILED, byName.getValue(LexiconImportValidator.CHECK_MANIFEST).status)
        assertEquals(CheckStatus.NOT_REACHED, byName.getValue(LexiconImportValidator.CHECK_CHECKSUM).status)
        assertEquals(CheckStatus.NOT_REACHED, byName.getValue(LexiconImportValidator.CHECK_RECORD_SHAPE).status)
        assertEquals(CheckStatus.NOT_REACHED, byName.getValue(LexiconImportValidator.CHECK_GRAMMAR_SAMPLE).status)
        assertEquals(CheckStatus.NOT_REACHED, byName.getValue(LexiconImportValidator.CHECK_DUPLICATE_KEYS).status)
    }

    @Test
    fun `a file with no manifest header is rejected without a false record count`() {
        val file = File(dir, "no-header.tsv")
        file.writeText(VALID_CALLSIGNS.joinToString("\n") { dataRow(it) })

        val result = LexiconImportValidator.validate(file, assetId)

        assertTrue(result is LexiconImportResult.Rejected)
        result as LexiconImportResult.Rejected
        assertEquals(CheckStatus.FAILED, result.checks.first { it.name == LexiconImportValidator.CHECK_MANIFEST }.status)
    }

    @Test
    fun `a checksum mismatch alone is reported without corrupting the record count check`() {
        // Same data, wrong declared checksum — the manifest's *other* promise (record count) is
        // still checkable and still true, so it should still say so honestly.
        val file = writeLexiconFile(dir, declaredChecksum = "0".repeat(64))

        val result = LexiconImportValidator.validate(file, assetId)

        assertTrue(result is LexiconImportResult.Rejected)
        result as LexiconImportResult.Rejected
        val byName = result.checks.associateBy { it.name }
        assertEquals(CheckStatus.FAILED, byName.getValue(LexiconImportValidator.CHECK_CHECKSUM).status)
        // Both checksum and record count are independently computable from the same read, so a
        // checksum-only corruption still lets record count run and honestly pass.
        assertEquals(CheckStatus.PASSED, byName.getValue(LexiconImportValidator.CHECK_RECORD_SHAPE).status)
        assertEquals(CheckStatus.NOT_REACHED, byName.getValue(LexiconImportValidator.CHECK_GRAMMAR_SAMPLE).status)
    }

    @Test
    fun `a row with a structurally invalid callsign fails the grammar sample check`() {
        val rows = VALID_CALLSIGNS.map { dataRow(it) } + dataRow("NOTACALL9")
        val file = writeLexiconFile(dir, rows = rows)

        val result = LexiconImportValidator.validate(file, assetId)

        assertTrue(result is LexiconImportResult.Rejected)
        result as LexiconImportResult.Rejected
        val grammar = result.checks.first { it.name == LexiconImportValidator.CHECK_GRAMMAR_SAMPLE }
        assertEquals(CheckStatus.FAILED, grammar.status)
        assertTrue(grammar.detail.contains("1"))
    }

    @Test
    fun `duplicate callsigns fail the no-duplicate-keys check`() {
        val rows = VALID_CALLSIGNS.map { dataRow(it) } + dataRow("K7ABC")
        val file = writeLexiconFile(dir, rows = rows)

        val result = LexiconImportValidator.validate(file, assetId)

        assertTrue(result is LexiconImportResult.Rejected)
        result as LexiconImportResult.Rejected
        val dup = result.checks.first { it.name == LexiconImportValidator.CHECK_DUPLICATE_KEYS }
        assertEquals(CheckStatus.FAILED, dup.status)
        assertTrue(dup.detail.contains("K7ABC"))
    }

    @Test
    fun `Rejected with no previous lexicon reports stillActive as null, not omitted`() {
        val file = writeLexiconFile(dir, truncateDataAfterLines = 1)

        val result = LexiconImportValidator.validate(file, assetId, currentActive = null)

        assertTrue(result is LexiconImportResult.Rejected)
        result as LexiconImportResult.Rejected
        assertNull(result.stillActive)
    }

    @Test
    fun `installValidated activates the new lexicon only when every check passes`() {
        val store = FakeActiveLexiconStore()
        val file = writeLexiconFile(dir)

        val result = LexiconImportInstaller.installValidated(file, assetId, store)

        assertTrue(result is LexiconImportResult.Accepted)
        assertEquals(1, store.activated.size)
        assertEquals("2026.09", store.current()?.version)
        assertNotNull(store.current())
        assertFalse(store.activated.first().version == "2026.08")
    }
}
