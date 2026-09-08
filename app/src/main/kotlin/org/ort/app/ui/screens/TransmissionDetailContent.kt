package org.ort.app.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import org.ort.app.ui.audio.TransmissionAudioPlayer
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.clearedWhileOverlaid
import org.ort.app.ui.data.CorrectionPolling
import org.ort.app.ui.data.CorrectionRequest
import org.ort.app.ui.data.CorrectionScope
import org.ort.app.ui.data.CorrectionTier
import org.ort.app.ui.data.DetailViewState
import org.ort.app.ui.data.DetailViewStateMapper
import org.ort.app.ui.data.LabelledSampleWriter
import org.ort.app.ui.data.PassFailureViewState
import org.ort.app.ui.data.PropagationOutcome
import org.ort.app.ui.data.ReaderPolling
import org.ort.app.ui.data.ReaderTransmissionViewStateMapper
import org.ort.app.ui.data.TranscriptVersionViewState
import org.ort.app.ui.data.TransmissionDetail
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.core.SystemClock
import org.ort.core.TransmissionState
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
 *
 * [backLabel] (R-017) names the destination [onBack] actually returns to — defaulted to `"Log"`
 * (today's only real entry point) so `OrtNavHost.kt` compiles unchanged while WP3 wires true origin
 * tracking through separately; a caller that knows the real origin (Search, a station's overs, a
 * thread) passes it once that wiring lands.
 *
 * **R-153.** [refresh] also looks up [CorrectionPolling.passFailure] whenever the polled
 * [TransmissionDetail.processingState] is `FAILED`, so [TransmissionDetailScreen] can render the
 * failed-pass state from the real queue row rather than the generic (and here misleading) UNKNOWN
 * attribution copy. `Retry this pass` calls [CorrectionPolling.retryFailedPass].
 */
@Composable
public fun TransmissionDetailContent(
    context: Context,
    transmissionId: String,
    player: TransmissionAudioPlayer,
    onBack: () -> Unit,
    onOpenTransmission: (String) -> Unit,
    backLabel: String = "Log",
    modifier: Modifier = Modifier,
) {
    var detail by remember(transmissionId) { mutableStateOf<TransmissionDetail?>(null) }
    var passFailure by remember(transmissionId) { mutableStateOf<PassFailureViewState?>(null) }
    var sourceOverTimeLabel by remember(transmissionId) { mutableStateOf<String?>(null) }
    var destination by remember(transmissionId) { mutableStateOf<DetailDestination>(DetailDestination.Main) }
    val scope = rememberCoroutineScope()

    // R-153: a pass-failure lookup is a real extra `:data` read, so it only runs for the
    // transmissions that can possibly have one — `processingState == FAILED` — never on every poll.
    // R-189 (halt): `ReaderPolling`'s own attribution derivation drops a corrected row's real
    // stationId back to UNKNOWN once its confidence is null (see `CorrectionPolling.currentAttribution`'s
    // own doc comment for the full diagnosis) — patched here, on every poll, not only after Undo.
    // R-183: the source over's real time, for the INFERRED explanation's "to HH:MM:SS" clause.
    suspend fun refresh() {
        var fetched = ReaderPolling.transmissionDetail(context, transmissionId)
        if (fetched != null) {
            fetched = fetched.copy(
                attribution = CorrectionPolling.currentAttribution(context, transmissionId, fetched.attribution),
            )
        }
        detail = fetched
        passFailure = if (fetched?.processingState == TransmissionState.FAILED) {
            CorrectionPolling.passFailure(context, transmissionId)
        } else {
            null
        }
        sourceOverTimeLabel = CorrectionPolling.sourceOverTimeLabel(
            context,
            fetched?.attribution?.sourceTransmissionId?.toString(),
        )
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
    val viewState = DetailViewStateMapper.from(
        ReaderTransmissionViewStateMapper.detailView(current),
        passFailure,
        sourceOverTimeLabel,
    )
    val callsignLabel = viewState.detail.attribution.stationId ?: "unknown station"
    val whyParentLabel = "$callsignLabel · ${viewState.detail.timeLabel}"
    val dest = destination

    // `Detail-Correct-A/B/C.dc.html`: the sheet sits over the *dimmed detail screen*, not a second
    // full screen — Correcting renders `Main` underneath (so the callsign stays visible through
    // the scrim, matching the artboard) plus the scrim+sheet overlay on top, inside one `Box` so
    // the two genuinely stack rather than one replacing the other.
    Box(modifier = modifier.fillMaxSize()) {
        when (dest) {
            DetailDestination.Main, DetailDestination.Correcting -> MainDestination(
                context = context,
                transmissionId = transmissionId,
                current = current,
                viewState = viewState,
                player = player,
                onBack = onBack,
                onOpenTransmission = onOpenTransmission,
                onDestinationChange = { destination = it },
                refresh = ::refresh,
                backLabel = backLabel,
                // R-261: while the correction sheet is open, the dimmed detail beneath it — its
                // own `DrillInHeader`'s `Back to Log` included — must not be focusable or reachable
                // by TalkBack traversal, and must not appear in the merged semantics tree at all.
                modifier = Modifier.fillMaxSize().clearedWhileOverlaid(dest == DetailDestination.Correcting),
            )

            DetailDestination.Why -> DetailWhyScreen(
                callsignLabel = whyParentLabel,
                why = viewState.why,
                onBack = { destination = DetailDestination.Main },
                modifier = Modifier.fillMaxSize(),
            )

            DetailDestination.Revisions -> RevisionsDestination(
                context = context,
                transmissionId = transmissionId,
                dest = dest,
                parentLabel = whyParentLabel,
                onBack = { destination = DetailDestination.Main },
                refresh = ::refresh,
                modifier = Modifier.fillMaxSize(),
            )

            is DetailDestination.Propagated -> PropagatedDestination(
                context = context,
                outcome = dest.outcome,
                onBack = onBack,
                onDestinationChange = { destination = it },
                refresh = ::refresh,
                backLabel = backLabel,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (dest == DetailDestination.Correcting) {
            CorrectingOverlay(
                context = context,
                transmissionId = transmissionId,
                current = current,
                viewState = viewState,
                dest = dest,
                onDestinationChange = { destination = it },
            )
        }
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
    backLabel: String,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    Column(modifier = modifier.fillMaxSize()) {
        DrillInHeader(parentLabel = backLabel, onBack = onBack)
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
            onRetryPass = {
                val failure = viewState.passFailure
                if (failure != null) {
                    scope.launch {
                        CorrectionPolling.retryFailedPass(context, transmissionId, failure.passId)
                        refresh()
                    }
                }
            },
            // R-153: "Keep the partial" writes nothing — the over already reads exactly as it is
            // (the honest partial, or "(transcription failed)"); there is no state left to change.
            onKeepPartial = {},
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

/**
 * `Detail-Correct-A/B/C.dc.html`: the sheet over a dimmed backdrop, dismissed on scrim tap — the
 * identical `Column`-of-scrim-then-sheet split `SearchScreen.kt`'s `FiltersSheetOverlay` (WP7)
 * uses (read for the pattern, not edited): a `Column`, not two `fillMaxSize()` siblings in a `Box`,
 * so the scrim's clickable area is exactly the strip actually exposed above the sheet, never the
 * area the sheet itself covers — two overlapping `fillMaxSize()` elements would both claim the
 * same tap, and the sheet, drawn on top, would always win regardless of which one the operator
 * meant. [CorrectionSheet] itself owns the `fillMaxHeight(0.85f)` cap and its own scroll, for the
 * same reason `SearchFiltersSheet` does (see that composable's doc comment).
 */
@Suppress("LongParameterList") // one seam per independent I/O effect this overlay wires up.
@Composable
private fun CorrectingOverlay(
    context: Context,
    transmissionId: String,
    current: TransmissionDetail,
    viewState: DetailViewState,
    dest: DetailDestination,
    onDestinationChange: (DetailDestination) -> Unit,
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

    fun apply(callsign: String, tier: CorrectionTier, correctionScope: CorrectionScope) {
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
    }
    val onDismiss = { onDestinationChange(DetailDestination.Main) }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(OrtColors.bgPage.copy(alpha = 0.78f))
                .clickable(role = Role.Button, onClickLabel = "Dismiss correction", onClick = onDismiss)
                .testTag("correction-sheet-scrim"),
        )
        CorrectionSheet(
            currentCallsign = current.attribution.stationId,
            candidates = viewState.why.candidates,
            everyOverSameVoiceCount = everyOverCount,
            onSearchStations = { query -> CorrectionPolling.searchHeardStations(context, query) },
            onApply = ::apply,
            onDismiss = onDismiss,
            modifier = Modifier.testTag("correction-sheet"),
        )
    }
}

@Composable
private fun PropagatedDestination(
    context: Context,
    outcome: PropagationOutcome,
    onBack: () -> Unit,
    onDestinationChange: (DetailDestination) -> Unit,
    refresh: suspend () -> Unit,
    backLabel: String,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    Column(modifier = modifier.fillMaxSize()) {
        DrillInHeader(parentLabel = backLabel, onBack = onBack)
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
