package org.ort.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
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
import org.robolectric.annotation.GraphicsMode

/**
 * R-093 (`Settings-Assets.dc.html`, guide §6.7): the rebuilt Models/lexicon presentation — one row
 * per asset with a verified/not-installed marker, an `active` tag, a size · checksum-prefix
 * sub-line, and no full-sentence checksum-unknown explanation baked into the row label (guide §9).
 * [org.ort.app.ui.data.ModelsController]'s own logic and tests (`ModelsControllerTest.kt`) are
 * untouched by this change — this file replaces only the presentation test that asserted on the
 * pre-R-093 M3-button, free-text-paragraph rendering this package's `ModelsScreen.kt` no longer
 * produces.
 */
// This round's own R-863/R-874/R-970 cases pushed this file over detekt's LargeClass threshold —
// kept as one class deliberately, the same call DigestPollingTest.kt/FailureScreensTest.kt already
// make (that file's own doc comment: every real screen fact this package's tests own lives here).
@Suppress("LargeClass")
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
        // `Settings-Assets.dc.html` verbatim shape: "segmentation · <size> · bundled · verified
        // 9e2449e1" — no "sha256" word, no trailing tiers clause (unlike the Whisper family row,
        // a different formula — see `assetFactsSubLine`'s own doc comment for why). R-865
        // (Validator V9, device): "644 KB" is the catalogue's own real declared VAD size
        // (`bundled-assets.json`'s `643854` bytes), the row's primary figure now, never this
        // fixture's own on-disk length alone (`2_100_000L`, deliberately different here to prove
        // the two are independent) — that real length still renders, honestly, as its own
        // secondary "on disk" clause.
        composeTestRule
            .onNodeWithContentDescription(
                "Silero VAD verified. segmentation · 644 KB · on disk: 2 MB · bundled · verified 9e2449e1",
            )
            .assertExists()
    }

    // --- R-865 (Validator V9, device) — a verified-but-zero-byte row is a placeholder, never a
    // legitimate 0 KB verified asset ---------------------------------------------------------

    @Test
    @Requirement("FR-AST-3a")
    fun `R_865 a verified row with 0 real bytes reads as an honest placeholder, never a fabricated 0 KB asset`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(
                        rows = listOf(
                            row(ModelId.VAD, ModelRowStatus.INSTALLED, sizeBytes = 0L, checksumPrefix = "9e2449e1"),
                        ),
                    ),
                    onDownload = {},
                    onSideload = {},
                )
            }
        }

        // The real, declared VAD size still renders (never "size unknown") — only the on-disk/
        // verified claim is withheld, since 0 real bytes can never back a genuine checksum.
        composeTestRule
            .onNodeWithContentDescription(
                "Silero VAD verified. segmentation · 644 KB · bundled · placeholder — no bytes",
            )
            .assertExists()
        composeTestRule.onNodeWithContentDescription("Silero VAD verified. segmentation · 0 KB", substring = true)
            .assertDoesNotExist()
        composeTestRule.onNodeWithText("verified 9e2449e1", substring = true).assertDoesNotExist()
    }

    @Test
    @Requirement("FR-AST-3a")
    fun `R_865 a grouped family with even one placeholder part is honestly flagged, not silently averaged away`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(
                        rows = listOf(
                            row(ModelId.ASR_ENCODER, ModelRowStatus.INSTALLED, sizeBytes = 0L, checksumPrefix = "aa"),
                            row(
                                ModelId.ASR_DECODER,
                                ModelRowStatus.INSTALLED,
                                sizeBytes = 1_000_000L,
                                checksumPrefix = "bb",
                            ),
                            row(
                                ModelId.ASR_TOKENS,
                                ModelRowStatus.INSTALLED,
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

        composeTestRule.onNodeWithText("Pass B · 104 MB · bundled · placeholder — no bytes").assertExists()
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

        // R-865: "836 KB" is the catalogue's own real declared tokens-file size
        // (`bundled-assets.json`'s `835554` bytes) — the row's primary figure now; the fixture's
        // own on-disk length (`500_000L`, deliberately different) still renders, honestly, as its
        // own secondary "on disk" clause.
        composeTestRule.onNodeWithContentDescription(
            "Whisper tiny.en — tokens installed, unverified. Pass B · 836 KB · on disk: 500 KB · bundled · " +
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

    /** R-863 (register, design, validator V9): at font scale 2.0, "Install from a file" (CF04)
     * used to wrap one word per line down the right edge because the family row's own long leading
     * label ("Whisper tiny.en — encoder") took the row's width with neither side weighted — R-805's
     * exact defect class, fixed once and centrally in `TextAction` itself now (`Controls.kt`). This
     * drives the real production path end to end (`ModelsController.currentState` on a genuinely
     * clean context, the same one `R_443_clean_install_groups` above proves reaches this exact
     * nested row) — never a hand-built row list standing in for it. `GraphicsMode.NATIVE`: the
     * default graphics mode does not reliably reproduce real glyph-wrap measurement (this package's
     * own established discipline, `RigBluetoothScreenTest`'s `R_805` case). */
    @Test
    @Requirement("R-863")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_863 CF04 Install from a file keeps its intrinsic single-line width at font scale 2_0`() {
        val freshContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cleanInstallState = ModelsController.currentState(freshContext)

        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme { ModelsScreen(state = cleanInstallState, onDownload = {}, onSideload = {}) }
            }
        }

        val size = composeTestRule
            .onNodeWithContentDescription("Install Whisper tiny.en — encoder from a file")
            .fetchSemanticsNode()
            .size
        assert(size.width > size.height) {
            "expected 'Install from a file' wider than tall (single line), was ${size.width} x ${size.height}"
        }
    }

    /** R-874 (register, spec, validator V10): R-863's own fix (`TextAction`'s then-unbounded
     * measurement) held the case above at whatever width this test's default window offers, but
     * traded per-word wrap for silent clipping past a *real* 390dp screen's own edge — the same
     * regression `DigestScreensTest`'s own `R_874` case proves for DG05. This drives the identical
     * real production path (`ModelsController.currentState`) inside a real-width `Box`, proving the
     * fix here too: the leading part label now yields (`Modifier.weight(1f, fill = false)` on
     * `Row.fillMaxWidth()`), so "Install from a file" wraps within the screen's own real width
     * rather than past it. */
    @Test
    @Requirement("R-874")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_874 CF04 Install from a file wraps within a real 390dp screen at font scale 2_0, never past its edge`() {
        val freshContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cleanInstallState = ModelsController.currentState(freshContext)

        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme {
                    Box(modifier = Modifier.width(390.dp)) {
                        ModelsScreen(state = cleanInstallState, onDownload = {}, onSideload = {})
                    }
                }
            }
        }

        val actionBounds = composeTestRule
            .onNodeWithContentDescription("Install Whisper tiny.en — encoder from a file")
            .getUnclippedBoundsInRoot()
        assertTrue(
            "expected 'Install from a file' own right edge (${actionBounds.right}) to stay within the " +
                "real 390dp screen's own right edge at font scale 2.0, never clipped past it",
            actionBounds.right <= 390.dp,
        )
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
        // R-865 (Validator V9, device): the primary size is now the sum of every part's own
        // catalogue-declared size (12,937,772 + 89,853,865 + 835,554 = 103,627,191 bytes, rounds
        // to 104 MB) — real always, never a sum of on-disk lengths a test/dev fixture's own
        // placeholder shortcut can zero out. The real on-disk sum (1.0 + 2.0 + 0.5 = 3.5 MB,
        // rounds to 4 MB, this fixture's own deliberately-different numbers) still renders,
        // honestly, as its own secondary "on disk" clause — never a per-part number standing in
        // for the whole family, and honestly "not every part verified" since the tokens file is
        // trust-on-first-use, never a false blanket "verified" claim.
        composeTestRule.onNodeWithText(
            "Pass B · 104 MB · on disk: 4 MB · bundled · not every part verified against a published checksum",
        ).assertExists()
        // No loose per-part action remains once nothing is missing.
        composeTestRule.onNodeWithContentDescription(
            "Install Whisper tiny.en — tokens from a file",
        ).assertDoesNotExist()
    }

    /** R-970 (register, spec, reviewer A3, run 4a): the "ACTIVE" badge beside the wrapped Whisper
     * family title stacked one character per line at the right edge of a real 390dp screen at font
     * scale 2.0 (`assets-bundled/CF04-settings-assets@2x.png`) — the same defect class R-874
     * established for `TextAction`: the leading title, unweighted in a `Row` with no
     * `fillMaxWidth()`, took the row's width first, squeezing the trailing `Badge` to nothing. The
     * fix gives the title `Modifier.weight(1f, fill = false)` on `Row.fillMaxWidth()` so the badge
     * is measured first, at its own real width, and stays whole and inside the row. */
    @Test
    @Requirement("R-970")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_970 the ACTIVE badge stays whole and inside the row beside a wrapped Whisper title at 2_0`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme {
                    Box(modifier = Modifier.width(390.dp)) {
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
            }
        }

        val badgeBounds = composeTestRule.onNodeWithText("ACTIVE", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(
            "expected the ACTIVE badge own right edge (${badgeBounds.right}) to stay within the real " +
                "390dp screen's own right edge at font scale 2.0, never clipped past it",
            badgeBounds.right <= 390.dp,
        )
        val badgeSize = composeTestRule.onNodeWithText("ACTIVE", useUnmergedTree = true).fetchSemanticsNode().size
        assert(badgeSize.width > badgeSize.height) {
            "expected the ACTIVE badge wider than tall (whole word, single line), was " +
                "${badgeSize.width} x ${badgeSize.height}"
        }
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

    /**
     * Register R-933 (Reviewer D2, run 3, design — the R-826 shape): under a live session the
     * Lexicon row's own "not installed" sub-line and "Install a lexicon from a file" action sat
     * behind the live bar with no way to scroll them clear. Reproduced the same structural way this
     * codebase's own R-613/R-826 precedents do: a real sibling occupying the live bar's own height
     * below a `weight(1f)` box holding this screen, then a real maximum scroll, then one node's
     * bottom checked against the sibling's own top.
     */
    @Test
    @Requirement("FR-AST-3")
    fun `R_933 scrolled to the end the footer clears a live bar pinned below this screen`() {
        composeTestRule.setContent {
            OrtTheme {
                Column(modifier = Modifier.width(390.dp).height(500.dp)) {
                    Box(modifier = Modifier.weight(1f)) {
                        ModelsScreen(
                            state = ModelsViewState(rows = listOf(row(ModelId.VAD, ModelRowStatus.NOT_INSTALLED))),
                            onDownload = {},
                            onSideload = {},
                        )
                    }
                    Box(modifier = Modifier.height(44.dp).testTag("fake-live-bar"))
                }
            }
        }

        val scrollable = composeTestRule.onNode(hasScrollAction())
        scrollable.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, Float.MAX_VALUE) }
        composeTestRule.waitForIdle()

        val footerBottom = composeTestRule
            .onNodeWithText("A corrupt file is refused and the old one stays", substring = true)
            .getUnclippedBoundsInRoot()
            .bottom
        val liveBarTop = composeTestRule.onNodeWithTag("fake-live-bar").getUnclippedBoundsInRoot().top
        // R-933's own fix adds 44dp; this screen's closing text already carried its own pre-existing
        // `OrtSpacing.lg` (20dp) trailing padding regardless of this fix — the threshold is set
        // meaningfully above that pre-existing baseline (never a bare `> 0`, which the existing 20dp
        // alone could already satisfy) so this genuinely discriminates the *new* clearance, not the
        // screen's own unrelated existing spacing.
        val minimumExpectedGap = 40.dp
        assertTrue(
            "expected the closing footer's own bottom ($footerBottom) to clear the live bar's own top " +
                "($liveBarTop) by at least $minimumExpectedGap after a real max scroll; got a gap of " +
                "${liveBarTop - footerBottom}",
            liveBarTop - footerBottom >= minimumExpectedGap,
        )
    }
}
