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

private sealed interface ImprovePage {
    data object Root : ImprovePage
    data class Select(val group: ImproveGroupViewState) : ImprovePage
    data class Running(val transmissionIds: List<String>, val headline: String) : ImprovePage
    data class Done(val headline: String, val clearedCount: Int) : ImprovePage
}

/**
 * R-091 (register): the stateful entry point `OrtNavHost` dispatches `IMPROVE_RECORDS` to — owns
 * navigation across `Improve` → `Improve-Select` → `Improve-Running` → `Improve-Done`
 * (`Flow-Improve.dc.html`) as local state, the same pattern [org.ort.app.ui.settings.SettingsContent]
 * uses for its own sub-screens.
 */
@Composable
public fun ImproveContent(context: Context, onDrawer: () -> Unit, modifier: Modifier = Modifier) {
    val runner = remember { FakeImproveRunner(context) }
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

        is ImprovePage.Running -> {
            var done by remember(current.transmissionIds) { mutableStateOf(0) }
            var paused by remember(current.transmissionIds) { mutableStateOf(false) }
            var job by remember(current.transmissionIds) { mutableStateOf<Job?>(null) }
            val total = current.transmissionIds.size

            LaunchedEffect(current.transmissionIds) {
                job = launch {
                    // A cold flow's `emit` suspends until this block returns, so pausing here
                    // genuinely pauses `FakeImproveRunner`'s work, not just this screen's display.
                    runner.run(current.transmissionIds).collect { progress ->
                        while (paused) delay(120L)
                        done = progress.done
                    }
                    refreshToken++
                    page = ImprovePage.Done(current.headline, done)
                }
            }

            ImproveRunningScreen(
                state = ImproveRunningViewState(
                    headline = current.headline,
                    doneCount = done,
                    totalCount = total,
                    paused = paused,
                ),
                onPause = { paused = !paused },
                onCancel = {
                    job?.cancel()
                    refreshToken++
                    page = ImprovePage.Done(current.headline, done)
                },
                modifier = modifier,
            )
        }

        is ImprovePage.Done -> ImproveDoneScreen(
            state = ImproveDoneViewState(headline = current.headline, clearedCount = current.clearedCount),
            onDone = { page = ImprovePage.Root },
            modifier = modifier,
        )
    }
}

@Composable
private fun Loading(modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(OrtSpacing.lg)) {
        Text(text = "Loading…", modifier = Modifier.semantics { contentDescription = "Loading improve records" })
    }
}
