package org.ort.app.debug

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.LexiconImportViewState
import org.ort.app.ui.data.ModelsController
import org.ort.data.OrtDatabase
import org.ort.lexicon.import.CheckStatus
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * Register R-154 (`Fail-Lexicon.dc.html`, FR-LEX-12/FR-LEX-30/FR-AST-2): the `lexicon-corrupt`
 * debug scenario runs the **real** [ModelsController.installLexicon] against a genuinely bad
 * bundled file ([LexiconCorruptScenario]) — these tests prove that path end to end through
 * `:data`'s real (file-backed) [OrtDatabase], the same instance
 * [org.ort.app.ui.data.ReaderPolling] opens. House style follows `ScenariosTest.kt`.
 */
@RunWith(RobolectricTestRunner::class)
class LexiconCorruptScenarioTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: OrtDatabase

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
    }

    @Test
    @Requirement("R-154", "FR-LEX-12")
    fun `R_154 lexicon-corrupt is a declared scenario name`() {
        assertTrue(Scenarios.NAMES.contains("lexicon-corrupt"))
    }

    @Test
    @Requirement("R-154", "FR-LEX-12")
    fun `R_154_lexicon_corrupt_scenario_renders_the_refusal`() = runTest {
        Scenarios.load(context, "lexicon-corrupt")

        val result = LexiconCorruptScenario.lastResult
        assertTrue("expected a real LexiconImportViewState, got $result", result is LexiconImportViewState.Rejected)
        result as LexiconImportViewState.Rejected

        // "What was checked" — every check present, none silently dropped.
        assertTrue(result.checks.isNotEmpty())
        val byName = result.checks.associateBy { it.name }
        assertEquals(CheckStatus.PASSED, byName.getValue("Manifest readable").status)
        assertEquals(CheckStatus.FAILED, byName.getValue("Checksum").status)
        assertEquals(CheckStatus.FAILED, byName.getValue("Record count and shape").status)
        assertEquals(CheckStatus.NOT_REACHED, byName.getValue("Callsign grammar sample").status)
        result.checks.forEach { assertTrue("${it.name} had a blank detail", it.detail.isNotBlank()) }

        // "Still active" — the board's promise that the previous lexicon is untouched.
        assertTrue(result.reason.isNotBlank())
        assertEquals("Callsign lexicon 2026.08 · 1,104,208 records", result.stillActiveLabel)
    }

    @Test
    @Requirement("R-154", "FR-LEX-12")
    fun `FR_LEX_12_a_corrupt_file_is_rejected_and_the_previous_lexicon_stays_active`() = runTest {
        Scenarios.load(context, "lexicon-corrupt")

        val versions = db.catalogDao().versionsFor(ModelsController.CALLSIGN_LEXICON_ASSET_ID)
        assertEquals("only the seeded previous version should exist — nothing was replaced", 1, versions.size)
        assertEquals("2026.08", versions.single().version)
        assertEquals(1_104_208, versions.single().recordCount)
    }

    @Test
    @Requirement("R-154")
    fun `re-running lexicon-corrupt does not duplicate the seeded previous version`() = runTest {
        Scenarios.load(context, "lexicon-corrupt")
        Scenarios.load(context, "lexicon-corrupt")

        val versions = db.catalogDao().versionsFor(ModelsController.CALLSIGN_LEXICON_ASSET_ID)
        assertEquals(1, versions.size)
    }
}
