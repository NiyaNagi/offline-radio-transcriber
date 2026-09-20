package org.ort.app.ui.setup

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.ModelId
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** P22 (D43, FR-AST-10..12) — `SetupStep.MODELS`. No artboard exists yet for this screen (outside
 * this unit's `design` tree ownership); these tests cover the real per-row states, the Wi-Fi-only
 * toggle, and the Continue gate. */
@RunWith(RobolectricTestRunner::class)
class ModelsSetupScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val pendingRow = ModelDownloadRowViewState(
        id = ModelId.ASR_ENCODER,
        label = ModelId.ASR_ENCODER.label,
        sizeBytes = 42 * 1024 * 1024L,
        status = ModelDownloadRowStatus.PENDING,
    )

    @Test
    fun `AC_184 a pending row offers Download and never claims installed`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsSetupScreen(
                    state = ModelsSetupViewState(rows = listOf(pendingRow), wifiOnly = true),
                    onDownload = {},
                    onToggleWifiOnly = {},
                    onContinue = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-models-row-${ModelId.ASR_ENCODER.name}-download").assertIsDisplayed()
    }

    @Test
    fun `AC_184 tapping Download invokes onDownload with the row's own id`() {
        var downloaded: ModelId? = null
        composeTestRule.setContent {
            OrtTheme {
                ModelsSetupScreen(
                    state = ModelsSetupViewState(rows = listOf(pendingRow), wifiOnly = true),
                    onDownload = { downloaded = it },
                    onToggleWifiOnly = {},
                    onContinue = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-models-row-${ModelId.ASR_ENCODER.name}-download").performClick()
        assertEquals(ModelId.ASR_ENCODER, downloaded)
    }

    @Test
    fun `AC_188 Continue is disabled while any row is not yet installed`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsSetupScreen(
                    state = ModelsSetupViewState(rows = listOf(pendingRow), wifiOnly = true),
                    onDownload = {},
                    onToggleWifiOnly = {},
                    onContinue = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-models-continue").assertIsNotEnabled()
    }

    @Test
    fun `AC_188 Continue is enabled once every row is installed`() {
        val installed = pendingRow.copy(status = ModelDownloadRowStatus.INSTALLED)
        composeTestRule.setContent {
            OrtTheme {
                ModelsSetupScreen(
                    state = ModelsSetupViewState(rows = listOf(installed), wifiOnly = true),
                    onDownload = {},
                    onToggleWifiOnly = {},
                    onContinue = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-models-continue").assertIsDisplayed().performClick()
    }

    @Test
    fun `AC_187 the Wi-Fi-only toggle reflects state and reports a change`() {
        var wifiOnly: Boolean? = null
        composeTestRule.setContent {
            OrtTheme {
                ModelsSetupScreen(
                    state = ModelsSetupViewState(rows = listOf(pendingRow), wifiOnly = true),
                    onDownload = {},
                    onToggleWifiOnly = { wifiOnly = it },
                    onContinue = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-models-wifi-only").performClick()
        assertEquals(false, wifiOnly)
    }

    @Test
    fun `a failed row shows its real failure reason and offers Retry, never a fabricated one`() {
        val failed = pendingRow.copy(status = ModelDownloadRowStatus.FAILED, failureReason = "connection interrupted")
        assertEquals("connection interrupted", modelDownloadRowStatusLine(failed))
    }

    /**
     * R-1092 (register, design-guide §3, FR-AST-10, AC-186): the FAILED row's reason used to render
     * in `text/dim`, the same colour every other row's status line uses, so a checksum mismatch read
     * like ordinary secondary text rather than the amber the guide reserves for a failed state
     * (§6.8 "Failed / unavailable — amber ... never a bare 'error'"). [modelDownloadRowStatusColor]
     * is the single place [ModelDownloadRow] reads its status-line colour from; pinning it here
     * means the composable cannot drift back to dim without this test failing.
     */
    @Test
    fun `R_1092 a FAILED row's reason renders in accent amber text, not dim`() {
        val failed = pendingRow.copy(status = ModelDownloadRowStatus.FAILED, failureReason = "checksum mismatch")
        assertEquals(OrtColors.accentAmberText, modelDownloadRowStatusColor(failed))
    }

    @Test
    fun `R_1092 every non-FAILED row keeps the ordinary dim status colour`() {
        val pending = pendingRow.copy(status = ModelDownloadRowStatus.PENDING)
        val downloading = pendingRow.copy(status = ModelDownloadRowStatus.DOWNLOADING)
        val installed = pendingRow.copy(status = ModelDownloadRowStatus.INSTALLED)
        assertEquals(OrtColors.textDim, modelDownloadRowStatusColor(pending))
        assertEquals(OrtColors.textDim, modelDownloadRowStatusColor(downloading))
        assertEquals(OrtColors.textDim, modelDownloadRowStatusColor(installed))
    }

    /** The wiring proof the pure-function test above cannot stand in for: the real composition
     * renders the FAILED reason with `OrtType.subLine` unchanged (only its colour moves), and it
     * is still findable by its own text. Colour itself is not queryable from the semantics tree
     * (`OrtThemeTest`'s own note: `captureToImage()` hangs in this sandbox with no network egress),
     * so [modelDownloadRowStatusColor] — [ModelDownloadRow]'s one and only source for this colour —
     * is what carries the pin; this test guards that the reason text a checksum failure reports is
     * still the one rendered, not silently swapped for a generic "Download failed" while the colour
     * moves. */
    @Test
    fun `R_1092 a FAILED row still renders its own real failure reason on screen`() {
        val failed = pendingRow.copy(status = ModelDownloadRowStatus.FAILED, failureReason = "checksum mismatch")
        composeTestRule.setContent {
            OrtTheme {
                ModelsSetupScreen(
                    state = ModelsSetupViewState(rows = listOf(failed), wifiOnly = true),
                    onDownload = {},
                    onToggleWifiOnly = {},
                    onContinue = {},
                )
            }
        }
        composeTestRule.onNodeWithText("checksum mismatch").assertIsDisplayed()
    }

    @Test
    fun `a downloading row never claims a progress figure it does not have`() {
        val downloading = pendingRow.copy(status = ModelDownloadRowStatus.DOWNLOADING)
        val line = modelDownloadRowStatusLine(downloading)
        assertTrue(line.contains("Downloading"))
        assertFalse(line.any { it.isDigit() })
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `the continue action is reachable at font scale 2_0 at the tour's own width and density`() {
        val installed = pendingRow.copy(status = ModelDownloadRowStatus.INSTALLED)
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme {
                    ModelsSetupScreen(
                        state = ModelsSetupViewState(rows = listOf(installed), wifiOnly = true),
                        onDownload = {},
                        onToggleWifiOnly = {},
                        onContinue = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("setup-models-continue").assertIsDisplayed()
    }
}
