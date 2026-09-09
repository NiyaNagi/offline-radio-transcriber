package org.ort.app.ui.screens

import android.content.Context
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.ort.app.ui.data.StationDetailViewState
import org.ort.app.ui.data.StationIdentityViewState
import org.ort.app.ui.data.StationPatternViewState
import org.ort.app.ui.data.StationPolling
import org.ort.app.ui.data.StationSubScreen
import org.ort.app.ui.data.StationVoiceSplitViewState
import org.ort.app.ui.theme.OrtSpacing
import org.ort.core.SystemClock

/**
 * The station drill-in's polling wrapper (R-071/R-072/R-073, ui-conformance-plan WP8) — the
 * `*Content.kt` composable `OrtNavHost` (WP3) dispatches an opened station to. Owns the local
 * "which sub-screen" state for `Station-Pattern` (reached from "By day"), `Station-Identity`
 * (reached from the header's kebab) and `Split` (reached from Station-Identity's own "Split"
 * action), since all three are full-screen presentations over this same station rather than
 * separate drawer destinations. Also owns the persistence side of R-073's `Rename`/`Add note`/
 * `Split` — [StationPolling.renameStation]/[StationPolling.updateStationNote]/
 * [StationPolling.splitVoiceprint] all write through [org.ort.data.dao.StationIdentityDao], then
 * this refetches identity so the screen reflects what was actually written, not an optimistic guess.
 */
@Composable
public fun StationDetailContent(
    context: Context,
    stationId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenTransmission: (String) -> Unit = {},
    // R-017: passed straight through to `StationDetailScreen`'s own `backLabel` — see that
    // composable's doc comment. Defaulted so `OrtNavHost.kt` compiles unchanged; WP3 wires the
    // real navigation origin afterwards.
    backLabel: String = "Stations",
    // WP12 screenshot tour (register, coordinator round 2026-09-08): seeds the sub-screen state
    // below once, so ST03/ST04 can be captured without a real user tap through Overview. Defaulted
    // to `NONE` so every existing caller (`OrtNavHost.kt`) compiles unchanged.
    initialSubScreen: StationSubScreen = StationSubScreen.NONE,
) {
    val scope = rememberCoroutineScope()
    var sub by remember(stationId) { mutableStateOf(initialSubScreen) }
    var detail by remember(stationId) { mutableStateOf<StationDetailViewState?>(null) }
    var pattern by remember(stationId) { mutableStateOf<StationPatternViewState?>(null) }
    var identity by remember(stationId) { mutableStateOf<StationIdentityViewState?>(null) }
    var splitCandidates by remember(stationId) { mutableStateOf<StationVoiceSplitViewState?>(null) }
    // R-272 (register, halt, V5 pass 2 @8d1456f): `voiceSplitCandidates` returning `null` is a
    // real, honest answer — this station's voice pipeline never bound a cluster to split, not
    // "still fetching". Before this flag, both states shared the one `splitCandidates == null`
    // value, so a single-cluster (or cluster-less) station rendered "Loading…" forever — there was
    // nothing left to load. `remember(stationId)` so switching stations resets it, matching every
    // other piece of this screen's own per-station state.
    var splitCandidatesLoaded by remember(stationId) { mutableStateOf(false) }

    LaunchedEffect(stationId) {
        detail = StationPolling.stationDetail(context, stationId, nowMillis = SystemClock.wallMillis())
    }
    LaunchedEffect(stationId, sub) {
        if (sub == StationSubScreen.IDENTITY && identity == null) {
            identity = StationPolling.stationIdentity(context, stationId)
        }
        if (sub == StationSubScreen.PATTERN && pattern == null) {
            pattern = StationPolling.stationPattern(context, stationId, nowMillis = SystemClock.wallMillis())
        }
        if (sub == StationSubScreen.SPLIT && !splitCandidatesLoaded) {
            splitCandidates = StationPolling.voiceSplitCandidates(context, stationId)
            splitCandidatesLoaded = true
        }
    }

    suspend fun refreshIdentity() {
        identity = StationPolling.stationIdentity(context, stationId)
    }

    when (sub) {
        StationSubScreen.PATTERN -> {
            val current = pattern
            if (current != null) {
                StationPatternScreen(state = current, onBack = { sub = StationSubScreen.NONE }, modifier = modifier)
            } else {
                LoadingLine(modifier)
            }
        }

        StationSubScreen.IDENTITY -> IdentitySubScreen(
            state = identity,
            context = context,
            stationId = stationId,
            scope = scope,
            modifier = modifier,
            refreshIdentity = ::refreshIdentity,
            onBack = { sub = StationSubScreen.NONE },
            onOpenSplit = { sub = StationSubScreen.SPLIT },
        )

        StationSubScreen.SPLIT -> SplitSubScreen(
            state = SplitSubScreenState(
                candidates = splitCandidates,
                loaded = splitCandidatesLoaded,
                callsign = identity?.callsign ?: stationId,
            ),
            context = context,
            stationId = stationId,
            scope = scope,
            modifier = modifier,
            onCancel = { sub = StationSubScreen.IDENTITY },
            onSplit = { newIdentity, refreshedCandidates ->
                identity = newIdentity
                splitCandidates = refreshedCandidates
                sub = StationSubScreen.IDENTITY
            },
        )

        StationSubScreen.NONE -> {
            val current = detail
            if (current != null) {
                StationDetailScreen(
                    state = current,
                    onBack = onBack,
                    modifier = modifier,
                    onOpenTransmission = onOpenTransmission,
                    onOpenPattern = { sub = StationSubScreen.PATTERN },
                    onOpenIdentity = { sub = StationSubScreen.IDENTITY },
                    backLabel = backLabel,
                )
            } else {
                LoadingLine(modifier)
            }
        }
    }
}

/** `StationSubScreen.IDENTITY` — split out of [StationDetailContent] to keep that composable
 * under detekt's `LongMethod` limit. */
@Composable
private fun IdentitySubScreen(
    state: StationIdentityViewState?,
    context: Context,
    stationId: String,
    scope: CoroutineScope,
    refreshIdentity: suspend () -> Unit,
    onBack: () -> Unit,
    onOpenSplit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state == null) {
        LoadingLine(modifier)
        return
    }
    StationIdentityScreen(
        state = state,
        onBack = onBack,
        modifier = modifier,
        onRename = { name ->
            scope.launch {
                StationPolling.renameStation(context, stationId, name)
                refreshIdentity()
            }
        },
        onAddNote = { note ->
            scope.launch {
                StationPolling.updateStationNote(context, stationId, note)
                refreshIdentity()
            }
        },
        onSplit = onOpenSplit,
    )
}

/**
 * [SplitSubScreen]'s own fetched state (R-272), folded into one holder — the same reason
 * [org.ort.app.ui.data.StationsListState] exists — so that composable's own parameter count stays
 * under detekt's `LongParameterList` limit. [loaded] distinguishes "still fetching" from "fetched,
 * and this station genuinely has nothing to split" — [candidates] is `null` in both cases, which
 * is exactly the ambiguity that used to render a bare "Loading…" forever for a station with no
 * voiceprint cluster bound.
 */
private data class SplitSubScreenState(
    val candidates: StationVoiceSplitViewState?,
    val loaded: Boolean,
    val callsign: String,
)

/**
 * `StationSubScreen.SPLIT` — split out of [StationDetailContent] for the same reason as
 * [IdentitySubScreen]. [onSplit] hands back the refreshed identity and split-candidate state once
 * [StationPolling.splitVoiceprint] has actually written, so the caller never has to guess.
 *
 * R-272 (register, halt): `!state.loaded` renders the loading line; `state.loaded &&
 * state.candidates == null` renders a real, named empty state with its own header and a way back.
 */
@Composable
private fun SplitSubScreen(
    state: SplitSubScreenState,
    context: Context,
    stationId: String,
    scope: CoroutineScope,
    onCancel: () -> Unit,
    onSplit: (StationIdentityViewState, StationVoiceSplitViewState?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.loaded) {
        LoadingLine(modifier)
        return
    }
    val candidates = state.candidates
    if (candidates == null) {
        SplitEmptyState(callsign = state.callsign, onBack = onCancel, modifier = modifier)
        return
    }
    StationSplitScreen(
        state = candidates,
        onCancel = onCancel,
        onSplit = { transmissionIds ->
            scope.launch {
                val newIdentity = StationPolling.splitVoiceprint(
                    context,
                    stationId,
                    candidates.fromVoiceprintId,
                    transmissionIds,
                )
                // The overs that moved are no longer in this cluster — refetch rather than patch
                // the list locally, so a re-opened chooser shows the real remaining membership,
                // not a guess.
                val refreshedCandidates = StationPolling.voiceSplitCandidates(context, stationId)
                onSplit(newIdentity, refreshedCandidates)
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun LoadingLine(modifier: Modifier) {
    Text(
        text = "Loading…",
        modifier = modifier
            .padding(OrtSpacing.lg)
            .semantics { contentDescription = "Loading station detail" },
    )
}
