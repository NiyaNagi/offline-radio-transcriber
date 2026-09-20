package org.ort.app.ui.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.components.ToggleRow
import org.ort.app.ui.data.ModelId
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType

/**
 * P22 (D43, FR-AST-10..12) — the setup MODELS step (`SetupStep.MODELS`, right before `Ready`):
 * whatever the detected tier requires that this build did not bundle
 * ([org.ort.app.ui.data.ModelsViewState.rowsRequiringDownload]), each with its own real download
 * state read from `WorkManager` ([org.ort.app.work.ModelDownloadWorker.observe]). No artboard
 * exists for this screen yet (the `design` tree is outside this unit's file ownership) — built to this
 * package's own established `SetupScaffold` shape rather than left undrawn.
 */
public enum class ModelDownloadRowStatus { PENDING, DOWNLOADING, INSTALLED, FAILED }

/** One row's real state — never a placeholder. [failureReason] is non-null exactly when [status]
 * is [ModelDownloadRowStatus.FAILED] (constitution I: an absent conclusion and a failed one are
 * different facts, never conflated). */
public data class ModelDownloadRowViewState(
    val id: ModelId,
    val label: String,
    val sizeBytes: Long,
    val status: ModelDownloadRowStatus,
    val failureReason: String? = null,
)

/** [allInstalled] is what [SetupActivity]'s own `Continue` action — and, independently,
 * [SetupStateMachine]'s own `requiredModelsInstalled` gate — both need; kept in one place so
 * neither can silently compute it differently from the other. */
public data class ModelsSetupViewState(val rows: List<ModelDownloadRowViewState>, val wifiOnly: Boolean) {
    public val allInstalled: Boolean get() = rows.all { it.status == ModelDownloadRowStatus.INSTALLED }
}

@Composable
public fun ModelsSetupScreen(
    state: ModelsSetupViewState,
    onDownload: (ModelId) -> Unit,
    onToggleWifiOnly: (Boolean) -> Unit,
    onContinue: () -> Unit,
) {
    SetupScaffold(
        step = SetupStep.MODELS,
        title = "Models",
        subtitle = "This build did not carry everything your device's tier can use",
        onBack = null,
        bottomActions = {
            PrimaryButton(
                text = "Continue",
                onClick = onContinue,
                enabled = state.allInstalled,
                modifier = Modifier.fillMaxWidth().testTag("setup-models-continue"),
            )
        },
    ) {
        Text(
            text = "Every model below is downloaded once, verified against its published checksum, " +
                "and never activated unless it matches (FR-AST-2).",
            style = OrtType.bodyProse,
            color = OrtColors.textSecondary,
        )
        ToggleRow(
            label = "Wi-Fi only",
            checked = state.wifiOnly,
            onCheckedChange = onToggleWifiOnly,
            subLine = if (state.wifiOnly) {
                "Downloads wait for Wi-Fi"
            } else {
                "Downloads may use your mobile data"
            },
            modifier = Modifier.testTag("setup-models-wifi-only"),
        )
        state.rows.forEach { row -> ModelDownloadRow(row, onDownload) }
    }
}

@Composable
private fun ModelDownloadRow(row: ModelDownloadRowViewState, onDownload: (ModelId) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("setup-models-row-${row.id.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = row.label, style = OrtType.control, color = OrtColors.textHigh)
            Text(
                text = modelDownloadRowStatusLine(row),
                style = OrtType.subLine,
                color = modelDownloadRowStatusColor(row),
            )
        }
        when (row.status) {
            ModelDownloadRowStatus.PENDING -> TextAction(
                text = "Download",
                onClick = { onDownload(row.id) },
                modifier = Modifier.testTag("setup-models-row-${row.id.name}-download"),
            )
            ModelDownloadRowStatus.FAILED -> TextAction(
                text = "Retry",
                onClick = { onDownload(row.id) },
                modifier = Modifier.testTag("setup-models-row-${row.id.name}-retry"),
            )
            ModelDownloadRowStatus.DOWNLOADING, ModelDownloadRowStatus.INSTALLED -> {}
        }
    }
}

/** The one-line status a row shows beneath its label — never fabricated: a downloading row never
 * claims a percentage this screen has no real progress figure for (constitution I). */
internal fun modelDownloadRowStatusLine(row: ModelDownloadRowViewState): String = when (row.status) {
    ModelDownloadRowStatus.PENDING -> sizeLabel(row.sizeBytes)
    ModelDownloadRowStatus.DOWNLOADING -> "Downloading…"
    ModelDownloadRowStatus.INSTALLED -> "Installed · checksum verified"
    ModelDownloadRowStatus.FAILED -> row.failureReason ?: "Download failed"
}

/**
 * R-1092 (register, design-guide §3/§6.8, FR-AST-10, AC-186): the FAILED status line used to
 * render in `text/dim`, the same colour every other row's status line uses, so a checksum
 * mismatch read like ordinary secondary text rather than the amber the guide reserves for a
 * failed state ("Failed / unavailable — amber, states the cause in operator terms ... never a
 * bare 'error'"). [OrtColors.accentAmberText] is the guide's own "amber explanatory text" token
 * (§3: "Amber explanatory text, 'revised' badge text") — the colour, not a fill, since this is a
 * sentence of explanation beside the row, not a badge or a banner. [ModelDownloadRow] reads this
 * function alone for that colour, so nothing else can drift it back to dim independently.
 */
internal fun modelDownloadRowStatusColor(row: ModelDownloadRowViewState): Color = when (row.status) {
    ModelDownloadRowStatus.FAILED -> OrtColors.accentAmberText
    ModelDownloadRowStatus.PENDING,
    ModelDownloadRowStatus.DOWNLOADING,
    ModelDownloadRowStatus.INSTALLED,
    -> OrtColors.textDim
}

private fun sizeLabel(sizeBytes: Long): String = if (sizeBytes <= 0L) {
    "Size unknown"
} else {
    "%.0f MB".format(sizeBytes / (1024.0 * 1024.0))
}
