package org.ort.app.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import org.ort.app.ui.data.LogFilterSelection
import org.ort.app.ui.data.LogFilterSheetViewState
import org.ort.app.ui.data.LogPolling
import org.ort.app.ui.data.LogQuickFilterId
import org.ort.app.ui.data.LogScreenViewState

private const val POLL_INTERVAL_MILLIS = 2_000L

private val EMPTY_LOG_SCREEN_STATE = LogScreenViewState(
    items = emptyList(),
    quickFilters = emptyList(),
    rejectedFocus = false,
    rejectedExplanation = null,
    emptyState = null,
)

/**
 * The Log destination's polling content composable (R-040..R-045, ui-conformance WP5) —
 * `ui-conformance-plan.md`'s nav-host rule: WP3's `OrtNavHost` dispatches `ReaderDestination.LOG`
 * here once it removes its own inline copy; this file owns that dispatch target. Session-tied
 * polling and the filter sheet's open/close/selection state live here, off the pure [LogScreen]/
 * [LogFilterSheet] composables — neither of those takes a [Context].
 */
@Composable
public fun LogContent(context: Context, sessionId: String?, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    var screenState by remember { mutableStateOf(EMPTY_LOG_SCREEN_STATE) }
    var quickFilter by rememberSaveable { mutableStateOf<LogQuickFilterId>(LogQuickFilterId.All) }
    var selection by remember { mutableStateOf(LogFilterSelection()) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var sheetState by remember { mutableStateOf<LogFilterSheetViewState?>(null) }

    if (sessionId != null) {
        LaunchedEffect(sessionId, quickFilter, selection) {
            while (true) {
                screenState = LogPolling.screenState(context, sessionId, selection, quickFilter)
                delay(POLL_INTERVAL_MILLIS)
            }
        }
        LaunchedEffect(sessionId, selection, sheetOpen) {
            if (sheetOpen) sheetState = LogPolling.filterSheetState(context, sessionId, selection)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LogScreen(
            state = screenState,
            onOpen = onOpen,
            onQuickFilterSelect = { id -> quickFilter = id },
            onFilterClick = { sheetOpen = true },
        )

        val currentSheetState = sheetState
        if (sheetOpen && currentSheetState != null) {
            LogFilterSheet(
                state = currentSheetState,
                onFrequencySelect = { hz -> selection = selection.copy(frequencyHz = hz) },
                onAttributionToggle = { state, checked ->
                    val current = selection.attributionStates
                    selection = selection.copy(attributionStates = if (checked) current + state else current - state)
                },
                onShowRejectedToggle = { checked -> selection = selection.copy(showRejected = checked) },
                onShowGapsToggle = { checked -> selection = selection.copy(showGaps = checked) },
                onClearAll = { selection = LogFilterSelection() },
                onShow = { sheetOpen = false },
            )
        }
    }
}
