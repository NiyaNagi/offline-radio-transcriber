package org.ort.app.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * CF04 (`Settings-Assets.dc.html`, redrawn 2026-09-10, D35/D36, FR-DIG-3b, FR-AST-3a, AC-139,
 * `spec/e2e-capture-modes-plan.md` E2-F04/E2-F05/E2-F07) — the Prose-digest and Space sections.
 * Split out of `ModelsScreenTest.kt` purely to keep that class under detekt's `LargeClass` limit,
 * the same reason `ModelsScreen.kt`'s own composables were split into smaller functions.
 */
@RunWith(RobolectricTestRunner::class)
class ModelsScreenProseDigestTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun row(
        id: ModelId,
        status: ModelRowStatus,
        checksumKnown: Boolean = true,
        sizeBytes: Long? = null,
        checksumPrefix: String? = null,
        bundled: Boolean = true,
    ) = ModelRowViewState(
        id,
        id.label,
        status,
        detail = null,
        checksumKnown = checksumKnown,
        sizeBytes = sizeBytes,
        checksumPrefix = checksumPrefix,
        bundled = bundled,
    )

    private fun gemmaRow(status: ModelRowStatus, tierEligible: Boolean = true) = ModelRowViewState(
        ModelId.LLM_GEMMA3_1B,
        ModelId.LLM_GEMMA3_1B.label,
        status,
        detail = null,
        sizeBytes = if (status != ModelRowStatus.NOT_INSTALLED) 554_661_243L else null,
        checksumPrefix = if (status != ModelRowStatus.NOT_INSTALLED) "e3d981c0" else null,
        tierEligible = tierEligible,
    )

    @Test
    @Requirement("FR-AST-3")
    fun `E2_F04 the Gemma row never shows an active tag, even when installed and verified`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(rows = listOf(gemmaRow(ModelRowStatus.INSTALLED))),
                    onDownload = {},
                    onSideload = {},
                )
            }
        }

        composeTestRule.onNodeWithText("PROSE DIGEST").assertExists()
        // `Settings-Assets.dc.html` verbatim: "verified e3d981c0" — no "sha256" word.
        composeTestRule
            .onNodeWithContentDescription(
                "Gemma 3 1B int4 — prose digest. 555 MB · bundled · verified e3d981c0 · " +
                    "stored, loaded only at tier 3 while idle and charging",
            )
            .assertExists()
        // AC-138: stored is not loaded — never the same solid "active" marker every other
        // installed row gets, which would wrongly imply this one is resident too.
        composeTestRule.onNodeWithText("ACTIVE").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Download Gemma 3 1B int4 — prose digest").assertDoesNotExist()
    }

    @Test
    @Requirement("FR-AST-3")
    fun `E2_F04 a gated, not-yet-installed Gemma row states the dev escape hatch honestly`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(rows = listOf(gemmaRow(ModelRowStatus.NOT_INSTALLED))),
                    onDownload = {},
                    onSideload = {},
                )
            }
        }

        // The committed manifest marks Gemma `gated = true` (D36) — confirmed against
        // `bundled-assets.json` before writing this — so a `NOT_INSTALLED` row here is always the
        // real dev-escape-hatch case this build can actually produce, never a fabricated example.
        composeTestRule
            .onNodeWithText("not in this build — needs the gated download at build time", substring = true)
            .assertExists()
    }

    @Test
    @Requirement("FR-AST-3")
    fun `E2_F04 no Download action ever appears anywhere on this screen — every asset is bundled`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(
                        rows = listOf(
                            row(ModelId.ASR_ENCODER, ModelRowStatus.NOT_INSTALLED),
                            row(ModelId.ASR_DECODER, ModelRowStatus.NOT_INSTALLED),
                            row(ModelId.ASR_TOKENS, ModelRowStatus.NOT_INSTALLED, checksumKnown = false),
                            row(ModelId.VAD, ModelRowStatus.NOT_INSTALLED),
                            gemmaRow(ModelRowStatus.NOT_INSTALLED),
                        ),
                    ),
                    onDownload = {},
                    onSideload = {},
                )
            }
        }

        composeTestRule.onAllNodesWithContentDescription("Download", substring = true).assertCountEquals(0)
    }

    @Test
    @Requirement("AC-140")
    fun `E2_F05 the Write prose summaries toggle reflects state and calls back on tap`() {
        var toggledTo: Boolean? = null
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(rows = listOf(gemmaRow(ModelRowStatus.INSTALLED))),
                    onDownload = {},
                    onSideload = {},
                    extras = ModelsExtrasViewState(
                        proseDigest = ProseDigestSectionViewState(enabled = true, onToggle = { toggledTo = it }),
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("Write prose summaries").assertExists()
        composeTestRule.onNodeWithTag(PROSE_DIGEST_TOGGLE_TEST_TAG).assertIsOn()
        composeTestRule.onNodeWithTag(PROSE_DIGEST_TOGGLE_TEST_TAG).performClick()

        assert(toggledTo == false) { "expected the toggle to call back with false, got $toggledTo" }
    }

    @Test
    @Requirement("AC-140")
    fun `E2_F05 a disabled prose toggle reads off, never on`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(rows = listOf(gemmaRow(ModelRowStatus.INSTALLED))),
                    onDownload = {},
                    onSideload = {},
                    extras = ModelsExtrasViewState(
                        proseDigest = ProseDigestSectionViewState(enabled = false, onToggle = {}),
                    ),
                )
            }
        }

        composeTestRule.onNodeWithTag(PROSE_DIGEST_TOGGLE_TEST_TAG).assertIsOff()
    }

    @Test
    @Requirement("FR-AST-3")
    fun `E2_F04 no proseDigest section at all when the caller passes null, matching lexicon's own convention`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(rows = listOf(gemmaRow(ModelRowStatus.INSTALLED))),
                    onDownload = {},
                    onSideload = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Write prose summaries").assertDoesNotExist()
    }

    @Test
    @Requirement("AC-139")
    fun `E2_F07 the Space row shows the real bundled-storage figure when the caller supplies one`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(rows = listOf(row(ModelId.VAD, ModelRowStatus.INSTALLED, sizeBytes = 1L))),
                    onDownload = {},
                    onSideload = {},
                    extras = ModelsExtrasViewState(spaceUsedBytesLabel = "635 MB"),
                )
            }
        }

        composeTestRule.onNodeWithText("SPACE").assertExists()
        composeTestRule.onNodeWithText("Bundled assets use 635 MB").assertExists()
        composeTestRule
            .onNodeWithText("not counted against your recording budget", substring = true)
            .assertExists()
    }

    @Test
    @Requirement("AC-139")
    fun `E2_F07 no Space row at all while the figure has not measured yet`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(rows = listOf(row(ModelId.VAD, ModelRowStatus.INSTALLED, sizeBytes = 1L))),
                    onDownload = {},
                    onSideload = {},
                )
            }
        }

        composeTestRule.onNodeWithText("SPACE").assertDoesNotExist()
    }

    // --- R-841 (round 1, tour finding): subtitle, section order, and the omitted "Replacing an
    // asset" section, all audited against `Settings-Assets.dc.html` -----------------------------

    @Test
    @Requirement("FR-AST-3")
    fun `R_841 the subtitle is the board's own D35 copy, not the pre-D35 download-and-sideload line`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(rows = listOf(row(ModelId.VAD, ModelRowStatus.INSTALLED, sizeBytes = 1L))),
                    onDownload = {},
                    onSideload = {},
                )
            }
        }

        composeTestRule
            .onNodeWithText("Bundled with the app and verified on first launch. Nothing is downloaded.")
            .assertExists()
        composeTestRule
            .onNodeWithText("Installed by you, from a file", substring = true)
            .assertDoesNotExist()
    }

    @Test
    @Requirement("FR-AST-3")
    fun `R_841 the Replacing-an-asset section renders the board's own copy`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(rows = listOf(row(ModelId.VAD, ModelRowStatus.INSTALLED, sizeBytes = 1L))),
                    onDownload = {},
                    onSideload = {},
                )
            }
        }

        composeTestRule.onNodeWithText("REPLACING AN ASSET").assertExists()
        composeTestRule.onNodeWithText("Takes effect at the next session").assertExists()
        composeTestRule
            .onNodeWithText(
                "a model or lexicon is never swapped under a running capture · the bundled copy stays as the fallback",
            )
            .assertExists()
    }

    @Test
    @Requirement("FR-AST-3")
    fun `R_841 section order is Transcription, Prose digest, Lexicon, Space — matching the board`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(rows = listOf(gemmaRow(ModelRowStatus.INSTALLED))),
                    onDownload = {},
                    onSideload = {},
                    lexicon = org.ort.app.ui.data.LexiconAssetActions(
                        row = org.ort.app.ui.data.LexiconAssetRowViewState(installed = false, label = "not installed"),
                        importResult = null,
                        onInstall = {},
                        onDismissResult = {},
                    ),
                    extras = ModelsExtrasViewState(spaceUsedBytesLabel = "635 MB"),
                )
            }
        }

        val order = listOf("PROSE DIGEST", "LEXICON", "SPACE", "REPLACING AN ASSET")
        val positions = order.map { label ->
            composeTestRule.onNodeWithText(label).fetchSemanticsNode().positionInRoot.y
        }
        assert(positions == positions.sorted()) {
            "expected section headers top-to-bottom in board order $order, got y-positions $positions"
        }
    }
}
