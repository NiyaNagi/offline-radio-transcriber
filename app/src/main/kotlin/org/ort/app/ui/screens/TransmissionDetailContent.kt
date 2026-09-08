package org.ort.app.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import org.ort.app.ui.audio.TransmissionAudioPlayer
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.data.CorrectionPolling
import org.ort.app.ui.data.CorrectionRequest
import org.ort.app.ui.data.CorrectionScope
import org.ort.app.ui.data.CorrectionTier
import org.ort.app.ui.data.DetailViewState
import org.ort.app.ui.data.DetailViewStateMapper
import org.ort.app.ui.data.LabelledSampleWriter
import org.ort.app.ui.data.PropagationOutcome
import org.ort.app.ui.data.ReaderPolling
import org.ort.app.ui.data.ReaderTransmissionViewStateMapper
import org.ort.app.ui.data.TranscriptVersionViewState
import org.ort.app.ui.data.TransmissionDetail
import org.ort.app.ui.theme.OrtSpacing
import org.ort.core.SystemClock
import java.io.File

/**
 * ui-conformance WP6: the content composable [org.ort.app.ui.navigation.OrtNavHost] (WP3) dispatches
 * to for the detail drill-in — the exact signature named in this package's row, so WP3 can delete
 * the inline copy currently in `OrtNavHost.kt` and call this one, unchanged, once merged.
 *
 * Owns: the base detail poll (via [ReaderPolling.transmissionDetail] — a read-only call to WP4's
 * public API, exactly as the pre-existing inline version already made; only the *correction*
 * read/write path is [CorrectionPolling]'s alone, per this package's row), [DrillInHeader]
 * (`design/design-guide.md`'s R-050: [TransmissionDetailScreen] no longer draws its own back row),
 * and the nested why/revisions/correction/propagated navigation, delegated one composable per
 * [DetailDestination] below so this dispatcher itself stays short.
 */
@Composable
public fun TransmissionDetailContent(
    context: Context,
    transmissionId: String,
    player: TransmissionAudioPlayer,
    onBack: () -> Unit,
    onOpenTransmission: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var detail by remember(transmissionId) { mutableStateOf<TransmissionDetail?>(null) }
    var destination by remember(transmissionId) { mutableStateOf<DetailDestination>(DetailDestination.Main) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        detail = ReaderPolling.transmissionDetail(context, transmissionId)
    }
    LaunchedEffect(transmissionId) { refresh() }

    val current = detail
    if (current == null) {
        Text(
            text = "Loading…",
            modifier = modifier.padding(OrtSpacing.lg).semantics { contentDescription = "Loading transmission detail" },
        )
        return
    }
    val viewState = DetailViewStateMapper.from(ReaderTransmissionViewStateMapper.detailView(current))
    val callsignLabel = viewState.detail.attribution.stationId ?: "unknown station"
    val whyParentLabel = "$callsignLabel · ${viewState.detail.timeLabel}"

    when (val dest = destination) {
        DetailDestination.Main -> MainDestination(
            context = context,
            transmissionId = transmissionId,
            current = current,
            viewState = viewState,
            player = player,
            onBack = onBack,
            onOpenTransmission = onOpenTransmission,
            onDestinationChange = { destination = it },
            refresh = ::refresh,
            modifier = modifier,
        )

        DetailDestination.Why -> DetailWhyScreen(
            callsignLabel = whyParentLabel,
            why = viewState.why,
            onBack = { destination = DetailDestination.Main },
            modifier = modifier,
        )

        DetailDestination.Revisions -> RevisionsDestination(
            context = context,
            transmissionId = transmissionId,
            dest = dest,
            parentLabel = whyParentLabel,
            onBack = { destination = DetailDestination.Main },
            refresh = ::refresh,
            modifier = modifier,
        )

        DetailDestination.Correcting -> CorrectingDestination(
            context = context,
            transmissionId = transmissionId,
            current = current,
            viewState = viewState,
            dest = dest,
            onDestinationChange = { destination = it },
            modifier = modifier,
        )

        is DetailDestination.Propagated -> PropagatedDestination(
            context = context,
            outcome = dest.outcome,
            onBack = onBack,
            onDestinationChange = { destination = it },
            refresh = ::refresh,
            modifier = modifier,
        )
    }
}

@Suppress("LongParameterList") // one seam per independent I/O effect this destination wires up.
@Composable
private fun MainDestination(
    context: Context,
    transmissionId: String,
    current: TransmissionDetail,
    viewState: DetailViewState,
    player: TransmissionAudioPlayer,
    onBack: () -> Unit,
    onOpenTransmission: (String) -> Unit,
    onDestinationChange: (DetailDestination) -> Unit,
    refresh: suspend () -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    Column(modifier = modifier.fillMaxSize()) {
        DrillInHeader(parentLabel = "Log", onBack = onBack)
        TransmissionDetailScreen(
            state = viewState,
            player = player,
            onOpenTransmission = onOpenTransmission,
            onNotRight = { onDestinationChange(DetailDestination.Correcting) },
            onConfirm = {
                val stationId = current.attribution.stationId
                if (stationId != null) {
                    scope.launch {
                        CorrectionPolling.confirm(context, transmissionId, stationId, SystemClock.wallMillis())
                        refresh()
                    }
                }
            },
            onChooseCandidate = { callsign ->
                scope.launch {
                    val outcome = CorrectionPolling.applyCorrection(
                        context,
                        CorrectionRequest(
                            transmissionId = transmissionId,
                            previousStationId = current.attribution.stationId,
                            newStationId = callsign,
                            tier = CorrectionTier.PICK_CANDIDATE,
                            correctedAtMillis = SystemClock.wallMillis(),
                        ),
                        CorrectionScope.EVERY_OVER_SAME_VOICE,
                    )
                    onDestinationChange(DetailDestination.Propagated(outcome))
                }
            },
            onNeither = { onDestinationChange(DetailDestination.Correcting) },
            onLeaveAmbiguous = onBack,
            onIKnowWhoThisIs = { onDestinationChange(DetailDestination.Correcting) },
            onOpenWhy = { onDestinationChange(DetailDestination.Why) },
            onOpenRevisions = { onDestinationChange(DetailDestination.Revisions) },
            onRecordLabel = { sample ->
                LabelledSampleWriter.append(File(context.filesDir, "labelled-samples.tsv"), sample)
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RevisionsDestination(
    context: Context,
    transmissionId: String,
    dest: DetailDestination,
    parentLabel: String,
    onBack: () -> Unit,
    refresh: suspend () -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    var versions by remember(transmissionId) { mutableStateOf(emptyList<TranscriptVersionViewState>()) }
    LaunchedEffect(transmissionId, dest) { versions = CorrectionPolling.revisions(context, transmissionId) }
    DetailRevisionsScreen(
        parentLabel = parentLabel,
        versions = versions,
        onRestore = { versionId ->
            scope.launch {
                CorrectionPolling.restore(context, transmissionId, versionId, SystemClock.wallMillis())
                versions = CorrectionPolling.revisions(context, transmissionId)
                refresh()
            }
        },
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
private fun CorrectingDestination(
    context: Context,
    transmissionId: String,
    current: TransmissionDetail,
    viewState: DetailViewState,
    dest: DetailDestination,
    onDestinationChange: (DetailDestination) -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    var everyOverCount by remember(transmissionId) { mutableStateOf(1) }
    LaunchedEffect(transmissionId, dest) {
        everyOverCount = CorrectionPolling.affectedOverCount(
            context,
            transmissionId,
            CorrectionScope.EVERY_OVER_SAME_VOICE,
        )
    }
    CorrectionSheet(
        currentCallsign = current.attribution.stationId,
        candidates = viewState.why.candidates,
        everyOverSameVoiceCount = everyOverCount,
        onSearchLexicon = { query -> ReaderPolling.searchLexicon(query) },
        onApply = { callsign, tier, correctionScope ->
            scope.launch {
                val outcome = CorrectionPolling.applyCorrection(
                    context,
                    CorrectionRequest(
                        transmissionId = transmissionId,
                        previousStationId = current.attribution.stationId,
                        newStationId = callsign,
                        tier = tier,
                        correctedAtMillis = SystemClock.wallMillis(),
                    ),
                    correctionScope,
                )
                onDestinationChange(DetailDestination.Propagated(outcome))
            }
        },
        onDismiss = { onDestinationChange(DetailDestination.Main) },
        modifier = modifier,
    )
}

@Composable
private fun PropagatedDestination(
    context: Context,
    outcome: PropagationOutcome,
    onBack: () -> Unit,
    onDestinationChange: (DetailDestination) -> Unit,
    refresh: suspend () -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    Column(modifier = modifier.fillMaxSize()) {
        DrillInHeader(parentLabel = "Log", onBack = onBack)
        PropagatedScreen(
            outcome = outcome,
            onUndoAll = {
                scope.launch {
                    CorrectionPolling.undoAll(context, outcome, SystemClock.wallMillis())
                    refresh()
                    onDestinationChange(DetailDestination.Main)
                }
            },
            onBackToOver = {
                scope.launch {
                    refresh()
                    onDestinationChange(DetailDestination.Main)
                }
            },
            onDone = onBack,
            modifier = Modifier.weight(1f),
        )
    }
}

private sealed interface DetailDestination {
    data object Main : DetailDestination
    data object Why : DetailDestination
    data object Revisions : DetailDestination
    data object Correcting : DetailDestination
    data class Propagated(val outcome: PropagationOutcome) : DetailDestination
}
