package org.ort.app.ui.improve

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ort.app.ui.theme.OrtSpacing
import org.ort.pipeline.reprocess.ReprocessStatus

private sealed interface ImprovePage {
    data object Root : ImprovePage
    data class Select(val group: ImproveGroupViewState) : ImprovePage
    data class Running(val transmissionIds: List<String>, val headline: String) : ImprovePage
    data class Done(val headline: String, val clearedCount: Int, val summary: ReprocessStatus.Summary?) : ImprovePage
}

/**
 * R-091 (register): the stateful entry point `OrtNavHost` dispatches `IMPROVE_RECORDS` to — owns
 * navigation across `Improve` → `Improve-Select` → `Improve-Running` → `Improve-Done`
 * (`Flow-Improve.dc.html`) as local state, the same pattern [org.ort.app.ui.settings.SettingsContent]
 * uses for its own sub-screens.
 *
 * WP11d addendum (round 5): drives [RealImproveRunner] now that `:pipeline`'s reprocessing engine
 * exists — [FakeImproveRunner] stays only for tests and the debug scenario simulator (neither goes
 * through this composable). `ReprocessStatus.state` (a process-wide holder [ReprocessRunner]
 * publishes to as a side effect of the very `Flow` [runner]`.run` returns) is polled separately
 * while [ImprovePage.Running] is showing, both for the engine's own capture-priority auto-pause
 * (FR-REP-6) and for the real `Summary` [ImprovePage.Done] renders (R-143).
 */
@Composable
public fun ImproveContent(context: Context, onDrawer: () -> Unit, modifier: Modifier = Modifier) {
    val runner = remember { RealImproveRunner(context) }
    var page by remember { mutableStateOf<ImprovePage>(ImprovePage.Root) }
    var root by remember { mutableStateOf<ImproveRootViewState?>(null) }
    var refreshToken by remember { mutableStateOf(0) }

    LaunchedEffect(refreshToken) { root = ImprovePolling.root(context) }

    when (val current = page) {
        is ImprovePage.Root -> {
            val state = root
            if (state != null) {
                ImproveScreen(
                    state = state,
                    onDrawer = onDrawer,
                    onImproveAll = {
                        page = ImprovePage.Running(state.allTransmissionIds, "All groups")
                    },
                    onOpenGroup = { group -> page = ImprovePage.Select(group) },
                    modifier = modifier,
                )
            } else {
                Loading(modifier)
            }
        }

        is ImprovePage.Select -> {
            var selectState by remember(current.group.id) { mutableStateOf<ImproveSelectViewState?>(null) }
            LaunchedEffect(current.group.id) { selectState = ImprovePolling.select(context, current.group) }
            val state = selectState
            if (state != null) {
                ImproveSelectScreen(
                    state = state,
                    onBack = { page = ImprovePage.Root },
                    onStart = { page = ImprovePage.Running(current.group.transmissionIds, current.group.headline) },
                    modifier = modifier,
                )
            } else {
                Loading(modifier)
            }
        }

        is ImprovePage.Running -> RunningPage(
            current = current,
            runner = runner,
            modifier = modifier,
            onDone = { headline, done, summary ->
                refreshToken++
                page = ImprovePage.Done(headline, done, summary)
            },
        )

        is ImprovePage.Done -> ImproveDoneScreen(
            state = ImproveDoneViewState(
                headline = current.headline,
                clearedCount = current.clearedCount,
                summary = current.summary,
            ),
            onDone = { page = ImprovePage.Root },
            modifier = modifier,
        )
    }
}

/**
 * [ImprovePage.Running]'s own body, split out of [ImproveContent] purely to keep that function
 * under detekt's length limit. [onDone] takes `(headline, doneCount, summary)` rather than
 * building an [ImprovePage.Done] itself — that type is `private` to the caller's file scope, and
 * the token-bump `ImproveContent` does alongside it stays there, one call site, not duplicated for
 * both the natural-completion and Cancel exits below.
 */
@Composable
private fun RunningPage(
    current: ImprovePage.Running,
    runner: ImproveRunner,
    modifier: Modifier,
    onDone: (headline: String, doneCount: Int, summary: ReprocessStatus.Summary?) -> Unit,
) {
    var done by remember(current.transmissionIds) { mutableStateOf(0) }
    var paused by remember(current.transmissionIds) { mutableStateOf(false) }
    var autoPausedReason by remember(current.transmissionIds) { mutableStateOf<String?>(null) }
    var job by remember(current.transmissionIds) { mutableStateOf<Job?>(null) }
    val total = current.transmissionIds.size

    LaunchedEffect(current.transmissionIds) {
        job = launch {
            // A cold flow's `emit` suspends until this block returns, so pausing here genuinely
            // pauses the run, not just this screen's display.
            runner.run(current.transmissionIds).collect { progress ->
                while (paused) delay(120L)
                done = progress.done
            }
            val summary = (ReprocessStatus.state as? ReprocessStatus.State.Done)?.summary
            onDone(current.headline, done, summary)
        }
    }
    // FR-REP-6: the engine's own capture-priority yield, read separately from the collector above
    // so it updates even while that coroutine is itself parked in the `while (paused) delay(...)`
    // loop above — see ImproveViewData.kt's own doc comment.
    LaunchedEffect(current.transmissionIds) {
        while (true) {
            autoPausedReason = if (ReprocessStatus.state is ReprocessStatus.State.Paused) {
                "waiting — capture is busy"
            } else {
                null
            }
            delay(AUTO_PAUSE_POLL_INTERVAL_MILLIS)
        }
    }

    ImproveRunningScreen(
        state = ImproveRunningViewState(
            headline = current.headline,
            doneCount = done,
            totalCount = total,
            paused = paused,
            autoPausedReason = autoPausedReason,
        ),
        onPause = { paused = !paused },
        onCancel = {
            job?.cancel()
            onDone(current.headline, done, null)
        },
        modifier = modifier,
    )
}

@Composable
private fun Loading(modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(OrtSpacing.lg)) {
        Text(text = "Loading…", modifier = Modifier.semantics { contentDescription = "Loading improve records" })
    }
}

private const val AUTO_PAUSE_POLL_INTERVAL_MILLIS = 200L
