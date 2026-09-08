package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.ModelId
import org.ort.app.ui.data.ModelRowStatus
import org.ort.app.ui.data.ModelRowViewState
import org.ort.app.ui.data.ModelsViewState
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * R-093 (`Settings-Assets.dc.html`, guide §6.7): the rebuilt Models/lexicon presentation — one row
 * per asset with a verified/not-installed marker, an `active` tag, a size · checksum-prefix
 * sub-line, and no full-sentence checksum-unknown explanation baked into the row label (guide §9).
 * [org.ort.app.ui.data.ModelsController]'s own logic and tests (`ModelsControllerTest.kt`) are
 * untouched by this change — this file replaces only the presentation test that asserted on the
 * pre-R-093 M3-button, free-text-paragraph rendering this package's `ModelsScreen.kt` no longer
 * produces.
 */
@RunWith(RobolectricTestRunner::class)
class ModelsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun row(
        id: ModelId,
        status: ModelRowStatus,
        checksumKnown: Boolean = true,
        detail: String? = null,
        sizeBytes: Long? = null,
        checksumPrefix: String? = null,
    ) = ModelRowViewState(
        id,
        id.label,
        status,
        detail = detail,
        checksumKnown = checksumKnown,
        sizeBytes = sizeBytes,
        checksumPrefix = checksumPrefix,
    )

    @Test
    @Requirement("FR-ASR-1")
    fun `R_093 a not-installed model shows honestly and offers Download and Install from a file`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(rows = listOf(row(ModelId.VAD, ModelRowStatus.NOT_INSTALLED))),
                    onDownload = {},
                    onSideload = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Silero VAD not installed. not installed").assertExists()
        composeTestRule.onNodeWithContentDescription("Download Silero VAD").assertExists()
        composeTestRule.onNodeWithContentDescription("Install Silero VAD from a file").assertExists()
    }

    @Test
    @Requirement("FR-ASR-1")
    fun `R_093 an installed model shows the verified marker and the active tag, not a sentence`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(
                        rows = listOf(
                            row(
                                ModelId.VAD,
                                ModelRowStatus.INSTALLED,
                                sizeBytes = 2_100_000L,
                                checksumPrefix = "9e2449e1",
                            ),
                        ),
                    ),
                    onDownload = {},
                    onSideload = {},
                )
            }
        }

        composeTestRule.onNodeWithText("ACTIVE").assertExists()
        composeTestRule
            .onNodeWithContentDescription("Silero VAD verified. 2 MB · sha256 9e2449e1…")
            .assertExists()
    }

    @Test
    @Requirement("FR-ASR-1")
    fun `R_093 tapping Download calls back with that row's id`() {
        var tapped: ModelId? = null
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(rows = listOf(row(ModelId.ASR_ENCODER, ModelRowStatus.NOT_INSTALLED))),
                    onDownload = { tapped = it },
                    onSideload = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Download Whisper tiny.en — encoder").performClick()

        assert(tapped == ModelId.ASR_ENCODER) { "expected a download tap for ASR_ENCODER, got $tapped" }
    }

    @Test
    @Requirement("FR-ASR-1")
    fun `R_093 tapping Install from a file calls back with that row's id`() {
        var tapped: ModelId? = null
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(rows = listOf(row(ModelId.VAD, ModelRowStatus.NOT_INSTALLED))),
                    onDownload = {},
                    onSideload = { tapped = it },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Install Silero VAD from a file").performClick()

        assert(tapped == ModelId.VAD) { "expected a sideload tap for VAD, got $tapped" }
    }

    @Test
    @Requirement("FR-AST-1")
    fun `R_093 an unknown-checksum row never offers Download and states sideload-only honestly`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(
                        rows = listOf(
                            row(
                                ModelId.ASR_TOKENS,
                                ModelRowStatus.NOT_INSTALLED,
                                checksumKnown = false,
                                detail = "no sha256 is published for this file",
                            ),
                        ),
                    ),
                    onDownload = {},
                    onSideload = {},
                )
            }
        }

        val expectedDescription = "Whisper tiny.en — tokens not installed. not installed · sideload only, " +
            "no sha256 is published for this file"
        composeTestRule
            .onNodeWithContentDescription(expectedDescription)
            .assertExists()
        composeTestRule.onNodeWithContentDescription("Download Whisper tiny.en — tokens").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Install Whisper tiny.en — tokens from a file").assertExists()
    }

    @Test
    @Requirement("FR-AST-1")
    fun `R_093 an unverified install says not verified, never claiming verified`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(
                        rows = listOf(
                            row(
                                ModelId.ASR_TOKENS,
                                ModelRowStatus.INSTALLED_UNVERIFIED,
                                checksumKnown = false,
                                sizeBytes = 500_000L,
                                checksumPrefix = "abc12345",
                            ),
                        ),
                    ),
                    onDownload = {},
                    onSideload = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(
            "Whisper tiny.en — tokens installed, unverified. 500 KB · sha256 abc12345… · " +
                "not verified against a published value",
        ).assertExists()
    }

    @Test
    @Requirement("F13")
    fun `F13 no encoder or decoder installed shows the fallback-tier failed state with an Install action`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(
                        rows = listOf(
                            row(ModelId.ASR_ENCODER, ModelRowStatus.NOT_INSTALLED),
                            row(ModelId.ASR_DECODER, ModelRowStatus.NOT_INSTALLED),
                        ),
                    ),
                    onDownload = {},
                    onSideload = {},
                )
            }
        }

        composeTestRule.onNodeWithText("No transcription model installed").assertExists()
        composeTestRule.onNodeWithText("Install").assertExists()
    }

    @Test
    @Requirement("F13")
    fun `F13 an installed encoder and decoder show no fallback-tier failed state`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(
                        rows = listOf(
                            row(ModelId.ASR_ENCODER, ModelRowStatus.INSTALLED, sizeBytes = 1L, checksumPrefix = "aa"),
                            row(ModelId.ASR_DECODER, ModelRowStatus.INSTALLED, sizeBytes = 1L, checksumPrefix = "aa"),
                        ),
                    ),
                    onDownload = {},
                    onSideload = {},
                )
            }
        }

        composeTestRule.onNodeWithText("No transcription model installed").assertDoesNotExist()
    }

    @Test
    @Requirement("FR-ASR-1")
    fun `R_093 the last action message and requeue message are both shown`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(
                        rows = listOf(
                            row(ModelId.VAD, ModelRowStatus.INSTALLED, sizeBytes = 1L, checksumPrefix = "aa"),
                        ),
                        requeuedMessage = "Requeued 3 previously failed transmission(s).",
                    ),
                    status = org.ort.app.ui.data.ModelsScreenStatus(
                        lastMessage = "Silero VAD: installed, checksum verified.",
                    ),
                    onDownload = {},
                    onSideload = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Requeued 3 previously failed transmission(s).").assertExists()
        composeTestRule.onNodeWithText("Silero VAD: installed, checksum verified.").assertExists()
    }

    @Test
    @Requirement("R-140")
    fun `R_140 the three Whisper files render as one grouped row, not three loose assets, when none is installed`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(
                        rows = listOf(
                            row(ModelId.ASR_ENCODER, ModelRowStatus.NOT_INSTALLED),
                            row(ModelId.ASR_DECODER, ModelRowStatus.NOT_INSTALLED),
                            row(ModelId.ASR_TOKENS, ModelRowStatus.NOT_INSTALLED, checksumKnown = false),
                            row(ModelId.VAD, ModelRowStatus.NOT_INSTALLED),
                        ),
                    ),
                    onDownload = {},
                    onSideload = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Whisper tiny.en (speech to text)").assertExists()
        composeTestRule.onNodeWithText("Silero VAD (voice activity)").assertExists()
        // Never installed as a group — no `active` tag ([org.ort.app.ui.components.Badge] renders
        // its text uppercased), and the per-part actions this screen could always do stay
        // reachable, just nested under the one family row rather than three loose ones.
        composeTestRule.onNodeWithText("ACTIVE").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Download Whisper tiny.en — encoder").assertExists()
        composeTestRule.onNodeWithContentDescription("Install Whisper tiny.en — tokens from a file").assertExists()
        // R-093/FR-AST-1: the checksum-unknown tokens file never offers Download, grouped or not.
        composeTestRule.onNodeWithContentDescription("Download Whisper tiny.en — tokens").assertDoesNotExist()
    }

    @Test
    @Requirement("R-140")
    fun `R_140 the Whisper family row reads active with one aggregate size once every part is installed`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(
                        rows = listOf(
                            row(
                                ModelId.ASR_ENCODER,
                                ModelRowStatus.INSTALLED,
                                sizeBytes = 1_000_000L,
                                checksumPrefix = "aa",
                            ),
                            row(
                                ModelId.ASR_DECODER,
                                ModelRowStatus.INSTALLED,
                                sizeBytes = 2_000_000L,
                                checksumPrefix = "bb",
                            ),
                            row(
                                ModelId.ASR_TOKENS,
                                ModelRowStatus.INSTALLED_UNVERIFIED,
                                checksumKnown = false,
                                sizeBytes = 500_000L,
                                checksumPrefix = "cc",
                            ),
                        ),
                    ),
                    onDownload = {},
                    onSideload = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Whisper tiny.en (speech to text)").assertExists()
        composeTestRule.onNodeWithText("ACTIVE").assertExists()
        // Aggregate size is the sum of every part's real bytes (1.0 + 2.0 + 0.5 = 3.5 MB, rounds to
        // 4 MB) — never a per-part number standing in for the whole family, and honestly "not
        // every part verified" since the tokens file is trust-on-first-use, never a false blanket
        // "verified" claim.
        composeTestRule.onNodeWithText("4 MB · not every part verified against a published checksum").assertExists()
        // No loose per-part action remains once nothing is missing.
        composeTestRule.onNodeWithContentDescription(
            "Install Whisper tiny.en — tokens from a file",
        ).assertDoesNotExist()
    }

    @Test
    @Requirement("R-140")
    fun `R_140 a failed download renders the amber FailedState with the real reason and a Retry`() {
        var retried: ModelId? = null
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(rows = listOf(row(ModelId.VAD, ModelRowStatus.NOT_INSTALLED))),
                    status = org.ort.app.ui.data.ModelsScreenStatus(
                        downloadFailure = org.ort.app.ui.data.ModelDownloadFailureViewState(
                            id = ModelId.VAD,
                            reason = "Unable to resolve host",
                        ),
                    ),
                    onDownload = { retried = it },
                    onSideload = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Silero VAD could not be downloaded").assertExists()
        // R-140 (round 7, register): the raw cause is operator-language-free and no longer sits in
        // the always-visible body — it is real, but behind its own "Details" disclosure.
        composeTestRule.onNodeWithText(
            "The download did not complete. Check the connection and retry.",
            substring = true,
        )
            .assertExists()
        composeTestRule.onNodeWithText("Unable to resolve host", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Details").performClick()
        composeTestRule.onNodeWithText("Unable to resolve host", substring = true).assertExists()

        composeTestRule.onNodeWithText("Retry").performClick()

        assert(retried == ModelId.VAD) { "expected Retry to re-run the download for VAD, got $retried" }
    }

    @Test
    @Requirement("R-154")
    fun `R_154_a_rejected_import_renders_every_check_and_what_stays_active`() {
        var choseAnotherFile = false
        var done = false
        val rejected = org.ort.app.ui.data.LexiconImportViewState.Rejected(
            fileName = "lexicon-2026.09.tsv.zst",
            checks = listOf(
                org.ort.app.ui.data.LexiconCheckViewRow(
                    "Manifest readable",
                    org.ort.lexicon.import.CheckStatus.PASSED,
                    "version 2026.09 · declares 1,122,410 records",
                ),
                org.ort.app.ui.data.LexiconCheckViewRow(
                    "Checksum",
                    org.ort.lexicon.import.CheckStatus.FAILED,
                    "computed sha256 e07c… · does not match",
                ),
                org.ort.app.ui.data.LexiconCheckViewRow(
                    "Record count and shape",
                    org.ort.lexicon.import.CheckStatus.FAILED,
                    "read 1,004,392 · declared 1,122,410 · file ends mid-record",
                ),
                org.ort.app.ui.data.LexiconCheckViewRow(
                    "Callsign grammar sample",
                    org.ort.lexicon.import.CheckStatus.NOT_REACHED,
                    "not reached",
                ),
            ),
            reason = "Checksum mismatched and the record count did not match the manifest — a partial " +
                "or corrupted download, most likely. Nothing was replaced.",
            stillActiveLabel = "Callsign lexicon 2026.08 · 1,104,208 records",
        )

        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(rows = emptyList()),
                    onDownload = {},
                    onSideload = {},
                    lexicon = org.ort.app.ui.data.LexiconAssetActions(
                        row = org.ort.app.ui.data.LexiconAssetRowViewState(installed = true, label = "x"),
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

        // The reason and what stays active.
        composeTestRule.onNodeWithText(rejected.reason, substring = true).assertExists()
        composeTestRule.onNodeWithText("Callsign lexicon 2026.08 · 1,104,208 records").assertExists()

        composeTestRule.onNode(androidx.compose.ui.test.hasScrollAction())
            .performScrollToNode(androidx.compose.ui.test.hasText("Done"))
        composeTestRule.onNodeWithText("Choose another file").performClick()
        assert(choseAnotherFile) { "expected Choose another file to call onInstall" }

        composeTestRule.onNodeWithText("Done").performClick()
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
                    lexicon = org.ort.app.ui.data.LexiconAssetActions(
                        row = org.ort.app.ui.data.LexiconAssetRowViewState(
                            installed = true,
                            label = "Callsign lexicon 2026.09 · 1,122,410 records",
                        ),
                        importResult = org.ort.app.ui.data.LexiconImportViewState.Accepted(
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
}
