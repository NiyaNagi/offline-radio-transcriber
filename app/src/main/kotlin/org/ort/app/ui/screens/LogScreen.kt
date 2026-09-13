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
import androidx.compose.ui.platform.testTag
import org.ort.app.ui.components.ColumnHeaderRow
import org.ort.app.ui.components.EmptyState
import org.ort.app.ui.components.FilterChip
import org.ort.app.ui.components.FilterChipRow
import org.ort.app.ui.components.GapRow
import org.ort.app.ui.components.LoadingState
import org.ort.app.ui.components.LogGroupHeader
import org.ort.app.ui.components.LogRow
import org.ort.app.ui.components.RejectedRow
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.data.LogListItem
import org.ort.app.ui.data.LogQuickFilterId
import org.ort.app.ui.data.LogScreenViewState
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
    onOpenThread: (String) -> Unit = {},
    // R-1047 (register): clears [LogScreenViewState.appliedFilterLabel]'s own statement line,
    // resetting to the plain, unfiltered `All` view — never called when that label is `null` (no
    // statement to clear). Defaulted to a no-op so every existing caller keeps compiling unchanged.
    onClearAppliedFilter: () -> Unit = {},
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

        // R-1047 (register, sent back): a dedicated, full-width line between the chip row and the
        // column heads — never *inside* the scrollable `FilterChipRow` above, which is exactly what
        // the register's own device capture (`overnight/L01-log-filtered-overs.png`) found: a chip
        // appended after five real quick chips clips off the visible edge at both 390dp and 480dp,
        // so the "visible, specific statement" this row exists to give the operator was only
        // reachable by a horizontal scroll nobody has a reason to make (constitution I). No `Log`
        // filtered-state artboard exists in `design/canvas` to follow instead (checked directly:
        // `Log.dc.html`/`Log-Filter.dc.html`/`Log-Partial.dc.html`/`Log-Rejected.dc.html`/
        // `Log-Empty.dc.html` are the whole set, none of them a filtered-*result* state) — this
        // reuses the established full-width-line-below-the-chip-row shape immediately below
        // ([state.rejectedExplanation]'s own identical position, for the dedicated Rejected view),
        // rather than inventing a second, competing pattern for the same "one more fact about what
        // this Log shows" job. `fillMaxWidth`, never sized to its own content, so it can never be
        // itself scrolled out of a viewport the way a chip-row member can.
        state.appliedFilterLabel?.let { label ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.xs)
                    .testTag("log-applied-filter-statement"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Filtered to $label",
                    style = OrtType.cardBody,
                    color = OrtColors.textDim,
                    modifier = Modifier.weight(1f),
                )
                TextAction(text = "Clear", onClick = onClearAppliedFilter)
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

        // Register R-1051 (halt): the screen's own real "not yet known" value — checked ahead of
        // the column headers and the empty state below, never rendered alongside either (see
        // `LogPolling.loadingState`'s own kdoc for why this is a distinct field, not a third
        // meaning folded into `emptyState`).
        if (state.loading) {
            LoadingState(
                message = "Loading the log…",
                modifier = Modifier.fillMaxWidth().padding(horizontal = OrtSpacing.lg),
            )
            return@Column
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
            BluetoothAudioFootnote(state.bluetoothAudioFootnote)
            return@Column
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(state.items, key = { it.key }) { item ->
                LogListItemRow(
                    item = item,
                    onOpen = onOpen,
                    onOpenThread = onOpenThread,
                    showWhy = state.rejectedFocus,
                )
            }
        }

        BluetoothAudioFootnote(state.bluetoothAudioFootnote)
    }
}

/** E2-G04 (F23, FR-CAP-13, `Fail-Bluetooth-Audio.dc.html`'s own footnote): present exactly when
 * this session's audio route is Bluetooth SCO — never the only copy of the fact (the row mark,
 * N04's Input sub-line and DG04's own facts all say so too). */
@Composable
private fun BluetoothAudioFootnote(footnote: String?) {
    footnote?.let {
        Text(
            text = it,
            style = OrtType.subLine,
            color = OrtColors.textDim,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)
                .testTag("log-bluetooth-audio-footnote"),
        )
    }
}

@Composable
private fun LogListItemRow(
    item: LogListItem,
    onOpen: (String) -> Unit,
    onOpenThread: (String) -> Unit,
    showWhy: Boolean,
) {
    when (item) {
        is LogListItem.Group -> LogGroupHeader(label = item.label, onClick = { onOpenThread(item.threadId) })
        is LogListItem.Row -> LogRow(state = item.state, onClick = { onOpen(item.state.id) })
        is LogListItem.Gap -> GapRow(
            timeLabel = item.timeLabel,
            label = item.label,
            bluetoothAudioDropped = item.bluetoothAudioDropped,
        )
        is LogListItem.RejectedItem -> RejectedRow(
            timeLabel = item.timeLabel,
            frequencyLabel = item.frequencyLabel,
            reason = item.reason,
            onClick = { onOpen(item.id) },
            // R-043 (`Log-Rejected.dc.html`'s `.why` line): only the dedicated Rejected view shows
            // it — a rejected row merely interleaved into the normal chronological list (the
            // sheet's "Also show" toggle) stays the compact one-line row it always was.
            why = if (showWhy) item.why else null,
            // R-242 (wired now that main carries `RejectedRow(durationLabel)`, WP2): the DUR
            // column only exists on the dedicated Rejected view's header (`ColumnHeaderRow`'s own
            // `signalLabel = "dur"` branch below) — the interleaved row stays exactly as compact
            // as it always was, same gating as `why` above.
            durationLabel = if (showWhy) item.durationLabel else null,
        )
    }
}
