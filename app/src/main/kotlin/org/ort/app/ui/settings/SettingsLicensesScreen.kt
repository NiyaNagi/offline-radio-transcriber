@file:Suppress("MatchingDeclarationName")
// LicenceNotice/BundledLicenceNotices are additional public declarations here.

package org.ort.app.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.NavRow
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType
import java.io.IOException

/**
 * P27 (Wave G, `spec/build-plan.md`): NFR-6d, AC-167, FR-AST-14's own notices clause — a real,
 * navigable third-party licence notices screen, reachable from Settings, listing every bundled
 * model and library with its own notice obligation. Every notice's text is a bundled app asset
 * under `app/src/main/assets/licenses/` (read via [readLicenceAsset], `Context.assets` only) — this
 * screen never touches `:net`, so it satisfies AC-167's "reachable and readable without a network
 * connection" by construction, not by a runtime check.
 *
 * [BundledLicenceNotices.ALL] names the five AC-167 requires by name (Gemma, Whisper, sherpa-onnx,
 * ONNX Runtime, Silero) plus every further bundled dependency this unit's own report verified has a
 * real notice obligation: MediaPipe (`:llm-mediapipe`), usb-serial-for-android (`:rig-usb`),
 * AndroidX and Kotlin. Each licence was verified against the dependency's own published licence
 * file, not guessed — see this unit's own report for the source checked per entry. Hilt/Dagger is
 * deliberately absent: `AGENTS.md`'s stack list names it, but no module in this build actually
 * depends on it (confirmed by search before writing this) — listing a notice for a library this
 * build does not ship would itself be a constitution I violation (a claim beyond what is true).
 *
 * [BundledLicenceNotices.BUNDLED_ASSET_NOTICES] maps every `bundled-assets.json` entry id to the
 * notice covering it; [SettingsLicensesCoverageTest] walks the real, generated
 * [org.ort.app.assets.GeneratedBundledAssetManifest] against this map's keys, so a future asset
 * shipped with no matching notice entry fails a test, not a review (this unit's own "Tests first"
 * brief — "a new model can't ship without one").
 */
public data class LicenceNotice(
    public val name: String,
    public val licenceLabel: String,
    public val assetFileName: String,
)

public object BundledLicenceNotices {
    public val GEMMA: LicenceNotice = LicenceNotice("Gemma 3 1B", "Gemma Terms of Use", "gemma.txt")
    public val WHISPER: LicenceNotice = LicenceNotice("Whisper tiny.en", "MIT License", "whisper.txt")
    public val SHERPA_ONNX: LicenceNotice = LicenceNotice("sherpa-onnx", "Apache License 2.0", "sherpa-onnx.txt")
    public val ONNX_RUNTIME: LicenceNotice = LicenceNotice("ONNX Runtime", "MIT License", "onnxruntime.txt")
    public val SILERO_VAD: LicenceNotice = LicenceNotice("Silero VAD", "MIT License", "silero-vad.txt")
    public val MEDIAPIPE: LicenceNotice = LicenceNotice("MediaPipe", "Apache License 2.0", "mediapipe.txt")
    public val USB_SERIAL_FOR_ANDROID: LicenceNotice =
        LicenceNotice("usb-serial-for-android", "MIT License", "usb-serial-for-android.txt")
    public val ANDROIDX: LicenceNotice = LicenceNotice("AndroidX", "Apache License 2.0", "androidx.txt")
    public val KOTLIN: LicenceNotice = LicenceNotice("Kotlin", "Apache License 2.0", "kotlin.txt")

    /** Board order — AC-167's own five named first, then every further bundled dependency this
     * unit's own report verified a real notice obligation for. */
    public val ALL: List<LicenceNotice> = listOf(
        GEMMA,
        WHISPER,
        SHERPA_ONNX,
        ONNX_RUNTIME,
        SILERO_VAD,
        MEDIAPIPE,
        USB_SERIAL_FOR_ANDROID,
        ANDROIDX,
        KOTLIN,
    )

    /** Every `bundled-assets.json` entry id this build ships (per
     * [org.ort.app.assets.GeneratedBundledAssetManifest]), mapped to the notice that covers it —
     * three Whisper components (encoder, decoder, tokens) share the one Whisper notice, the same
     * grouping `SettingsPolling`'s own "Models and lexicon" row already uses for display. */
    public val BUNDLED_ASSET_NOTICES: Map<String, LicenceNotice> = mapOf(
        "ASR_ENCODER" to WHISPER,
        "ASR_DECODER" to WHISPER,
        "ASR_TOKENS" to WHISPER,
        "VAD" to SILERO_VAD,
        "LLM_GEMMA3_1B" to GEMMA,
    )
}

@Composable
public fun SettingsLicensesScreen(context: Context, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var selected by remember { mutableStateOf<LicenceNotice?>(null) }
    val notice = selected
    if (notice != null) {
        SettingsLicenseDetail(context = context, notice = notice, onBack = { selected = null }, modifier = modifier)
        return
    }
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DrillInHeader(parentLabel = "Settings", onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
            Text(
                text = "Third-party licences",
                style = OrtType.screenTitle,
                color = OrtColors.textHigh,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.xs),
            )
            Text(
                text = "Every bundled model and library with its own notice obligation. Each one is " +
                    "reachable and readable here, offline — nothing on this screen reaches the network.",
                style = OrtType.bodyProse,
                color = OrtColors.textDim,
                modifier = Modifier.padding(bottom = OrtSpacing.md),
            )
        }
        BundledLicenceNotices.ALL.forEach { entry ->
            NavRow(
                rowTitle = entry.name,
                subLine = entry.licenceLabel,
                onClick = { selected = entry },
                // No bespoke "document"/"licence" glyph exists in `OrtIcons.kt`, and adding one is
                // outside this unit's own file-ownership map (the same reason `SettingsRootScreen`'s
                // own `iconFor` already falls back to this exact icon for TIER/ABOUT) — reused here
                // rather than inventing an association `OrtIcons` does not carry.
                icon = OrtIcons.settings,
            )
        }
    }
}

@Composable
private fun SettingsLicenseDetail(context: Context, notice: LicenceNotice, onBack: () -> Unit, modifier: Modifier) {
    val text = remember(notice.assetFileName) { readLicenceAsset(context, notice.assetFileName) }
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DrillInHeader(parentLabel = "Licences", onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)) {
            Text(text = notice.name, style = OrtType.screenTitle, color = OrtColors.textHigh)
            Text(
                text = notice.licenceLabel,
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.md),
            )
            Text(text = text, style = OrtType.cardBody, color = OrtColors.textBody)
        }
    }
}

/**
 * Real bundled asset text — `Context.assets` reads only the local APK's own packaged files, never
 * `:net`, so NFR-6d's "reachable offline" holds structurally, not by a runtime guard. Returns an
 * honest failure message rather than crashing if a future change to `app/src/main/assets/licenses/`
 * ever drops a file this catalogue still names (constitution I: a missing notice is content, not a
 * crash).
 */
private fun readLicenceAsset(context: Context, fileName: String): String = try {
    context.assets.open("licenses/$fileName").bufferedReader().use { it.readText() }
} catch (missing: IOException) {
    "This notice's text could not be read from the app bundle ($fileName)."
}
