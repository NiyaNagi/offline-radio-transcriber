package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.assets.AndroidBundledAssetSource
import org.ort.app.assets.BundledAssetInstaller
import org.ort.app.assets.BundledAssetSource
import org.ort.app.ui.data.ModelId
import org.ort.app.ui.data.ModelsController
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import java.io.InputStream

/**
 * Register R-934 follow-up (`results/ui-audit/register.md`): WPG's `ModelRowViewState.lastRejection`
 * (`BundledAssetInstaller.kt`, merged `a7a0232c`) persists a genuine checksum-mismatch rejection
 * beside the asset's own marker — this proves CF04 actually renders it, on the real production path
 * (`ModelsController.currentState`, the same real read `R_443_clean_install_groups` and `R_863`
 * (`ModelsScreenTest.kt`) already exercise), never a hand-built `ModelRowViewState` standing in for
 * the real installer's own output.
 */
@RunWith(RobolectricTestRunner::class)
class ModelsScreenRejectionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * Mirrors `BundledAssetInstallerTest.kt`'s own `CorruptingBundledAssetSource`-shaped technique
     * exactly (that private class lives in a different module's test source set and cannot be
     * imported here, per that file's own doc comment) — one real byte of one real bundled asset
     * flipped, never a fabricated digest mismatch — but wrapping the *real* app assets
     * ([AndroidBundledAssetSource]) rather than a synthetic manifest, so every other real asset
     * still installs and verifies for real: the genuine `asset-corrupt` shape (several good parts,
     * one genuinely corrupted), not an isolated single-asset fixture.
     */
    private fun corruptingSource(context: android.content.Context, corruptAssetPath: String): BundledAssetSource {
        val real = AndroidBundledAssetSource(context)
        return object : BundledAssetSource {
            override fun open(assetPath: String): InputStream {
                val stream = real.open(assetPath)
                if (assetPath != corruptAssetPath) return stream
                val original = stream.use { it.readBytes() }
                val corrupted = original.copyOf()
                corrupted[corrupted.size - 1] = corrupted[corrupted.size - 1].inc()
                return corrupted.inputStream()
            }
        }
    }

    @Test
    @Requirement("FR-AST-4")
    fun `R_934 a genuinely corrupted part reads the real rejection sentence, not plain not-yet-verified`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val corruptAssetPath = "bundled/models/whisper-tiny-en-int8/tiny.en-encoder.int8.onnx"
        BundledAssetInstaller.installAll(context.filesDir, corruptingSource(context, corruptAssetPath))
        val state = ModelsController.currentState(context)

        val encoderRow = state.rows.first { it.id == ModelId.ASR_ENCODER }
        val rejection = encoderRow.lastRejection
        check(rejection != null) {
            "test setup failed to reproduce a real rejection — installAll's own result: $encoderRow"
        }

        composeTestRule.setContent {
            OrtTheme { ModelsScreen(state = state, onDownload = {}, onSideload = {}) }
        }

        // Every fact in this sentence is read back from the real, persisted `BundledAssetRejection`
        // above — never a hand-typed prefix pair standing in for it (the corrupted checksum cannot
        // be predicted ahead of time; only the real installer run can produce it).
        composeTestRule
            .onNodeWithText(
                "${encoderRow.label} failed verification on this launch (sha256 ${rejection.expectedPrefix}… " +
                    "≠ ${rejection.actualPrefix}…) and was removed",
                substring = true,
            )
            .assertExists()
    }

    @Test
    @Requirement("FR-AST-4")
    fun `R_934 an uncorrupted install keeps the plain not-yet-verified copy, never the rejection sentence`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        BundledAssetInstaller.installAll(context.filesDir, AndroidBundledAssetSource(context))
        val state = ModelsController.currentState(context)

        val encoderRow = state.rows.first { it.id == ModelId.ASR_ENCODER }
        check(encoderRow.lastRejection == null) {
            "test setup should have a real, clean install — got a rejection: ${encoderRow.lastRejection}"
        }

        composeTestRule.setContent {
            OrtTheme { ModelsScreen(state = state, onDownload = {}, onSideload = {}) }
        }

        composeTestRule.onNodeWithText("failed verification on this launch", substring = true).assertDoesNotExist()
    }
}
