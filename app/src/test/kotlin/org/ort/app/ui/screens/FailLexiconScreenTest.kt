package org.ort.app.ui.screens

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.LexiconAssetActions
import org.ort.app.ui.data.LexiconAssetRowViewState
import org.ort.app.ui.data.LexiconCheckViewRow
import org.ort.app.ui.data.LexiconImportViewState
import org.ort.app.ui.data.ModelsController
import org.ort.app.ui.data.ModelsViewState
import org.ort.app.ui.data.RoomActiveLexiconStore
import org.ort.app.ui.theme.OrtTheme
import org.ort.lexicon.import.CheckStatus
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * `ModelsScreenTest.kt` split — detekt's own `LargeClass` finding, the same fix `RowsTest.kt`'s own
 * split family already established (see that file's own doc comment) — once this round's R-490..
 * R-493 tests (register, Reviewer D, `Fail-Lexicon.dc.html`) grew that file past a reasonable size.
 * Every `Fail-Lexicon`/R-154 test moved here unchanged; `ModelsScreenTest.kt` keeps every other
 * `ModelsScreen` test (the Assets list itself, not the refused-import drill-in).
 */
@RunWith(RobolectricTestRunner::class)
class FailLexiconScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    @Requirement("R-154")
    fun `R_154_a_rejected_import_renders_every_check_and_what_stays_active`() {
        var choseAnotherFile = false
        var done = false
        val rejected = LexiconImportViewState.Rejected(
            fileName = "lexicon-2026.09.tsv.zst",
            checks = listOf(
                LexiconCheckViewRow(
                    "Manifest readable",
                    CheckStatus.PASSED,
                    "version 2026.09 · declares 1,122,410 records",
                ),
                LexiconCheckViewRow(
                    "Checksum",
                    CheckStatus.FAILED,
                    "computed sha256 e07c… · does not match",
                ),
                LexiconCheckViewRow(
                    "Record count and shape",
                    CheckStatus.FAILED,
                    "read 1,004,392 · declared 1,122,410 · file ends mid-record",
                ),
                LexiconCheckViewRow(
                    "Callsign grammar sample",
                    CheckStatus.NOT_REACHED,
                    "not reached",
                ),
            ),
            reason = "Checksum mismatched and the record count did not match the manifest — a partial " +
                "or corrupted download, most likely. Nothing was replaced.",
            stillActiveLabel = "Callsign lexicon 2026.08",
            stillActiveRecordCount = 1_104_208,
        )

        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(rows = emptyList()),
                    onDownload = {},
                    onSideload = {},
                    lexicon = LexiconAssetActions(
                        row = LexiconAssetRowViewState(installed = true, label = "x"),
                        importResult = rejected,
                        onInstall = { choseAnotherFile = true },
                        onDismissResult = { done = true },
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("Import refused").assertExists()
        composeTestRule.onNodeWithText("lexicon-2026.09.tsv.zst", substring = true).assertExists()

        // Every check, in order, with its real status word represented (not colour-only) via detail.
        composeTestRule.onNodeWithText("Manifest readable").assertExists()
        composeTestRule.onNodeWithText("version 2026.09 · declares 1,122,410 records").assertExists()
        composeTestRule.onNodeWithText("Checksum").assertExists()
        composeTestRule.onNodeWithText("computed sha256 e07c… · does not match").assertExists()
        composeTestRule.onNodeWithText("Record count and shape").assertExists()
        composeTestRule.onNodeWithText("Callsign grammar sample").assertExists()
        composeTestRule.onNodeWithText("not reached").assertExists()

        // The reason and what stays active (R-491/R-492: two real lines, name then count/verified/
        // in-use, not one collapsed line).
        composeTestRule.onNodeWithText(rejected.reason, substring = true).assertExists()
        composeTestRule.onNodeWithText("Callsign lexicon 2026.08").assertExists()
        composeTestRule.onNodeWithText("1,104,208 records · verified · in use by the running session").assertExists()

        // WP2's R-380/R-381 fix — `PrimaryButton`/`SecondaryButton`'s label is a content
        // description now, never a `Text` node `hasText` can match.
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Done"))
        composeTestRule.onNodeWithContentDescription("Choose another file").performClick()
        assert(choseAnotherFile) { "expected Choose another file to call onInstall" }

        composeTestRule.onNodeWithContentDescription("Done").performClick()
        assert(done) { "expected Done to call onDismissResult" }
    }

    @Test
    @Requirement("R-154")
    fun `R_154 an accepted lexicon import folds back into the assets list, no full-screen takeover`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(rows = emptyList()),
                    onDownload = {},
                    onSideload = {},
                    lexicon = LexiconAssetActions(
                        row = LexiconAssetRowViewState(
                            installed = true,
                            label = "Callsign lexicon 2026.09 · 1,122,410 records",
                        ),
                        importResult = LexiconImportViewState.Accepted(
                            fileName = "lexicon-2026.09.tsv.zst",
                            checks = emptyList(),
                            version = "2026.09",
                            recordCount = 1_122_410,
                        ),
                        onInstall = {},
                        onDismissResult = {},
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("Import refused").assertDoesNotExist()
        composeTestRule.onNodeWithText("Models and lexicon").assertExists()
        composeTestRule.onNodeWithText("Callsign lexicon 2026.09 · 1,122,410 records").assertExists()
    }

    private fun rejectedWithStillActive(
        reason: String = "Checksum mismatched and the record count did not match the manifest — a partial " +
            "or corrupted download, most likely. Nothing was replaced.",
        stillActiveLabel: String? = "Callsign lexicon 2026.08",
        stillActiveRecordCount: Int? = 1_104_208,
    ) = LexiconImportViewState.Rejected(
        fileName = "lexicon-2026.09.tsv.zst",
        checks = listOf(
            LexiconCheckViewRow(
                "Manifest readable",
                CheckStatus.PASSED,
                "version 2026.09 · declares 1,122,410 records",
            ),
            LexiconCheckViewRow(
                "Checksum",
                CheckStatus.FAILED,
                "computed sha256 e07c… · does not match",
            ),
        ),
        reason = reason,
        stillActiveLabel = stillActiveLabel,
        stillActiveRecordCount = stillActiveRecordCount,
    )

    private fun setRejectedContent(rejected: LexiconImportViewState.Rejected) {
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(rows = emptyList()),
                    onDownload = {},
                    onSideload = {},
                    lexicon = LexiconAssetActions(
                        row = LexiconAssetRowViewState(installed = true, label = "x"),
                        importResult = rejected,
                        onInstall = {},
                        onDismissResult = {},
                    ),
                )
            }
        }
    }

    @Test
    @Requirement("R-490")
    fun `R_490 the real validator's five checks fold into the board's own four named rows`() = runTest {
        // FR-AST-4/R-154's own real path: the fold this test proves lives in
        // ModelsController.installLexicon -> toViewState -> foldChecksToBoardRows, exercised here
        // through the real, un-mocked validator against a genuinely corrupt file — the same
        // real-pipeline discipline `ModelsControllerLexiconTest`/`LexiconCorruptScenarioTest`
        // already use. A checksum-mismatched file exercises Manifest (passed), Checksum (failed),
        // Record count (folds the real shape+duplicate-key checks), Prefix table consistency
        // (not reached) — the board's exact four names, never five.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = Files.createTempDirectory("r490-fold-test").toFile()
        val goodRows = listOf("K7ABC\tOperator\tWA", "W7NPC\tOperator\tWA")
        val data = goodRows.joinToString("\n")
        val realChecksum = MessageDigest.getInstance("SHA-256")
            .digest(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val corruptFile = File(dir, "import.tsv")
        // Declares the real checksum but only one data row — a genuine checksum+shape mismatch.
        corruptFile.writeText("# version 2026.09\n# records 2\n# sha256 $realChecksum\n${goodRows[0]}\n")

        val result = ModelsController.installLexicon(context, corruptFile, RoomActiveLexiconStore(context))

        assertTrue(result is LexiconImportViewState.Rejected)
        result as LexiconImportViewState.Rejected
        assertEquals(4, result.checks.size)
        val names = result.checks.map { it.name }
        assertEquals(
            listOf("Manifest readable", "Checksum", "Record count", "Prefix table consistency"),
            names,
        )
    }

    @Test
    @Requirement("R-491")
    fun `R_491 the banner names the real still-active lexicon and the closing paragraph names the real deferral`() {
        setRejectedContent(rejectedWithStillActive())

        composeTestRule.onNodeWithText(
            "Callsign lexicon 2026.08 is still active and capture never noticed.",
            substring = true,
        ).assertExists()
        composeTestRule.onNodeWithText(
            "An import only ever replaces the current lexicon after every check passes, and even then " +
                "only at the next session.",
            substring = true,
        ).assertExists()
    }

    @Test
    @Requirement("R-491")
    fun `R_491 a first-ever import has no still-active lexicon to name, the banner never fabricates one`() {
        setRejectedContent(rejectedWithStillActive(stillActiveLabel = null, stillActiveRecordCount = null))

        composeTestRule.onNodeWithText("is still active and capture never noticed", substring = true)
            .assertDoesNotExist()
    }

    @Test
    @Requirement("R-492")
    fun `R_492 the still-active row shows a real two-line fact, name then count, verified, in use`() {
        setRejectedContent(rejectedWithStillActive())

        composeTestRule.onNodeWithText("Callsign lexicon 2026.08").assertExists()
        composeTestRule.onNodeWithText("1,104,208 records · verified · in use by the running session")
            .assertExists()
    }

    @Test
    @Requirement("R-493")
    fun `R_493 the back header reads the real parent, Models and lexicon, never Settings`() {
        setRejectedContent(rejectedWithStillActive())

        composeTestRule.onNodeWithText("Models and lexicon", substring = true).assertExists()
    }
}
