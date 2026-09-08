package org.ort.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.ort.app.ui.components.AttributionMarker
import org.ort.app.ui.data.TransmissionListEntryViewState
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * The "Log" destination (build-plan P14, `design/canvas/Log.dc.html`): the dense, instrument-like
 * table of every transmission, newest first (FR-UI-1 — the ordering itself is [entries]' caller's
 * job, see `ui/data/ReaderPolling.currentTransmissionDetails`). Every row's attribution renders
 * through [AttributionMarker] (FR-UI-4, AC-62) rather than a second scheme, and a transmission
 * with no transcript yet shows the honest text `ReaderTransmissionViewStateMapper` produces —
 * never an empty row or a spinner that never resolves, since a model-less install is exactly the
 * state this app ships in today.
 *
 * One deliberate divergence from the canvas: `Log.dc.html` groups consecutive overs under a QSO
 * header ("QSO · 4 overs · 2 stations") and shows a `not listening` gap row inline. Thread
 * grouping is FR-UI-2, explicitly P15's prompt ("Search and threads"), and the gap row needs
 * `CaptureGap` data this screen does not yet read — both left for their own prompts rather than
 * faked here with placeholder grouping logic.
 */
@Composable
public fun LogScreen(
    entries: List<TransmissionListEntryViewState>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) {
        Text(
            text = "No transmissions yet",
            style = MaterialTheme.typography.bodyLarge,
            modifier = modifier
                .fillMaxSize()
                .padding(OrtSpacing.lg)
                .semantics { contentDescription = "No transmissions yet" },
        )
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(entries, key = { it.id }) { entry -> LogRow(entry = entry, onOpen = onOpen) }
    }
}

@Composable
private fun LogRow(entry: TransmissionListEntryViewState, onOpen: (String) -> Unit) {
    val callsignOrUnidentified = entry.attribution.stationId ?: "Unidentified station"
    val rowDescription = buildString {
        append("Transmission at ${entry.timeLabel} on ${entry.frequencyLabel}, $callsignOrUnidentified")
        entry.revisionNote?.let { append(", $it") }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(entry.id) }
            .semantics(mergeDescendants = true) { contentDescription = rowDescription }
            .padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm),
    ) {
        Column(modifier = Modifier.padding(end = OrtSpacing.sm)) {
            Text(text = entry.timeLabel, style = OrtType.caption)
            Text(text = entry.frequencyLabel, style = OrtType.caption)
        }
        Column(modifier = Modifier.padding(end = OrtSpacing.sm)) {
            Row {
                AttributionMarker(attribution = entry.attribution)
                Text(text = "  $callsignOrUnidentified", style = OrtType.callsign)
            }
            Text(text = entry.transcriptText, style = MaterialTheme.typography.bodyMedium)
            entry.revisionNote?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
            }
        }
        entry.signalLabel?.let { Text(text = it, style = OrtType.caption) }
    }
}
