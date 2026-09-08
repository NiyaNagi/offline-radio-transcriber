package org.ort.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.ort.app.transmissions.TransmissionListViewStateMapper
import org.ort.app.transmissions.TransmissionRow
import org.ort.app.ui.components.AttributionMarker
import org.ort.app.ui.theme.OrtSpacing

/**
 * The "Log" destination's Compose surface (build-plan P13), replacing
 * [org.ort.app.transmissions.TransmissionListActivity]'s plain `TextView` rows. Each row's
 * attribution renders through the shared [AttributionMarker] component (AC-62) rather than a
 * second, screen-specific scheme; the station label comes from the same
 * [TransmissionListViewStateMapper] the marker itself is built on, so there is exactly one place
 * that turns an [org.ort.core.Attribution] into text.
 */
@Composable
public fun TransmissionListScreen(rows: List<TransmissionRow>, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) {
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
        items(rows, key = { it.id }) { row -> TransmissionRowContent(row) }
    }
}

@Composable
private fun TransmissionRowContent(row: TransmissionRow) {
    val mapped = TransmissionListViewStateMapper.from(row)
    Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)) {
        AttributionMarker(attribution = row.attribution)
        mapped.stationLabel?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
        if (mapped.transcript.isNotBlank()) {
            Text(text = mapped.transcript, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
