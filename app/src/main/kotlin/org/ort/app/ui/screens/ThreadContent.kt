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
 * `ReaderDestination.THREADS` here. [onOpen] opens a transmission's own detail screen; [onOpenThread]
 * (R-017, default a no-op so `OrtNavHost.kt` compiles unchanged until it passes a real one) opens a
 * thread card into `ThreadDetailScreen` — WP3's own `ThreadDetailContent` wrapper (`OrtNavHost.kt`)
 * already polls [ThreadPolling.threadDetail] and renders it; this composable only needs to forward
 * the tap. [onOpen] has nowhere to go yet — a thread card opens the thread, not one transmission —
 * so it stays unused pending a per-over destination from this screen.
 */
@Suppress("UnusedParameter")
@Composable
public fun ThreadContent(
    context: Context,
    sessionId: String?,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenThread: (String) -> Unit = {},
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
    ThreadScreen(state = state, onOpenThread = onOpenThread, modifier = modifier)
}
