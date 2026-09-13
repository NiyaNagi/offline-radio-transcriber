package org.ort.app.ui.recordings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/**
 * `Recordings.dc.html` (RC01): the stateful entry point `OrtNavHost` dispatches the `RECORDINGS`
 * destination to — polls [RecordingsPolling.state] on composition and whenever the chip selection
 * changes, following [org.ort.app.ui.digest.SessionsContent]'s own idiom (a `LaunchedEffect` keyed
 * on what changed, a plain `remember`-held view-state, no caching beyond one composition).
 *
 * R-1051 (register: initial state is not empty state): [state] starts `null` and
 * [RecordingsScreen] renders its own distinct loading row for exactly that case — never the "no
 * recordings match this filter" empty state before the first real query has returned.
 *
 * [onOpenSession] is a plain callback, not internal navigation state — `Recording-Session.dc.html`
 * (RC02) is a separate build unit; a caller that has not wired it yet may pass a no-op and this
 * composable still renders and behaves correctly on its own.
 *
 * [onOpenOverAudioBudget] is likewise a plain callback (real cross-package navigation, to
 * `Settings-Storage`/CF03 — this package has no drill-in surface of its own to render that on).
 * Turning the archive on/off is **not** an external callback: it is a same-package settings write
 * ([RecordingsPolling.setArchiveEnabled]) followed by a re-poll, entirely internal to this
 * composable — a caller has nothing to wire for it.
 */
@Composable
public fun RecordingsContent(
    context: Context,
    onDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSession: (String) -> Unit = {},
    onOpenOverAudioBudget: () -> Unit = {},
) {
    var selectedFilter by rememberSaveable { mutableStateOf(RecordingsFilter.ALL) }
    var state by remember { mutableStateOf<RecordingsViewState?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(selectedFilter, reloadKey) {
        state = RecordingsPolling.state(context, selectedFilter)
    }
    RecordingsScreen(
        state = state,
        onDrawer = onDrawer,
        onSelectFilter = { selectedFilter = it },
        onOpenSession = onOpenSession,
        onOpenOverAudioBudget = onOpenOverAudioBudget,
        onTurnOffArchive = {
            val currentlyEnabled = state?.budgets?.archive?.enabled ?: true
            scope.launch {
                RecordingsPolling.setArchiveEnabled(context, !currentlyEnabled)
                reloadKey++
            }
        },
        modifier = modifier,
    )
}
