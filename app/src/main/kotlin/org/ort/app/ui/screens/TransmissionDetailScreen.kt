package org.ort.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import org.ort.app.ui.data.TransmissionDetailViewState
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * The transmission detail drill-in (build-plan P14, `design/canvas/Detail.dc.html`): the
 * "why this callsign" header, audio playback and full transcript for one transmission.
 *
 * FR-UI-5 (audio playback): [player] is the seam — [RealTransmissionAudioPlayer] on a device,
 * [org.ort.app.ui.audio.FakeTransmissionAudioPlayer] under test — so this composable's own logic
 * (show a control iff [TransmissionDetailViewState.hasAudio], report what the player reports) is
 * provable without a device.
 *
 * Deliberately not built here, and why: `Detail.dc.html`'s phonetic-lattice and per-prior
 * ("what ranked it first") panel is FR-UI-8, which build-plan P16 owns by name ("the inspection
 * surface... the per-prior breakdown `PriorCombiner` already produces") — that data has no path
 * to this screen yet, and fabricating a look-alike panel with no real ranking behind it would be
 * exactly the kind of confident-looking fabrication constitution I forbids.
 */
@Composable
public fun TransmissionDetailScreen(
    state: TransmissionDetailViewState,
    player: TransmissionAudioPlayer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        BackRow(onBack)
        HeaderSection(state)
        PlaybackSection(state, player)
        TranscriptSection(state)
        RevisionHistorySection(state)
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
    Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
        Text(text = "Earlier versions (superseded)", style = OrtType.sectionLabel)
    }
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(state.revisionHistory) { earlier ->
            Text(
                text = earlier,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.xs),
            )
        }
    }
}
