package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.ModelCatalog
import org.ort.app.ui.data.ModelId
import org.ort.app.ui.data.ModelsController
import org.ort.app.ui.theme.OrtTheme
import org.ort.net.Checksum
import org.ort.net.ModelFetchSpec
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * Register R-865 (render half, WPG's guard landed `7354b061`): a marker whose text matches but
 * whose real on-disk length disagrees with the manifest must never read "verified" — it must read
 * "marker present, bytes missing … · re-verified on next launch", grey (never the solid installed
 * marker), and its bytes must never be counted anywhere as real. Reproduced on the real
 * [ModelsController.currentState] path (the exact `tier0-llm-stored` shape — a marker over an
 * empty or partial file), never a hand-built [org.ort.app.ui.data.ModelRowViewState] standing in
 * for it.
 */
@RunWith(RobolectricTestRunner::class)
class ModelsScreenTruncatedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun specForOnly(id: ModelId, destination: File, checksum: Checksum): (ModelId, File) -> ModelFetchSpec? =
        { candidate, _ ->
            if (candidate == id) {
                ModelFetchSpec(
                    url = "https://example.invalid/${id.name}",
                    destination = destination,
                    checksum = checksum,
                )
            } else {
                null
            }
        }

    @Test
    @Requirement("R-865")
    fun `R_865 a single-asset row with an empty on-disk file reads marker present bytes missing, never verified`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val tempFilesDir = Files.createTempDirectory("models-screen-r865-vad").toFile()
        val fakeContext = object : android.content.ContextWrapper(context) {
            override fun getFilesDir(): File = tempFilesDir
        }
        val destination = ModelCatalog.entry(ModelId.VAD).destination(tempFilesDir)
        val checksum = Checksum(value = "e".repeat(64))
        destination.parentFile?.mkdirs()
        destination.writeBytes(ByteArray(0))
        File(destination.parentFile, destination.name + ".sha256").writeText(checksum.value)

        val state = ModelsController.currentState(
            fakeContext,
            specFor = specForOnly(ModelId.VAD, destination, checksum),
        )
        val row = state.rows.first { it.id == ModelId.VAD }
        check(row.truncated != null) { "test setup failed to reproduce the truncated fact — got $row" }

        composeTestRule.setContent {
            OrtTheme { ModelsScreen(state = state, onDownload = {}, onSideload = {}) }
        }

        composeTestRule
            .onNodeWithText(
                "${row.label} marker present, bytes missing — 0 KB of ${declaredSizeLabelFor(ModelId.VAD)} on disk " +
                    "· re-verified on next launch",
                substring = true,
            )
            .assertExists()
        composeTestRule.onNodeWithText("verified e", substring = true).assertDoesNotExist()
    }

    @Test
    @Requirement("R-865")
    fun `R_865 a grouped part with a partial on-disk file reads the same honest sentence nested under the family`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val tempFilesDir = Files.createTempDirectory("models-screen-r865-encoder").toFile()
        val fakeContext = object : android.content.ContextWrapper(context) {
            override fun getFilesDir(): File = tempFilesDir
        }
        val destination = ModelCatalog.entry(ModelId.ASR_ENCODER).destination(tempFilesDir)
        val checksum = Checksum(value = "f".repeat(64))
        destination.parentFile?.mkdirs()
        destination.writeBytes(ByteArray(1_000))
        File(destination.parentFile, destination.name + ".sha256").writeText(checksum.value)

        val state = ModelsController.currentState(
            fakeContext,
            specFor = specForOnly(ModelId.ASR_ENCODER, destination, checksum),
        )
        val row = state.rows.first { it.id == ModelId.ASR_ENCODER }
        check(row.truncated != null) { "test setup failed to reproduce the truncated fact — got $row" }

        composeTestRule.setContent {
            OrtTheme { ModelsScreen(state = state, onDownload = {}, onSideload = {}) }
        }

        composeTestRule
            .onNodeWithText(
                "${row.label} marker present, bytes missing — 1 KB of ${declaredSizeLabelFor(ModelId.ASR_ENCODER)} " +
                    "on disk · re-verified on next launch",
                substring = true,
            )
            .assertExists()
    }

    @Test
    @Requirement("R-865")
    fun `R_865 the Gemma row reads the same honest sentence, never a fabricated verified checksum`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val tempFilesDir = Files.createTempDirectory("models-screen-r865-gemma").toFile()
        val fakeContext = object : android.content.ContextWrapper(context) {
            override fun getFilesDir(): File = tempFilesDir
        }
        val destination = ModelCatalog.entry(ModelId.LLM_GEMMA3_1B).destination(tempFilesDir)
        val checksum = Checksum(value = "0".repeat(64))
        destination.parentFile?.mkdirs()
        destination.writeBytes(ByteArray(0))
        File(destination.parentFile, destination.name + ".sha256").writeText(checksum.value)

        val state = ModelsController.currentState(
            fakeContext,
            specFor = specForOnly(ModelId.LLM_GEMMA3_1B, destination, checksum),
        )
        val row = state.rows.first { it.id == ModelId.LLM_GEMMA3_1B }
        check(row.truncated != null) { "test setup failed to reproduce the truncated fact — got $row" }

        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = state,
                    onDownload = {},
                    onSideload = {},
                    extras = ModelsExtrasViewState(),
                )
            }
        }

        composeTestRule
            .onNodeWithText(
                "${row.label} marker present, bytes missing — 0 KB of " +
                    "${declaredSizeLabelFor(ModelId.LLM_GEMMA3_1B)} on disk · re-verified on next launch",
                substring = true,
            )
            .assertExists()
        composeTestRule.onNodeWithText("verified 0", substring = true).assertDoesNotExist()
    }

    /** The row's own real declared size, formatted the identical way [ModelsScreen] itself does —
     * read from the real catalogue rather than a hand-typed literal that could silently drift from
     * the manifest. */
    private fun declaredSizeLabelFor(id: ModelId): String {
        val bytes = ModelCatalog.entry(id).sizeBytes
        val mb = bytes / 1_000_000.0
        val kb = bytes / 1_000.0
        return when {
            mb >= 1000.0 -> "%.1f GB".format(mb / 1000.0)
            mb >= 1.0 -> "%.0f MB".format(mb)
            else -> "%.0f KB".format(kb)
        }
    }
}
