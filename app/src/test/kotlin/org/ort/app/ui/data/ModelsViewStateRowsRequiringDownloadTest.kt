package org.ort.app.ui.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * P22 (D43, FR-AST-10..12): [ModelsViewState.rowsRequiringDownload] is what the setup MODELS step
 * (and [org.ort.app.ui.setup.SetupStateMachine]'s own READY gate, via
 * [org.ort.app.ui.setup.SetupActivity]'s `currentSnapshot()`) reads to decide what is still
 * missing — pure, no Context, no `ModelsController`.
 */
class ModelsViewStateRowsRequiringDownloadTest {

    private fun row(id: ModelId, bundled: Boolean, status: ModelRowStatus, tierEligible: Boolean = true) =
        ModelRowViewState(
            id = id,
            label = id.label,
            status = status,
            detail = null,
            bundled = bundled,
            tierEligible = tierEligible,
        )

    @Test
    fun `AC_184 a bundled row is never required, whatever its status`() {
        val state =
            ModelsViewState(listOf(row(ModelId.ASR_ENCODER, bundled = true, status = ModelRowStatus.NOT_INSTALLED)))

        assertEquals(emptyList<ModelRowViewState>(), state.rowsRequiringDownload())
    }

    @Test
    fun `AC_184 a non-bundled, not-yet-installed, tier-eligible row is required`() {
        val missing = row(ModelId.ASR_ENCODER, bundled = false, status = ModelRowStatus.NOT_INSTALLED)
        val state = ModelsViewState(listOf(missing))

        assertEquals(listOf(missing), state.rowsRequiringDownload())
    }

    @Test
    fun `AC_184 a non-bundled row already installed is not required`() {
        val installed = row(ModelId.ASR_ENCODER, bundled = false, status = ModelRowStatus.INSTALLED)
        val state = ModelsViewState(listOf(installed))

        assertEquals(emptyList<ModelRowViewState>(), state.rowsRequiringDownload())
    }

    @Test
    fun `AC_184 a tier-ineligible row is never required, even if genuinely missing and non-bundled`() {
        val tierIneligible =
            row(ModelId.ASR_ENCODER, bundled = false, status = ModelRowStatus.NOT_INSTALLED, tierEligible = false)
        val state = ModelsViewState(listOf(tierIneligible))

        assertEquals(emptyList<ModelRowViewState>(), state.rowsRequiringDownload())
    }

    @Test
    fun `R_862 parity -- the gated Gemma model is never required, matching modelsRow's own carve-out`() {
        val gemma = row(ModelId.LLM_GEMMA3_1B, bundled = false, status = ModelRowStatus.NOT_INSTALLED)
        val state = ModelsViewState(listOf(gemma))

        assertEquals(emptyList<ModelRowViewState>(), state.rowsRequiringDownload())
    }

    @Test
    fun `AC_184 rowsForSetupModelsStep keeps an installed row visible, unlike rowsRequiringDownload`() {
        val installed = row(ModelId.ASR_ENCODER, bundled = false, status = ModelRowStatus.INSTALLED)
        val state = ModelsViewState(listOf(installed))

        assertEquals(emptyList<ModelRowViewState>(), state.rowsRequiringDownload())
        assertEquals(listOf(installed), state.rowsForSetupModelsStep())
    }

    @Test
    fun `AC_184 rowsForSetupModelsStep excludes a bundled row exactly like rowsRequiringDownload`() {
        val bundled = row(ModelId.ASR_ENCODER, bundled = true, status = ModelRowStatus.NOT_INSTALLED)
        val state = ModelsViewState(listOf(bundled))

        assertEquals(emptyList<ModelRowViewState>(), state.rowsForSetupModelsStep())
    }
}
