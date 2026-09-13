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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
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
 * Register (coordinator round, WPIMPROVE): [ImprovePage] used to live in a plain `remember`, so a
 * configuration change (font scale, rotation — anything without `android:configChanges` covering
 * it, which `ReaderActivity` does not declare) recreated the Activity and silently dropped
 * `Select`/`Running`/`Done` back to `Root` — mid-run, that hid a reprocess still in progress
 * (FR-REP-9/11: an operator watching progress must not be told, in effect, that nothing is
 * happening). The same `rememberSaveable` + hand-written [Saver] pattern this codebase already
 * uses for every other file-private sealed navigation type — `OrtNavHost.kt`'s own
 * `LogFilterOriginSaver`, `SessionsContent.kt`'s own `SessionsPageSaver` — since [ImprovePage] is
 * not itself Bundle-safe (`Done` carries a whole [ReprocessStatus.Summary]) and, being
 * file-private, cannot be reached by a test outside this file — the same reason those two Savers'
 * own tests each drive the flow through the real composed screens rather than the Saver directly.
 *
 * One flat, delimiter-joined `String`, matching [SessionsContent.kt]'s own encoding convention
 * exactly (never a second, nested `Saver` call for [ImproveGroupViewState]/[ReprocessStatus
 * .Summary] — a single [IMPROVE_PAGE_FIELD_SEPARATOR]-delimited split must find every field at one
 * flat depth) — [IMPROVE_PAGE_FIELD_SEPARATOR] is the same control character
 * (`PAGE_FIELD_SEPARATOR` in that file) no real headline/sub-line/reason prose in this app ever
 * produces. An unrecognised or truncated restored value falls back to [ImprovePage.Root], never a
 * crash — the same "restore never crashes" contract [LogFilterOriginSaver] documents.
 *
 * **What does not survive, disclosed rather than hidden:** [RealImproveRunner]'s own kdoc already
 * states the run is cooperatively cancelled the moment its collecting coroutine stops — which is
 * exactly what happens to the `LaunchedEffect` driving it when the old Activity is destroyed. So a
 * restored [ImprovePage.Running] shows the *board*, correctly, but [RunningPage]'s own fresh
 * `LaunchedEffect` starts a new run over the same [ImprovePage.Running.transmissionIds] from its
 * own zero — real progress for *that* run, never fabricated, but not a seamless continuation of
 * whatever fraction the interrupted run had reached. Resuming a genuinely in-flight run across a
 * process-surviving recreation would need the run itself to live somewhere longer-lived than a
 * composition (a foreground service, `WorkManager`, or similar) — a `:pipeline`/architecture change
 * outside this package's own file ownership, not attempted here.
 */
private const val IMPROVE_PAGE_FIELD_SEPARATOR = ""

private val ImprovePageSaver: Saver<ImprovePage, String> = Saver(
    save = { page ->
        when (page) {
            ImprovePage.Root -> "root"
            is ImprovePage.Select -> listOf(
                "select",
                page.group.id,
                page.group.headline,
                page.group.subLine,
                page.group.overCount.toString(),
                page.group.transmissionIds.joinToString(","),
                page.group.tierOrdinal.toString(),
            ).joinToString(IMPROVE_PAGE_FIELD_SEPARATOR)
            is ImprovePage.Running -> listOf(
                "running",
                page.transmissionIds.joinToString(","),
                page.headline,
            ).joinToString(IMPROVE_PAGE_FIELD_SEPARATOR)
            is ImprovePage.Done -> listOf(
                "done",
                page.headline,
                page.clearedCount.toString(),
                (page.summary != null).toString(),
                page.summary?.total?.toString() ?: "",
                page.summary?.transcriptsChanged?.toString() ?: "",
                page.summary?.attributionsChanged?.toString() ?: "",
                page.summary?.rejected?.toString() ?: "",
                page.summary?.failed?.toString() ?: "",
                page.summary?.correctedCount?.toString() ?: "",
                page.summary?.failureReasons?.joinToString(",") ?: "",
                page.summary?.changedTransmissionIds?.joinToString(",") ?: "",
            ).joinToString(IMPROVE_PAGE_FIELD_SEPARATOR)
        }
    },
    restore = { saved ->
        val parts = saved.split(IMPROVE_PAGE_FIELD_SEPARATOR)
        when (parts.getOrNull(0)) {
            "select" -> ImprovePage.Select(
                ImproveGroupViewState(
                    id = parts.getOrElse(1) { "" },
                    headline = parts.getOrElse(2) { "" },
                    subLine = parts.getOrElse(3) { "" },
                    overCount = parts.getOrElse(4) { "0" }.toIntOrNull() ?: 0,
                    transmissionIds = parts.getOrNull(5)?.takeIf { it.isNotEmpty() }?.split(",") ?: emptyList(),
                    tierOrdinal = parts.getOrElse(6) { "0" }.toIntOrNull() ?: 0,
                ),
            )
            "running" -> ImprovePage.Running(
                transmissionIds = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.split(",") ?: emptyList(),
                headline = parts.getOrElse(2) { "" },
            )
            "done" -> ImprovePage.Done(
                headline = parts.getOrElse(1) { "" },
                clearedCount = parts.getOrElse(2) { "0" }.toIntOrNull() ?: 0,
                summary = if (parts.getOrElse(3) { "false" }.toBoolean()) {
                    ReprocessStatus.Summary(
                        total = parts.getOrElse(4) { "0" }.toIntOrNull() ?: 0,
                        transcriptsChanged = parts.getOrElse(5) { "0" }.toIntOrNull() ?: 0,
                        attributionsChanged = parts.getOrElse(6) { "0" }.toIntOrNull() ?: 0,
                        rejected = parts.getOrElse(7) { "0" }.toIntOrNull() ?: 0,
                        failed = parts.getOrElse(8) { "0" }.toIntOrNull() ?: 0,
                        correctedCount = parts.getOrElse(9) { "0" }.toIntOrNull() ?: 0,
                        failureReasons = parts.getOrNull(10)?.takeIf { it.isNotEmpty() }?.split(",") ?: emptyList(),
                        changedTransmissionIds = parts.getOrNull(11)?.takeIf { it.isNotEmpty() }
                            ?.split(",")?.toSet() ?: emptySet(),
                    )
                } else {
                    null
                },
            )
            else -> ImprovePage.Root // an unrecognised/truncated saved value never crashes restore.
        }
    },
)

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
 *
 * [onOpenModels] (R-350, register): `Improve-Done`'s `Install` action (shown only when the real
 * failure reasons name a missing model) needs `Settings-Assets` — the same destination R-139's
 * "Install a model" already reaches, via `initialScreen` — which this package cannot resolve on
 * its own (no drill-in of that shape exists inside `ui/improve`, and `OrtNavHost.kt` is outside
 * this round's file ownership). Defaults to a no-op so every existing caller keeps compiling
 * unchanged; the host is expected to wire it the same way it wires `NowScreen`'s own
 * `onOpenModels` (`OrtNavHost.kt`'s `NavHostCallbacks.onOpenModels`, `navigator.openSettings
 * (SettingsScreenId.ASSETS)`).
 */
@Composable
public fun ImproveContent(
    context: Context,
    onDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenModels: () -> Unit = {},
    // R-1041 (R04, `LogFilterOrigin.Improve`): `Improve-Done`'s own "Review the N changes" —
    // needs the Log, which this package cannot reach on its own (no drill-in of that shape exists
    // inside `ui/improve`, and `OrtNavHost.kt` is outside this round's file ownership) — the same
    // reason [onOpenModels] above is already a callback rather than a direct navigation. Defaulted
    // to a no-op so every existing caller keeps compiling unchanged; the host is expected to wire
    // it the same way it wires [onOpenModels].
    onOpenChangedOvers: (Set<String>) -> Unit = {},
) {
    val runner = remember { RealImproveRunner(context) }
    var page by rememberSaveable(stateSaver = ImprovePageSaver) { mutableStateOf<ImprovePage>(ImprovePage.Root) }
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
            onInstallModel = onOpenModels,
            onReviewChanges = onOpenChangedOvers,
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
