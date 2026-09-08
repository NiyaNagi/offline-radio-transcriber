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
import org.ort.app.ui.data.ThreadEntryViewState
import org.ort.app.ui.data.ThreadGroupViewState
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * The "Threads" destination (build-plan P15, FR-UI-2): transmissions grouped into conversations,
 * each entry showing the reasoning behind its attribution — "which transmission confirmed a
 * callsign, and which inherited it" (the functional spec's own wording).
 * [org.ort.app.ui.data.ThreadGroupingMapper] builds [groups] from the real `threadId` column;
 * nothing populates it before M6, so today every real session renders as one honest "Not yet
 * grouped into threads" bucket rather than a fabricated conversation — this screen renders
 * whatever grouping the data actually supports, real or not yet real.
 */
@Composable
public fun ThreadScreen(groups: List<ThreadGroupViewState>, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    if (groups.isEmpty()) {
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
        groups.forEach { group ->
            item(key = "header-${group.threadId ?: "ungrouped"}") { ThreadHeader(group.label) }
            items(group.entries, key = { "${group.threadId}-${it.listEntry.id}" }) { entry ->
                ThreadEntryRow(entry = entry, onOpen = onOpen)
            }
        }
    }
}

@Composable
private fun ThreadHeader(label: String) {
    Text(
        text = label,
        style = OrtType.sectionLabel,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm),
    )
}

@Composable
private fun ThreadEntryRow(entry: ThreadEntryViewState, onOpen: (String) -> Unit) {
    val listEntry = entry.listEntry
    val callsignOrUnidentified = listEntry.attribution.stationId ?: "Unidentified station"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(listEntry.id) }
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "Transmission at ${listEntry.timeLabel}, $callsignOrUnidentified. ${entry.reasoning}"
            }
            .padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm),
    ) {
        Column {
            Row {
                AttributionMarker(attribution = listEntry.attribution)
                Text(text = "  $callsignOrUnidentified", style = OrtType.callsign)
            }
            Text(text = listEntry.transcriptText, style = MaterialTheme.typography.bodyMedium)
            Text(text = entry.reasoning, style = OrtType.caption)
        }
    }
}
