package org.ort.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.ort.app.ui.components.FailedState
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.data.LevelViewState
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * `Level-Meter.dc.html` (ui-conformance-plan WP4, R-039, FR-CAP-3, F3). No level signal exists
 * anywhere in `:pipeline` today — [org.ort.pipeline.capture.ShedStatus]/[org.ort.pipeline.capture.ThermalStatus]/
 * [org.ort.pipeline.capture.RigStatus]/[org.ort.pipeline.capture.StorageForecast] cover shed level,
 * thermal, rig and storage; none of WP11a's four holders is a level (dBFS) reading. Rather than
 * fabricate bars from nothing (constitution I), this renders [org.ort.app.ui.components.FailedState]
 * — guide §6.8's rule that a screen with nothing to show because a *capability is missing* is
 * *failed*, not *empty* — naming the missing signal in operator terms.
 */
@Composable
public fun LevelMeterScreen(state: LevelViewState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().padding(OrtSpacing.lg)) {
        Text(text = "Level", style = OrtType.screenTitle, color = OrtColors.textHigh)
        Text(text = state.inputLabel, style = OrtType.subtitle, color = OrtColors.textDim)

        if (state.notMeasuredReason != null) {
            FailedState(
                title = "No level signal yet",
                body = state.notMeasuredReason,
                modifier = Modifier.testTag("level-meter-failed").padding(top = OrtSpacing.lg),
            )
        } else {
            SectionHeader(label = "Speech peaks, last 60 s", modifier = Modifier.padding(top = OrtSpacing.lg))
            LevelFact("Speech peaks", state.peakDbfsLabel)
            LevelFact("Noise floor", state.noiseFloorDbfsLabel)
            LevelFact("Headroom", state.headroomLabel)
            LevelFact("Clipped samples this session", state.clippedSamplesLabel)
        }
    }
}

@Composable
private fun LevelFact(label: String, value: String?) {
    Text(
        text = "$label: ${value ?: "not measured"}",
        style = OrtType.control,
        color = OrtColors.textBody,
        modifier = Modifier.padding(top = OrtSpacing.sm),
    )
}
