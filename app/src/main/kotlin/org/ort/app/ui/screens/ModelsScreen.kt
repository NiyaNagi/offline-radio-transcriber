package org.ort.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.ort.app.ui.data.ModelId
import org.ort.app.ui.data.ModelRowStatus
import org.ort.app.ui.data.ModelRowViewState
import org.ort.app.ui.data.ModelsViewState
import org.ort.app.ui.theme.OrtSpacing

/**
 * Audit F-008: the declared, user-initiated channel through which an ASR (Whisper tiny.en) or VAD
 * (Silero) model reaches the device (constitution V, FR-ASR-1). Reachable from the drawer's
 * `Settings` destination. Every row shows the two facts constitution I requires be visible rather
 * than assumed: whether a model is installed, and — only when [ModelAcquisition] itself verified
 * it — that installation was checksum-verified, never merely "a file exists at this path".
 */
@Composable
public fun ModelsScreen(
    state: ModelsViewState,
    busy: Set<ModelId> = emptySet(),
    lastMessage: String? = null,
    onDownload: (ModelId) -> Unit,
    onSideload: (ModelId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(OrtSpacing.lg)) {
        Text(text = "Models", style = MaterialTheme.typography.titleLarge)
        state.requeuedMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = OrtSpacing.sm).semantics { contentDescription = message },
            )
        }
        lastMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(top = OrtSpacing.sm)
                    .semantics { contentDescription = "Last action: $message" },
            )
        }
        state.rows.forEach { row ->
            ModelRow(
                row = row,
                isBusy = row.id in busy,
                onDownload = onDownload,
                onSideload = onSideload,
            )
        }
    }
}

@Composable
private fun ModelRow(
    row: ModelRowViewState,
    isBusy: Boolean,
    onDownload: (ModelId) -> Unit,
    onSideload: (ModelId) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.lg)) {
        Text(text = row.label, style = MaterialTheme.typography.titleMedium)
        val statusText = when {
            isBusy -> "Downloading…"
            row.status == ModelRowStatus.INSTALLED -> "Installed (checksum verified)"
            else -> "Not installed"
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .padding(top = OrtSpacing.xs)
                .semantics { contentDescription = "${row.label} status: $statusText" },
        )
        row.detail?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(top = OrtSpacing.xs)
                    .semantics { contentDescription = "${row.label} detail: $detail" },
            )
        }
        Row(modifier = Modifier.padding(top = OrtSpacing.sm)) {
            Button(
                onClick = { onDownload(row.id) },
                enabled = !isBusy,
                modifier = Modifier.semantics { contentDescription = "Download ${row.label}" },
            ) { Text("Download") }
            Button(
                onClick = { onSideload(row.id) },
                enabled = !isBusy,
                modifier = Modifier
                    .padding(start = OrtSpacing.sm)
                    .semantics { contentDescription = "Side-load ${row.label}" },
            ) { Text("Side-load from file") }
        }
    }
}
