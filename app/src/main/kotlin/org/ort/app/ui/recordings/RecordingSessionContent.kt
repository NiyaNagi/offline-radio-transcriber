package org.ort.app.ui.recordings

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.ort.app.ui.audio.PlaybackOutcome
import org.ort.app.ui.audio.TransmissionAudioPlayer
import org.ort.app.ui.audio.TransportPlaybackController

/**
 * `Recording-Session.dc.html` (RC02): the stateful entry point `OrtNavHost` dispatches an opened
 * recording session to — polls [RecordingSessionPolling.state] on composition and whenever
 * [sessionId] changes, the delete/export/label sheets' own actions change something real, or the
 * transport controller's own loaded transmission changes (the coverage strip's playhead is real
 * only then — see [RecordingSessionMapperInput.playingTransmissionId]'s own doc comment).
 *
 * [player] is the single hoisted [TransmissionAudioPlayer] `OrtNavHost` already owns — playing a
 * row here shows it in the transport bar and keeps playing when this screen is left (C10), the
 * identical shape `TransmissionDetailContent`'s own `PlaybackSection` already establishes; this
 * composable never constructs a second player.
 *
 * [onOpenLog]: IA-3's own shape, generalised — `LogFilterSelection` carries no session-scoped
 * filter today (searched before writing this), so this hands the caller (`OrtNavHost`) this
 * session's own real over ids to filter the Log to, `transmissionIds`, the same route
 * `Detail-Propagated.dc.html`'s "View the N affected overs" already uses. **Register R-1055
 * dependency**: a Log opened this way for a session other than the live one currently renders "No
 * overs yet" (WPINIT's own fix, elsewhere) — reported, not fixed here.
 */
@Composable
public fun RecordingSessionContent(
    context: Context,
    sessionId: String,
    player: TransmissionAudioPlayer,
    onBack: () -> Unit,
    onOpenTransmission: (String) -> Unit,
    onOpenStation: (String) -> Unit,
    onOpenLog: (sessionId: String, overIds: Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val controller = player as? TransportPlaybackController
    val scope = rememberCoroutineScope()

    var state by remember(sessionId) { mutableStateOf<RecordingSessionViewState?>(null) }
    var reloadKey by remember(sessionId) { mutableStateOf(0) }
    val playingTransmissionId = controller?.loadedTransmissionId
    LaunchedEffect(sessionId, reloadKey, playingTransmissionId) {
        state = RecordingSessionPolling.state(context, sessionId, playingTransmissionId)
    }

    var deleteSheet by remember(sessionId) {
        mutableStateOf<RecordingSessionDeleteState>(RecordingSessionDeleteState.Idle)
    }
    var exportSheet by remember(sessionId) {
        mutableStateOf<RecordingSessionExportState>(RecordingSessionExportState.Idle)
    }
    var labelSheet by remember(sessionId) { mutableStateOf<RecordingSessionLabelSheetViewState?>(null) }

    val exportLauncher = rememberExportLauncher(context, sessionId, scope, { exportSheet }) { exportSheet = it }
    val playback = playbackActions(scope, controller, { state })
    val delete = deleteSheetActions(context, sessionId, scope, { deleteSheet }, { deleteSheet = it }) { reloadKey++ }
    val export = exportSheetActions(context, sessionId, scope, { exportSheet }, { exportSheet = it }, exportLauncher)
    val label = labelSheetActions(context, scope, { state }, { labelSheet }) { labelSheet = it }

    RecordingSessionScreen(
        state = state,
        playingOverId = playingTransmissionId,
        isPlaying = controller?.isPlayingState ?: false,
        deleteSheet = deleteSheet,
        exportSheet = exportSheet,
        labelSheet = labelSheet,
        actions = RecordingSessionActions(
            onBack = onBack,
            onOpenLog = { onOpenLog(sessionId, overIdsOf(state)) },
            onPlayAll = playback.onPlayAll,
            onPlayRow = playback.onPlayRow,
            onOpenTransmission = onOpenTransmission,
            onOpenStation = onOpenStation,
            onRetry = { overId ->
                scope.launch {
                    RecordingSessionPolling.retry(context, overId)
                    reloadKey++
                }
            },
            onOpenLabelSheet = label.onOpen,
            onCloseLabelSheet = {
                labelSheet = null
                reloadKey++
            },
            onSetMarkedForTraining = label.onSetMarkedForTraining,
            onSetRating = label.onSetRating,
            onOpenDeleteSheet = delete.onOpen,
            onCloseDeleteSheet = delete.onClose,
            onConfirmDelete = delete.onConfirm,
            onOpenExportSheet = export.onOpen,
            onCloseExportSheet = export.onClose,
            onConfirmExport = export.onConfirm,
        ),
        modifier = modifier,
    )
}

private fun overIdsOf(state: RecordingSessionViewState?): Set<String> =
    state?.rows.orEmpty().filterIsInstance<RecordingSessionRow.Over>().map { it.id }.toSet()

// ---------------------------------------------------------------------------------------------
// Action builders — each pulled out to its own small function purely to keep
// [RecordingSessionContent] under detekt's `LongMethod` limit (the same "extract the duplication
// / extract the branch" reason this codebase's own `navHostCallbacks`/`ImproveRecordsContent`
// already exist for). Every lambda here still closes over the same live Compose state
// [RecordingSessionContent] itself owns, passed in as a plain getter/setter pair — none of this
// introduces a second source of truth.
// ---------------------------------------------------------------------------------------------

private data class PlaybackActions(val onPlayAll: () -> Unit, val onPlayRow: (String) -> Unit)

private fun playbackActions(
    scope: CoroutineScope,
    controller: TransportPlaybackController?,
    state: () -> RecordingSessionViewState?,
): PlaybackActions {
    fun overs() = state()?.rows.orEmpty().filterIsInstance<RecordingSessionRow.Over>()
    return PlaybackActions(
        onPlayAll = {
            val first = overs().firstOrNull { it.hasAudio }
            if (first != null && controller != null) playOver(scope, controller, first)
        },
        onPlayRow = { overId ->
            val row = overs().firstOrNull { it.id == overId }
            if (row != null && controller != null) {
                if (controller.loadedTransmissionId == overId) {
                    if (controller.isPlayingState) controller.pause() else controller.resume()
                } else {
                    playOver(scope, controller, row)
                }
            }
        },
    )
}

/** Starts [row]'s audio on [controller] and, on success, tells the transport bar its callsign and
 * duration right away — the identical pairing `TransmissionDetailScreen`'s own `togglePlayback`
 * already establishes for [TransportPlaybackController.setNowPlayingMeta] (that composable's own
 * doc comment explains why [TransmissionAudioPlayer.play] alone cannot carry either fact). */
private fun playOver(scope: CoroutineScope, controller: TransportPlaybackController, row: RecordingSessionRow.Over) {
    scope.launch {
        val outcome = controller.play(row.id)
        if (outcome is PlaybackOutcome.Played) {
            controller.setNowPlayingMeta(row.id, row.callsign, parseDurationSeconds(row.durationLabel))
        }
    }
}

/** [RecordingSessionRow.Over.durationLabel] is `org.ort.app.ui.data.ReaderTransmissionViewStateMapper
 * .durationLabel`'s own prose ("3.4 s" / "1 m 55 s") — parsed back to seconds only for
 * [TransportPlaybackController.setNowPlayingMeta]'s own numeric parameter, never asserted on by a
 * test (constitution II: assertions never depend on prose). A shape this cannot parse yields `0.0`,
 * matching [TransportPlaybackController]'s own pre-fill default. */
private fun parseDurationSeconds(label: String): Double {
    val minutesMatch = Regex("""(\d+) m (\d+) s""").find(label)
    if (minutesMatch != null) {
        val (minutes, seconds) = minutesMatch.destructured
        return minutes.toDouble() * 60 + seconds.toDouble()
    }
    return label.removeSuffix(" s").toDoubleOrNull() ?: 0.0
}

private data class DeleteActions(val onOpen: () -> Unit, val onClose: () -> Unit, val onConfirm: () -> Unit)

private fun deleteSheetActions(
    context: Context,
    sessionId: String,
    scope: CoroutineScope,
    sheet: () -> RecordingSessionDeleteState,
    setSheet: (RecordingSessionDeleteState) -> Unit,
    onReload: () -> Unit,
): DeleteActions = DeleteActions(
    onOpen = { scope.launch { setSheet(RecordingSessionPolling.deletePreview(context, sessionId)) } },
    onClose = {
        val wasDeleted = sheet() is RecordingSessionDeleteState.Deleted
        setSheet(RecordingSessionDeleteState.Idle)
        if (wasDeleted) onReload()
    },
    onConfirm = { scope.launch { setSheet(RecordingSessionPolling.delete(context, sessionId)) } },
)

private data class ExportActions(val onOpen: () -> Unit, val onClose: () -> Unit, val onConfirm: () -> Unit)

private fun exportSheetActions(
    context: Context,
    sessionId: String,
    scope: CoroutineScope,
    sheet: () -> RecordingSessionExportState,
    setSheet: (RecordingSessionExportState) -> Unit,
    launcher: androidx.activity.result.ActivityResultLauncher<String>,
): ExportActions = ExportActions(
    onOpen = { scope.launch { setSheet(RecordingSessionPolling.exportPreview(context, sessionId)) } },
    onClose = { setSheet(RecordingSessionExportState.Idle) },
    onConfirm = {
        (sheet() as? RecordingSessionExportState.Preview)?.let { preview -> launcher.launch(preview.suggestedFileName) }
    },
)

/** The Storage Access Framework write itself — the one action too tied to `rememberLauncherForActivityResult`'s
 * own Compose API to live in a plain (non-`@Composable`) builder alongside its siblings above. */
@Composable
private fun rememberExportLauncher(
    context: Context,
    sessionId: String,
    scope: CoroutineScope,
    sheet: () -> RecordingSessionExportState,
    setSheet: (RecordingSessionExportState) -> Unit,
) = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
    val preview = sheet() as? RecordingSessionExportState.Preview
    if (uri == null || preview == null) return@rememberLauncherForActivityResult
    scope.launch {
        setSheet(RecordingSessionExportState.Writing(0L, preview.totalBytes))
        val outcome = context.contentResolver.openOutputStream(uri)?.use { out ->
            RecordingSessionPolling.exportWrite(context, sessionId, out) { written, total ->
                setSheet(RecordingSessionExportState.Writing(written, total))
            }
        }
        if (outcome != null) setSheet(outcome)
    }
}

private data class LabelActions(
    val onOpen: (String) -> Unit,
    val onSetMarkedForTraining: (Boolean) -> Unit,
    val onSetRating: (String) -> Unit,
)

private fun labelSheetActions(
    context: Context,
    scope: CoroutineScope,
    state: () -> RecordingSessionViewState?,
    sheet: () -> RecordingSessionLabelSheetViewState?,
    setSheet: (RecordingSessionLabelSheetViewState?) -> Unit,
): LabelActions = LabelActions(
    onOpen = { overId ->
        val row = state()?.rows.orEmpty().filterIsInstance<RecordingSessionRow.Over>().firstOrNull { it.id == overId }
        val callsignLabel = row?.callsign ?: "unknown station"
        scope.launch { setSheet(RecordingSessionPolling.labelSheetState(context, overId, callsignLabel)) }
    },
    onSetMarkedForTraining = { marked ->
        sheet()?.transmissionId?.let { id ->
            scope.launch {
                RecordingSessionPolling.setMarkedForTraining(context, id, marked)
                setSheet(sheet()?.copy(markedForTraining = marked))
            }
        }
    },
    onSetRating = { rating ->
        sheet()?.transmissionId?.let { id ->
            scope.launch {
                RecordingSessionPolling.setRating(context, id, rating)
                setSheet(sheet()?.copy(rating = rating))
            }
        }
    },
)
