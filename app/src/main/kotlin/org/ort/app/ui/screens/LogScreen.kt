package org.ort.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.ort.app.ui.components.ColumnHeaderRow
import org.ort.app.ui.components.EmptyState
import org.ort.app.ui.components.FilterChip
import org.ort.app.ui.components.FilterChipRow
import org.ort.app.ui.components.GapRow
import org.ort.app.ui.components.LogGroupHeader
import org.ort.app.ui.components.LogRow
import org.ort.app.ui.components.LogRowViewState
import org.ort.app.ui.components.RejectedRow
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.data.LogEmptyStateViewState
import org.ort.app.ui.data.LogListItem
import org.ort.app.ui.data.LogQuickFilterId
import org.ort.app.ui.data.LogScreenViewState
import org.ort.app.ui.data.TransmissionListEntryViewState
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * The "Log" destination (R-040/R-041/R-042/R-043/R-045, ui-conformance WP5;
 * `design/canvas/Log.dc.html`, `Rows.dc.html`, `Log-Partial.dc.html`, `Log-Filter.dc.html`,
 * `Log-Empty.dc.html`, `Log-Rejected.dc.html`). A pure function of [LogScreenViewState] — every
 * fact (grouping, partials, badges, the rejected-focus view) is decided in
 * `ui/data/LogViewData.kt`, never here; this only lays the guide §6.5/§7 row family out.
 *
 * FR_UI_4 (this package's changed test, see `LogScreenTest`): every row renders through
 * [org.ort.app.ui.components.LogRow], whose marker slot is WP2's [org.ort.app.ui.components.AttributionRow]
 * shape — a score chip only ever beside INFERRED, never a number on CONFIRMED (guide §6.2). The
 * closed-set *state* (the marker shape) is what FR-UI-4 requires never be omitted; that is carried
 * on every row regardless of whether a confidence number is shown beside it.
 */
@Composable
public fun LogScreen(
    state: LogScreenViewState,
    onOpen: (String) -> Unit,
    onQuickFilterSelect: (LogQuickFilterId) -> Unit,
    onFilterClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(text = "Log", style = OrtType.screenTitle, modifier = Modifier.weight(1f))
            TextAction(text = "Filter", onClick = onFilterClick)
        }

        FilterChipRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm),
        ) {
            state.quickFilters.forEach { chip ->
                FilterChip(label = chip.label, selected = chip.selected, onClick = { onQuickFilterSelect(chip.id) })
            }
        }

        if (state.rejectedFocus) {
            state.rejectedExplanation?.let { explanation ->
                Text(
                    text = explanation,
                    style = OrtType.cardBody,
                    color = OrtColors.textDim,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.xs),
                )
            }
        }

        ColumnHeaderRow(
            stationLabel = if (state.rejectedFocus) "reason" else "station",
            signalLabel = if (state.rejectedFocus) "dur" else "sig",
        )

        val emptyState = state.emptyState
        if (emptyState != null) {
            EmptyState(
                message = emptyState.message,
                subMessage = emptyState.subMessage,
                modifier = Modifier.fillMaxWidth().padding(horizontal = OrtSpacing.lg),
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.items, key = { it.key }) { item -> LogListItemRow(item = item, onOpen = onOpen) }
        }
    }
}

/**
 * Compile-compatibility overload only. `OrtNavHost.kt` (WP3's file, not this package's — see
 * `LogContent.kt`'s doc comment) still calls this exact 3-argument shape from its own, still-inline
 * `LogContent` until WP3 deletes that copy and wires the nav host to this package's own
 * [org.ort.app.ui.screens.LogContent] instead — at which point this overload has no more callers
 * and WP3 or a follow-up may remove it. It renders through the real R-040 row family (never the
 * old two-free-width-column layout this destination shipped with before this change), just without
 * the grouping/partial/gap/rejected/filter facts that need [TransmissionDetail]'s richer data —
 * this legacy view-state does not carry those.
 */
@Composable
public fun LogScreen(
    entries: List<TransmissionListEntryViewState>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items: List<LogListItem> = entries.map { entry ->
        LogListItem.Row(
            LogRowViewState(
                id = entry.id,
                timeLabel = entry.timeLabel,
                frequencyLabel = entry.frequencyLabel,
                transcript = entry.transcriptText,
                attribution = entry.attribution,
                signalLabel = entry.signalLabel,
            ),
        )
    }
    val emptyState = if (entries.isEmpty()) {
        LogEmptyStateViewState("No transmissions yet", "The first one appears here the moment squelch opens.")
    } else {
        null
    }
    LogScreen(
        state = LogScreenViewState(
            items = items,
            quickFilters = emptyList(),
            rejectedFocus = false,
            rejectedExplanation = null,
            emptyState = emptyState,
        ),
        onOpen = onOpen,
        onQuickFilterSelect = {},
        onFilterClick = {},
        modifier = modifier,
    )
}

@Composable
private fun LogListItemRow(item: LogListItem, onOpen: (String) -> Unit) {
    when (item) {
        is LogListItem.Group -> LogGroupHeader(label = item.label)
        is LogListItem.Row -> LogRow(state = item.state, onClick = { onOpen(item.state.id) })
        is LogListItem.Gap -> GapRow(timeLabel = item.timeLabel, label = item.label)
        is LogListItem.RejectedItem -> RejectedRow(
            timeLabel = item.timeLabel,
            frequencyLabel = item.frequencyLabel,
            reason = item.reason,
            onClick = { onOpen(item.id) },
        )
    }
}
