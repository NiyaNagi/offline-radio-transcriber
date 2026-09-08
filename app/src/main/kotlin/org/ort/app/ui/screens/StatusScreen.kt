package org.ort.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.ort.app.status.StatusViewState
import org.ort.app.ui.theme.OrtSpacing

/**
 * The "Now" destination's Compose surface (build-plan P13), which replaced the v0
 * `StatusActivity`'s plain `TextView`s with the identical facts (FR-UI-7, FR-PLT-1) — that
 * Activity is since deleted (audit F-002). A full "Now" home — the activity chart, "Worth
 * knowing" digest from `Main.dc.html` — is P14's job; this prompt's "done when" is capture state
 * reachable through the nav host with no behaviour change, not the full canvas screen.
 */
@Composable
public fun StatusScreen(state: StatusViewState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().padding(OrtSpacing.lg)) {
        state.uncleanEndBanner?.let { banner ->
            Text(
                text = banner,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(bottom = OrtSpacing.md)
                    .semantics { contentDescription = "Unclean end warning: $banner" },
            )
        }
        Text(
            text = state.stateLabel,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { contentDescription = "Capture state: ${state.stateLabel}" },
        )
        LabeledLine("Elapsed", state.elapsedLabel)
        LabeledLine("Transmissions", "${state.transmissionCount}")
        LabeledLine("Gaps", "${state.gapCount}")
        LabeledLine("Shed level", state.shedLevelLabel)
        // FR-RUN-5 / audit F-002: queue backlog alongside shed level — both come from the same
        // real ShedStatus reading (or the same honest "Not measured" before one exists).
        LabeledLine("Queue backlog", state.backlogLabel)
        LabeledLine("Liveness", state.livenessLabel)
        // FR-UI-7 / audit F-004: the status surface must say whether a transcription model is
        // actually installed and running, in plain text — not just via the missing-transcript
        // symptom the reader would otherwise show with no explanation.
        LabeledLine("ASR", state.asrStatusLabel)
        LabeledLine("VAD", state.vadStatusLabel)
    }
}

@Composable
private fun LabeledLine(label: String, value: String) {
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .padding(top = OrtSpacing.sm)
            .semantics { contentDescription = "$label: $value" },
    )
}
