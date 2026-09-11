package org.ort.app.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.ModelId
import org.ort.app.ui.data.ModelRowStatus
import org.ort.app.ui.data.ModelRowViewState
import org.ort.app.ui.data.ModelsController
import org.ort.app.ui.data.ModelsViewState
import org.ort.app.ui.data.StagedActivation
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
        // D35/FR-AST-1: every real [ModelId] ships bundled today (the same default
        // [ModelRowViewState.bundled] itself carries) — a test wanting to exercise the pre-D35
        // Download path (a hypothetical, still-fetchable asset) passes `false` explicitly.
        bundled: Boolean = true,
    ) = ModelRowViewState(
        id,
        id.label,
        status,
        detail = detail,
        checksumKnown = checksumKnown,
        sizeBytes = sizeBytes,
        checksumPrefix = checksumPrefix,
        bundled = bundled,
    )

    @Test
    @Requirement("FR-ASR-1")
    fun `R_093 a not-installed model shows honestly and offers Install from a file, never Download since D35`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(rows = listOf(row(ModelId.VAD, ModelRowStatus.NOT_INSTALLED))),
                    onDownload = {},
                    onSideload = {},
                )
            }
        }

        // R-841 (round 1, tour finding): a bundled row reading `NOT_INSTALLED` means the
        // installer has not verified it this launch yet — never "not installed", which D35 makes
        // an operator-owed-action claim that is untrue for a bundled asset.
        composeTestRule
            .onNodeWithContentDescription("Silero VAD not installed. not yet verified on this launch")
            .assertExists()
        // D35/FR-AST-1 (checklist E2-F04): a bundled asset ships inside the artifact — there is
        // nothing to download, so this action must never appear for it, discrimination-tested
        // below (`AC_139_offersDownload_never_true_for_a_bundled_row`).
        composeTestRule.onNodeWithContentDescription("Download Silero VAD").assertDoesNotExist()
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
        // `Settings-Assets.dc.html` verbatim: "segmentation · 2 MB · bundled · verified
        // 9e2449e1" — no "sha256" word, no trailing tiers clause (unlike the Whisper family row,
        // a different formula — see `assetFactsSubLine`'s own doc comment for why).
        composeTestRule
            .onNodeWithContentDescription(
                "Silero VAD verified. segmentation · 2 MB · bundled · verified 9e2449e1",
            )
            .assertExists()
    }

    @Test
    @Requirement("FR-ASR-1")
    fun `R_093 tapping Download calls back with that row's id`() {
        var tapped: ModelId? = null
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(
                        rows = listOf(
                            // `bundled = false`: D35 refuses Download for a bundled row outright
                            // (every real ModelId today) — this proves the tap mechanism itself
                            // still works for the split-delivery case `ModelCatalogEntry.bundled`'s
                            // own doc comment names as the reason that field exists at all.
                            row(ModelId.ASR_ENCODER, ModelRowStatus.NOT_INSTALLED, bundled = false),
                        ),
                    ),
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
            "Whisper tiny.en — tokens installed, unverified. Pass B · 500 KB · bundled · " +
                "verified abc12345, not against a published value",
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
        // WP2's R-380/R-381 fix (`PrimaryButton`/`SecondaryButton`/`TextAction`'s own
        // `clearAndSetSemantics` now sets `contentDescription = text` on the button's own node and
        // clears its inner Text's semantics entirely, merged or not) — the button's label is a
        // content description now, never a `Text` node `onNodeWithText` can find. Real behaviour,
        // not a regression here.
        composeTestRule.onNodeWithContentDescription("Install").assertExists()
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
        // its text uppercased).
        composeTestRule.onNodeWithText("ACTIVE").assertDoesNotExist()
        // R-761 (register, design, confirmation sweep): the per-part actions used to nest one full
        // row — two actions each — per still-missing part (three rows, six buttons, on a clean
        // install), instead of `Settings-Assets.dc.html`'s own single-row-per-asset shape. Only the
        // *next* missing part's own actions are reachable at a time now — [ModelId.ASR_ENCODER]'s,
        // first in family order — never a second nested row for [ModelId.ASR_DECODER]/
        // [ModelId.ASR_TOKENS] while encoder is still missing; each becomes reachable in turn once
        // the part ahead of it installs.
        // D35/FR-AST-1: encoder ships bundled (this test's own `row()` default) — never Download,
        // only Install from a file, the one real replacement path for a bundled asset (FR-AST-1).
        composeTestRule.onNodeWithContentDescription("Download Whisper tiny.en — encoder").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Install Whisper tiny.en — encoder from a file").assertExists()
        composeTestRule.onNodeWithContentDescription("Install Whisper tiny.en — decoder from a file")
            .assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Install Whisper tiny.en — tokens from a file")
            .assertDoesNotExist()
        // R-093/FR-AST-1: the checksum-unknown tokens file never offers Download, grouped or not —
        // moot here (tokens is not the next-missing part), but still real: encoder's own real
        // checksum status, not a stand-in for the family's.
        composeTestRule.onNodeWithContentDescription("Download Whisper tiny.en — tokens").assertDoesNotExist()
    }

    @Test
    @Requirement("R-443")
    fun `R_443_clean_install_groups`() {
        // R-443 (register, Reviewer D): the tour's `model-missing` capture on a clean install showed
        // three loose Whisper rows instead of one grouped row, unlike a V6 pass 4 device that had
        // been used (and so already had leftover model files) before. `groupAssetRows`/`familyOf`
        // (this file's own R-140 test, `the three Whisper files render as one grouped row…`) are
        // already a pure function of [ModelId] alone, never of [ModelRowViewState.status] or any
        // on-disk fact — but that test only proved the *presentation* half. This proves the whole
        // real path a clean install actually takes: a genuinely fresh Robolectric context (no file
        // this test — or any prior one sharing this process — ever wrote to `filesDir`, the same
        // "nothing on disk" state `ModelsControllerTest`'s own
        // `FR_ASR_1 the models screen renders not installed honestly when nothing is on disk` already
        // establishes for [ModelsController.currentState] alone) fed straight into [ModelsScreen],
        // with no synthetic row list standing in for either half.
        val freshContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cleanInstallState = ModelsController.currentState(freshContext)

        composeTestRule.setContent {
            OrtTheme { ModelsScreen(state = cleanInstallState, onDownload = {}, onSideload = {}) }
        }

        // One family row for the three Whisper parts — never three loose per-file rows. A loose,
        // ungrouped row would carry its own top-level "<label> not installed. not installed" merged
        // description per part (exactly [row]'s `description` above); the grouped row instead merges
        // all three parts under the one family description below, so counting *that* shape directly
        // — rather than re-deriving it from `onNodeWithText` alone — is the precise, structural check.
        composeTestRule.onNodeWithText("Whisper tiny.en (speech to text)").assertExists()
        // R-841 (round 1, tour finding): a genuinely bundled, non-gated family reading
        // `NOT_INSTALLED` on a clean install means the installer has not verified it yet this
        // launch — never "not installed", which D35 makes an operator-owed-action claim that is
        // untrue for a bundled asset.
        composeTestRule
            .onNodeWithContentDescription(
                "Whisper tiny.en (speech to text) not installed. not yet verified on this launch · " +
                    "3 of 3 parts missing",
            )
            .assertExists()
        composeTestRule
            .onAllNodesWithContentDescription(
                "Whisper tiny.en — encoder not installed. not yet verified on this launch",
            )
            .assertCountEquals(0)

        // R-761 (register, design, confirmation sweep): this test's own premise, before this round,
        // stopped at "no *loose*, ungrouped per-file row exists" — true both before and after this
        // fix, so it passed throughout, but it never checked what the grouped row's own *nested*
        // content actually was. What a real clean install showed (`model-missing/CF04-settings-
        // assets.png`) was the header row correctly proven above, plus one full nested row **per
        // still-missing part** underneath it — three rows, two actions each, six buttons in total —
        // instead of `Settings-Assets.dc.html`'s own single-row-per-asset shape. That is the actual
        // defect this test now also proves fixed: exactly one nested part's actions reachable at a
        // time (encoder's, first in family order), not three.
        composeTestRule.onNodeWithContentDescription("Install Whisper tiny.en — encoder from a file").assertExists()
        composeTestRule.onNodeWithContentDescription("Install Whisper tiny.en — decoder from a file")
            .assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Install Whisper tiny.en — tokens from a file")
            .assertDoesNotExist()
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
        // "verified" claim. "Pass B · ... · bundled · ..." per `Settings-Assets.dc.html`'s own
        // "Pass · size · bundled · verified · tiers" formula (redrawn 2026-09-10).
        composeTestRule.onNodeWithText(
            "Pass B · 4 MB · bundled · not every part verified against a published checksum",
        ).assertExists()
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
        // WP2's R-380/R-381 fix — see this file's own `F13`/`R_448` tests' comment for what
        // changed; the button's label is a content description now, never a `Text` node.
        composeTestRule.onNodeWithContentDescription("Details").performClick()
        composeTestRule.onNodeWithText("Unable to resolve host", substring = true).assertExists()

        composeTestRule.onNodeWithContentDescription("Retry").performClick()

        assert(retried == ModelId.VAD) { "expected Retry to re-run the download for VAD, got $retried" }
    }

    @Test
    @Requirement("R-448")
    fun `R_448_asset_swap_a_staged_model_row_shows_the_staged_badge`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(
                        rows = listOf(
                            row(ModelId.VAD, ModelRowStatus.INSTALLED, sizeBytes = 1L, checksumPrefix = "aa"),
                        ),
                    ),
                    onDownload = {},
                    onSideload = {},
                    status = org.ort.app.ui.data.ModelsScreenStatus(
                        stagedActivation = StagedActivation(ModelId.VAD.name, "aa", 0L, "a session is live"),
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("staged · activates when this session ends").assertExists()
    }

    @Test
    @Requirement("R-448")
    fun `R_448_asset_swap_a_staged_lexicon_row_shows_the_staged_badge, the old lexicon still reads active`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(rows = emptyList()),
                    onDownload = {},
                    onSideload = {},
                    lexicon = org.ort.app.ui.data.LexiconAssetActions(
                        row = org.ort.app.ui.data.LexiconAssetRowViewState(
                            installed = true,
                            label = "Callsign lexicon 2026.08 · 1,104,208 records",
                        ),
                        importResult = null,
                        onInstall = {},
                        onDismissResult = {},
                    ),
                    status = org.ort.app.ui.data.ModelsScreenStatus(
                        stagedActivation = StagedActivation(
                            ModelsController.CALLSIGN_LEXICON_ASSET_ID,
                            "2026.09",
                            0L,
                            "a session is live",
                        ),
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("staged · activates when this session ends").assertExists()
        // FR-AST-4's own point: the previous version stays "usual" until the staged swap is real.
        composeTestRule.onNodeWithText("Callsign lexicon 2026.08 · 1,104,208 records").assertExists()
    }

    @Test
    @Requirement("R-448")
    fun `R_448_asset_swap_no_staged_activation_shows_no_badge_at_all`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(
                        rows = listOf(
                            row(ModelId.VAD, ModelRowStatus.INSTALLED, sizeBytes = 1L, checksumPrefix = "aa"),
                        ),
                    ),
                    onDownload = {},
                    onSideload = {},
                )
            }
        }

        composeTestRule.onNodeWithText("staged · activates when this session ends").assertDoesNotExist()
    }

    /**
     * E2-F04 (`spec/e2e-capture-modes-plan.md`, D35, FR-AST-1): [offersDownload]'s own guard —
     * discrimination-tested per constitution II: with `!row.bundled` in the real production
     * function (as written), this passes; commenting out that clause in [offersDownload] (a real
     * edit tried by hand while writing this test) makes it fail, for the right reason — a bundled,
     * not-installed row's own `offersDownload` reads `true` again. Restored before this change was
     * reported done.
     */
    @Test
    @Requirement("FR-AST-3")
    fun `AC_139 offersDownload is never true for a bundled row, discrimination-tested`() {
        val bundledNotInstalled = row(ModelId.VAD, ModelRowStatus.NOT_INSTALLED, bundled = true)
        val nonBundledNotInstalled = row(ModelId.VAD, ModelRowStatus.NOT_INSTALLED, bundled = false)

        assert(!offersDownload(bundledNotInstalled)) {
            "a bundled, not-installed row must never offer Download (D35, FR-AST-1)"
        }
        assert(offersDownload(nonBundledNotInstalled)) {
            "a non-bundled, checksum-known, not-installed row must still offer Download"
        }
    }

    @Test
    @Requirement("R-448")
    fun `R_448_asset_swap_a_staged_id_for_a_different_asset_never_leaks_onto_this_row`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(
                        rows = listOf(
                            row(ModelId.VAD, ModelRowStatus.INSTALLED, sizeBytes = 1L, checksumPrefix = "aa"),
                        ),
                    ),
                    onDownload = {},
                    onSideload = {},
                    status = org.ort.app.ui.data.ModelsScreenStatus(
                        stagedActivation = StagedActivation(ModelId.ASR_ENCODER.name, "bb", 0L, "a session is live"),
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("staged · activates when this session ends").assertDoesNotExist()
    }
}
