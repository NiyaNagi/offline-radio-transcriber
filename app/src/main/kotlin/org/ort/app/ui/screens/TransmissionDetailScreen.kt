package org.ort.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import org.ort.app.ui.audio.PlaybackOutcome
import org.ort.app.ui.audio.TransmissionAudioPlayer
import org.ort.app.ui.components.AttributionMarker
import org.ort.app.ui.data.CorrectionRequest
import org.ort.app.ui.data.CorrectionTier
import org.ort.app.ui.data.LabelCertainty
import org.ort.app.ui.data.LabelOutcome
import org.ort.app.ui.data.LabelledSample
import org.ort.app.ui.data.TransmissionDetailViewState
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType
import org.ort.core.SystemClock

/**
 * The transmission detail drill-in (build-plan P14, extended by P16, `design/canvas/Detail.dc.html`):
 * the "why this callsign" header, audio playback, full transcript, the inspection surface
 * (FR-UI-8), one-tap correction (FR-UI-6) and labelled-sample capture (FR-OBS-4) for one
 * transmission.
 *
 * FR-UI-5 (audio playback): [player] is the seam — [org.ort.app.ui.audio.RealTransmissionAudioPlayer]
 * on a device, [org.ort.app.ui.audio.FakeTransmissionAudioPlayer] under test.
 *
 * [onCorrect], [onSearchStations] and [onRecordLabel] are the I/O seams for the three build-plan
 * P16 features — real implementations go through [org.ort.app.ui.data.ReaderPolling]; every
 * Compose test here drives a fake in-memory lambda, matching [player]'s own pattern. Default no-op
 * implementations mean existing call sites (P14's own tests) compile unchanged.
 */
@Composable
public fun TransmissionDetailScreen(
    state: TransmissionDetailViewState,
    player: TransmissionAudioPlayer,
    onBack: () -> Unit,
    onCorrect: suspend (CorrectionRequest) -> Unit = {},
    onSearchStations: suspend (String) -> List<String> = { emptyList() },
    onRecordLabel: suspend (LabelledSample) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BackRow(onBack)
        HeaderSection(state)
        PlaybackSection(state, player)
        TranscriptSection(state)
        RevisionHistorySection(state)
        InspectionSection(state)
        CorrectionSection(state, onCorrect, onSearchStations)
        LabelSampleSection(state, onRecordLabel)
    }
}

@Composable
private fun BackRow(onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(OrtSpacing.lg)) {
        Text(
            text = "‹ Back",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.clickable(onClick = onBack).semantics { contentDescription = "Back" },
        )
    }
}

@Composable
private fun HeaderSection(state: TransmissionDetailViewState) {
    Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
        Row {
            AttributionMarker(attribution = state.attribution)
            Text(text = "  " + (state.attribution.stationId ?: "Unidentified station"), style = OrtType.titleLarge)
        }
        Text(
            text = "${state.timeLabel} · ${state.frequencyLabel} · ${state.durationLabel}" +
                (state.signalLabel?.let { " · $it" } ?: ""),
            style = OrtType.caption,
            modifier = Modifier.padding(top = OrtSpacing.xs),
        )
        if (state.attribution.corrected) {
            Text(
                text = "Corrected by operator",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = OrtSpacing.xs),
            )
        }
    }
}

@Composable
private fun PlaybackSection(state: TransmissionDetailViewState, player: TransmissionAudioPlayer) {
    val scope = rememberCoroutineScope()
    var playbackNote by remember(state.id) { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.padding(OrtSpacing.lg)) {
        if (!state.hasAudio) {
            Text(text = "No retained audio for this transmission", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Text(
            text = "▶ Play",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .clickable {
                    scope.launch {
                        playbackNote = when (val outcome = player.play(state.id)) {
                            PlaybackOutcome.Played -> null
                            is PlaybackOutcome.Unavailable -> outcome.reason
                        }
                    }
                }
                .semantics { contentDescription = "Play retained audio" },
        )
        playbackNote?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { contentDescription = "Playback unavailable: $it" },
            )
        }
    }
}

@Composable
private fun TranscriptSection(state: TransmissionDetailViewState) {
    Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
        Text(text = "Transcript", style = OrtType.sectionLabel)
        Text(
            text = state.transcriptText,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.md),
        )
    }
}

@Composable
private fun RevisionHistorySection(state: TransmissionDetailViewState) {
    if (state.revisionHistory.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = OrtSpacing.lg)) {
        Text(text = "Earlier versions (superseded)", style = OrtType.sectionLabel)
        // A plain Column, not a LazyColumn: the whole screen is now one `verticalScroll` container
        // (build-plan P16 added enough content below the fold that the screen needed scrolling at
        // all), and a vertically-scrolling LazyColumn nested in another vertical scroll container
        // measures with an infinite height constraint and crashes — this list is never long enough
        // to need lazy layout anyway.
        state.revisionHistory.forEach { earlier ->
            Text(
                text = earlier,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = OrtSpacing.xs),
            )
        }
    }
}

/**
 * FR-UI-8, the inspection surface: the phonetic lattice, the candidate list and the per-prior
 * breakdown `:lexicon`'s `PriorCombiner` (P7) produces — read here through the lexicon-free
 * `:data` entities [org.ort.app.ui.data.InspectionViewStateMapper] maps (see that file's own doc
 * comment for why `:app` cannot reach `PriorContribution`/`RankedCandidate` directly). A prior
 * that abstained (cold start, exactly zero — FR-LEX-31) is labelled distinctly from one that
 * argued against (a negative contribution) — the two are different facts and must not read alike
 * (constitution I).
 */
@Composable
private fun InspectionSection(state: TransmissionDetailViewState) {
    Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)) {
        Text(text = "Why this callsign", style = OrtType.sectionLabel)
        if (state.inspection.isEmpty) {
            Text(
                text = "No resolver output recorded for this transmission yet.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = OrtSpacing.xs),
            )
            return@Column
        }
        state.inspection.lattice?.let { lattice ->
            Text(
                text = "Lattice: ${lattice.source} (model ${lattice.modelId ?: "unrecorded"})",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = OrtSpacing.xs),
            )
        }
        state.inspection.candidates.forEach { candidate ->
            Text(
                text = "${candidate.callsign} — score %.2f".format(candidate.score),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = OrtSpacing.xs),
            )
            candidate.priorContributions.forEach { prior ->
                val descriptor = when {
                    prior.isColdStart -> "cold start — no prior data"
                    prior.logOdds < 0.0 -> "argued against"
                    else -> "supported"
                }
                Text(
                    text = "  ${prior.priorName}: %+.2f (%s)".format(prior.logOdds, descriptor),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * FR-UI-6 + FR-SPK-7, Q8's tiered correction. Tier A (pick a resolved candidate) and Tier B
 * (search a known station) both name an already-known identity; Tier C (free text) is recorded
 * unverified. See [org.ort.app.ui.data.CorrectionTier]'s doc comment for the module-boundary
 * reason Tier B searches known stations rather than the full lexicon.
 */
@Composable
private fun CorrectionSection(
    state: TransmissionDetailViewState,
    onCorrect: suspend (CorrectionRequest) -> Unit,
    onSearchStations: suspend (String) -> List<String>,
) {
    val scope = rememberCoroutineScope()
    var expanded by remember(state.id) { mutableStateOf(false) }
    var query by remember(state.id) { mutableStateOf("") }
    var searchResults by remember(state.id) { mutableStateOf(emptyList<String>()) }
    var freeText by remember(state.id) { mutableStateOf("") }

    fun apply(newStationId: String, tier: CorrectionTier) {
        scope.launch {
            onCorrect(
                CorrectionRequest(
                    transmissionId = state.id,
                    previousStationId = state.attribution.stationId,
                    newStationId = newStationId,
                    tier = tier,
                    correctedAtMillis = SystemClock.wallMillis(),
                ),
            )
            expanded = false
            query = ""
            searchResults = emptyList()
            freeText = ""
        }
    }

    Column(modifier = Modifier.padding(OrtSpacing.lg)) {
        Text(
            text = if (expanded) "▾ Correct attribution" else "▸ Correct attribution",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .clickable { expanded = !expanded }
                .semantics { contentDescription = "Correct attribution" },
        )
        if (!expanded) return@Column

        if (state.inspection.candidates.isNotEmpty()) {
            Text(
                text = "Pick a resolved candidate",
                style = OrtType.sectionLabel,
                modifier = Modifier.padding(top = OrtSpacing.sm),
            )
            state.inspection.candidates.forEach { candidate ->
                Text(
                    text = candidate.callsign,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .clickable { apply(candidate.callsign, CorrectionTier.PICK_CANDIDATE) }
                        .semantics { contentDescription = "Correct to ${candidate.callsign}" }
                        .padding(vertical = OrtSpacing.xs),
                )
            }
        }

        Text(
            text = "Search known stations",
            style = OrtType.sectionLabel,
            modifier = Modifier.padding(top = OrtSpacing.sm),
        )
        TextField(
            value = query,
            onValueChange = { text ->
                query = text
                scope.launch { searchResults = onSearchStations(text) }
            },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search known stations" },
        )
        searchResults.forEach { found ->
            Text(
                text = found,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .clickable { apply(found, CorrectionTier.SEARCH_KNOWN_STATION) }
                    .semantics { contentDescription = "Correct to $found" }
                    .padding(vertical = OrtSpacing.xs),
            )
        }

        Text(
            text = "Free text (unverified — will not feed the priors)",
            style = OrtType.sectionLabel,
            modifier = Modifier.padding(top = OrtSpacing.sm),
        )
        TextField(
            value = freeText,
            onValueChange = { freeText = it },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Free-text station (unverified)" },
        )
        Text(
            text = "Save unverified correction",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .clickable(enabled = freeText.isNotBlank()) { apply(freeText, CorrectionTier.FREE_TEXT) }
                .semantics { contentDescription = "Save unverified correction" }
                .padding(top = OrtSpacing.xs),
        )
    }
}

/**
 * FR-OBS-4: record a labelled sample from a live session into the format `corpus/`'s harness
 * already reads, per `docs/reference/labelling-protocol.md`. Defaults [LabelledSample.sessionId],
 * [LabelledSample.startSample] and [LabelledSample.endSample] from the real transmission this
 * screen is showing, since those facts are already known and re-typing them would invite error.
 */
private class LabelSampleFormState(id: String) {
    var callsign by mutableStateOf("")
    var certainty by mutableStateOf(LabelCertainty.CERTAIN)
    var outcome by mutableStateOf(LabelOutcome.SPEECH)
    var tactical by mutableStateOf("")
    var threadId by mutableStateOf("")
    var note by mutableStateOf("")

    fun clear() {
        callsign = ""
        tactical = ""
        threadId = ""
        note = ""
    }

    fun toSample(detail: TransmissionDetailViewState): LabelledSample = LabelledSample(
        sessionId = detail.sessionId,
        startSample = detail.startSample,
        endSample = detail.endSample,
        outcome = outcome,
        doubled = false,
        callsign = callsign,
        certainty = if (callsign.isBlank()) null else certainty,
        tactical = tactical,
        threadId = threadId,
        note = note,
    )
}

@Composable
private fun LabelSampleSection(state: TransmissionDetailViewState, onRecordLabel: suspend (LabelledSample) -> Unit) {
    val scope = rememberCoroutineScope()
    var expanded by remember(state.id) { mutableStateOf(false) }
    val form = remember(state.id) { LabelSampleFormState(state.id) }

    Column(modifier = Modifier.padding(OrtSpacing.lg)) {
        Text(
            text = if (expanded) "▾ Record labelled sample" else "▸ Record labelled sample",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .clickable { expanded = !expanded }
                .semantics { contentDescription = "Record labelled sample" },
        )
        if (!expanded) return@Column

        LabelSampleFields(form)
        Text(
            text = "Save",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .clickable {
                    scope.launch {
                        onRecordLabel(form.toSample(state))
                        expanded = false
                        form.clear()
                    }
                }
                .semantics { contentDescription = "Save labelled sample" }
                .padding(top = OrtSpacing.sm),
        )
    }
}

@Composable
private fun LabelSampleFields(form: LabelSampleFormState) {
    Text(
        text = "Outcome: ${form.outcome.wire}",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .clickable { form.outcome = cycleOutcome(form.outcome) }
            .semantics { contentDescription = "Cycle outcome, currently ${form.outcome.wire}" }
            .padding(top = OrtSpacing.sm),
    )
    TextField(
        value = form.callsign,
        onValueChange = { form.callsign = it },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Labelled callsign" },
    )
    if (form.callsign.isNotBlank()) {
        Text(
            text = "Certainty: ${form.certainty.wire}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .clickable { form.certainty = cycleCertainty(form.certainty) }
                .semantics { contentDescription = "Cycle certainty, currently ${form.certainty.wire}" },
        )
    }
    TextField(
        value = form.tactical,
        onValueChange = { form.tactical = it },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Tactical callsign" },
    )
    TextField(
        value = form.threadId,
        onValueChange = { form.threadId = it },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Thread id" },
    )
    TextField(
        value = form.note,
        onValueChange = { form.note = it },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Note" },
    )
}

private fun cycleOutcome(current: LabelOutcome): LabelOutcome {
    val values = LabelOutcome.entries
    return values[(values.indexOf(current) + 1) % values.size]
}

private fun cycleCertainty(current: LabelCertainty): LabelCertainty {
    val values = LabelCertainty.entries
    return values[(values.indexOf(current) + 1) % values.size]
}
