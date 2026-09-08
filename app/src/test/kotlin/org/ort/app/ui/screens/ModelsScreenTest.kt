package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
                    lastMessage = "Silero VAD: installed, checksum verified.",
                    onDownload = {},
                    onSideload = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Requeued 3 previously failed transmission(s).").assertExists()
        composeTestRule.onNodeWithText("Silero VAD: installed, checksum verified.").assertExists()
    }
}
