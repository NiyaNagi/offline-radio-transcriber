package org.ort.app.ui.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import org.ort.app.ui.data.ThreadListViewState
import org.ort.app.ui.data.ThreadPolling

private const val POLL_INTERVAL_MILLIS = 2_000L

/**
 * The Threads destination's polling content composable (R-044, ui-conformance WP5) —
 * `ui-conformance-plan.md`'s nav-host rule: WP3's `OrtNavHost` dispatches
 * `ReaderDestination.THREADS` here once it removes its own inline copy; this file owns that
 * dispatch target. [onOpen] opens a transmission's own detail screen — [ThreadDetailScreen]'s own
 * drill-in is not yet wired into navigation (see that screen's doc comment for why); this content
 * composable renders [ThreadScreen] only. [onOpen] is required by this file's own row in
 * `spec/ui-conformance-plan.md` (`ThreadContent(context, sessionId, onOpen, modifier)`, matching
 * [org.ort.app.ui.screens.LogContent]'s shape) but genuinely has nowhere to go yet — see the
 * previous paragraph — so it is intentionally unused until a route exists.
 */
@Suppress("UnusedParameter")
@Composable
public fun ThreadContent(
    context: Context,
    sessionId: String?,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var state by remember { mutableStateOf<ThreadListViewState>(ThreadListViewState.Empty) }
    if (sessionId != null) {
        LaunchedEffect(sessionId) {
            while (true) {
                state = ThreadPolling.currentThreadListState(context, sessionId)
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }
    ThreadScreen(state = state, onOpenThread = {}, modifier = modifier)
}
