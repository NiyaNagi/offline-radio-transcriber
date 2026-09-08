package org.ort.app.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import org.ort.app.ui.data.LogFilterSelection
import org.ort.app.ui.data.LogFilterSheetViewState
import org.ort.app.ui.data.LogPolling
import org.ort.app.ui.data.LogQuickFilterId
import org.ort.core.AttributionState

private const val POLL_INTERVAL_MILLIS = 2_000L

/**
 * R-129 (halt — audit V3 @3e2d4ee): [LogQuickFilterId] is a plain `sealed interface`, which
 * Compose's default `Saver` cannot handle — under a real `SaveableStateRegistry` (any real
 * Activity; Robolectric's `createComposeRule` installs none, which is why this was never caught by
 * a Robolectric test) the *first* composition throws `IllegalArgumentException: MutableState
 * containing All cannot be saved…`, crashing the process before Log ever renders. Encoded as a
 * plain string key — `"All"`/`"Named"`/`"Rejected"`/`"Frequency:<hz>"` — rather than an `Int`
 * ordinal, so a value written by one build survives a later build that inserts a new case.
 */
private val LogQuickFilterIdSaver: Saver<LogQuickFilterId, String> = Saver(
    save = { id ->
        when (id) {
            LogQuickFilterId.All -> "All"
            LogQuickFilterId.Named -> "Named"
            LogQuickFilterId.Rejected -> "Rejected"
            is LogQuickFilterId.Frequency -> "Frequency:${id.hz}"
        }
    },
    restore = { encoded ->
        when {
            encoded == "All" -> LogQuickFilterId.All
            encoded == "Named" -> LogQuickFilterId.Named
            encoded == "Rejected" -> LogQuickFilterId.Rejected
            encoded.startsWith("Frequency:") ->
                encoded.removePrefix("Frequency:").toLongOrNull()?.let { LogQuickFilterId.Frequency(it) }
                    ?: LogQuickFilterId.All
            else -> LogQuickFilterId.All // an unrecognised saved value never crashes restore — falls back to All.
        }
    },
)

/** The delimiter [LogFilterSelectionSaver] joins [LogFilterSelection.attributionStates] names on. */
private const val ATTRIBUTION_STATES_DELIMITER = ","

/**
 * R-129: [LogFilterSelection] is a plain data class too — same crash class as [LogQuickFilterId],
 * for the same real-`SaveableStateRegistry` reason. `listSaver` fits it directly since every field
 * is already a Bundle-primitive (`Long?`, `Boolean`) or a small enum set, joined into one string.
 */
private val LogFilterSelectionSaver: Saver<LogFilterSelection, Any> = listSaver(
    save = { selection: LogFilterSelection ->
        listOf(
            selection.frequencyHz,
            selection.attributionStates.joinToString(ATTRIBUTION_STATES_DELIMITER) { it.name },
            selection.showRejected,
            selection.showGaps,
            selection.fromMillis,
            selection.toMillis,
        )
    },
    restore = { saved: List<Any?> ->
        val statesField = saved[1] as String
        val states = if (statesField.isEmpty()) {
            emptySet()
        } else {
            statesField.split(ATTRIBUTION_STATES_DELIMITER).mapNotNull { name ->
                runCatching { AttributionState.valueOf(name) }.getOrNull()
            }.toSet()
        }
        LogFilterSelection(
            frequencyHz = saved[0] as Long?,
            attributionStates = states,
            showRejected = saved[2] as Boolean,
            showGaps = saved[3] as Boolean,
            fromMillis = saved[4] as Long?,
            toMillis = saved[5] as Long?,
        )
    },
)

/**
 * The Log destination's polling content composable (R-040..R-045, ui-conformance WP5) —
 * `ui-conformance-plan.md`'s nav-host rule: WP3's `OrtNavHost` dispatches `ReaderDestination.LOG`
 * here. Session-tied polling and the filter sheet's open/close/selection state live here, off the
 * pure [LogScreen]/[LogFilterSheet] composables — neither of those takes a [Context].
 *
 * [onOpenThread] (R-017, default a no-op so `OrtNavHost.kt` compiles unchanged until it passes a
 * real one) opens the QSO a [org.ort.app.ui.data.LogListItem.Group] header names — `Rows.dc.html`:
 * "Tapping opens the thread."
 */
@Composable
public fun LogContent(
    context: Context,
    sessionId: String?,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenThread: (String) -> Unit = {},
) {
    // R-247: `sessionId == null` (no session has ever started) never polls below, so this initial
    // value is the screen's actual, final state for that case — a real, fully-drawn empty Log
    // (headline, sentence, quick-filter chips), not the blank body a `null` placeholder left
    // behind. Once a session exists the first poll below replaces it immediately.
    var screenState by remember(sessionId) { mutableStateOf(LogPolling.noSessionState()) }
    var quickFilter by rememberSaveable(stateSaver = LogQuickFilterIdSaver) {
        mutableStateOf<LogQuickFilterId>(LogQuickFilterId.All)
    }
    var selection by rememberSaveable(stateSaver = LogFilterSelectionSaver) { mutableStateOf(LogFilterSelection()) }
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
            onOpenThread = onOpenThread,
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
