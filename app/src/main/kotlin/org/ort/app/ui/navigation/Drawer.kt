package org.ort.app.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ort.app.ui.theme.OrtSpacing

/**
 * The drawer (`Menu.dc.html`, build-plan P13). Lists every destination the canvas specifies, in
 * its order, replacing a tab bar — "Log, Threads, Stations, Frequencies and Earlier nights do not
 * fit in tabs, and Capture, Improve records and Settings belong in the same place"
 * (`canvas.json`'s `integrated` annotation). The storage footer (D26) is the same annotation's
 * other requirement: "a thing you actually watch on this product".
 */
@Composable
public fun ReaderDrawerContent(
    current: ReaderDestination,
    storage: StorageFooterViewState,
    onSelect: (ReaderDestination) -> Unit,
) {
    ModalDrawerSheet {
        // Scrollable: build-plan P15 added Search and Threads as real destinations (nine rows
        // became ten), and a fixed-height Column silently clips whatever falls below the visible
        // drawer height rather than failing loudly — confirmed by this project's own
        // ReaderAccessibilityTest, which asserts every destination row is actually displayed.
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            ReaderDestination.entries.forEach { destination ->
                DrawerRow(destination = destination, selected = destination == current, onSelect = onSelect)
            }
            StorageFooter(storage)
        }
    }
}

@Composable
private fun DrawerRow(destination: ReaderDestination, selected: Boolean, onSelect: (ReaderDestination) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = { onSelect(destination) })
            .semantics { contentDescription = "Open ${destination.label}" }
            .padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm),
    ) {
        Text(
            text = destination.label,
            style = if (selected) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun StorageFooter(storage: StorageFooterViewState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.md)
            .semantics(mergeDescendants = true) {
                contentDescription = "Storage: ${storage.usedBytes.toGigabyteLabel()} of " +
                    "${storage.totalBytes.toGigabyteLabel()} used" +
                    if (storage.isPlaceholder) " (device total — per-category budgets not yet set)" else ""
            },
    ) {
        Text(
            text = "Storage ${storage.usedBytes.toGigabyteLabel()}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(text = "of ${storage.totalBytes.toGigabyteLabel()}", style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(
            progress = { storage.usedFraction },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = OrtSpacing.xs)
                .height(4.dp),
        )
    }
}
