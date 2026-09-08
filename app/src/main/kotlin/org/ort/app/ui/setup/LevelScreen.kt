package org.ort.app.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.FailedState
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType

/**
 * S07 (`Setup-Level.dc.html`, R-082) — the live 60 s meter driven by [LevelCheck]. `Continue`
 * enables only [LevelBand.IN_BAND] (guide §6.10). No level signal at all (open failed, or nothing
 * was ever read) renders an honest "cannot measure" [FailedState] instead of fabricated bars
 * (guide §6.8, constitution I) — never a fake meter.
 */
@Composable
public fun LevelScreen(state: LevelCheckState?, onContinue: () -> Unit) {
    val reading = (state as? LevelCheckState.Reading)?.level
    SetupScaffold(
        step = SetupStep.LEVEL,
        title = "Level",
        subtitle = "Set the radio's volume so speech sits in the band, above the noise",
        onBack = null,
        bottomActions = {
            PrimaryButton(
                text = "Continue",
                onClick = onContinue,
                enabled = reading?.band == LevelBand.IN_BAND,
                modifier = Modifier.fillMaxWidth().testTag("setup-level-continue"),
            )
        },
    ) {
        if (state is LevelCheckState.Unavailable) {
            FailedState(
                title = "Cannot measure the level",
                body = state.reason,
                modifier = Modifier.testTag("setup-level-unavailable"),
            )
            return@SetupScaffold
        }

        LevelMeter(reading = reading, modifier = Modifier.testTag("setup-level-meter"))
        Row(modifier = Modifier.fillMaxWidth().padding(top = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = "noise −58", style = OrtType.axis, color = OrtColors.accentGapDim)
            Text(text = "target −18 to −12", style = OrtType.axis, color = OrtColors.accentGreenDim)
            Text(text = "clip 0", style = OrtType.axis, color = OrtColors.haltText)
        }

        LevelRow(
            label = "Speech peaks",
            value = reading?.let { "%.0f dBFS".format(it.peakDbfs) } ?: "—",
            testTag = "setup-level-peaks",
        )
        LevelRow(
            label = "Noise floor",
            value = reading?.noiseFloorDbfs?.let { "%.0f dBFS".format(it) } ?: "—",
            testTag = "setup-level-noise-floor",
        )
        LevelRow(
            label = "Headroom",
            value = reading?.let { "%.0f dB".format(it.headroomDb) } ?: "—",
            testTag = "setup-level-headroom",
        )

        reading?.let {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(colorFor(it.band), CircleShape),
                )
                Text(
                    text = messageFor(it.band),
                    style = OrtType.subtitle,
                    color = OrtColors.textBody,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        Text(
            text = "The level is set on the radio, not here — the app only reads it. Too quiet " +
                "loses the weak signals; clipping loses the strong ones.",
            style = OrtType.cardBody,
            color = OrtColors.textDim,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun LevelMeter(reading: LevelReading?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(OrtColors.bgPage),
    ) {
        val bars = reading?.bars.orEmpty()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .align(Alignment.BottomStart)
                .height(96.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(1.5.dp),
        ) {
            bars.forEach { fraction ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height((96.dp) * fraction.coerceIn(0f, 1f))
                        .background(OrtColors.accentGreen),
                )
            }
        }
    }
}

@Composable
private fun LevelRow(label: String, value: String, testTag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .testTag(testTag),
    ) {
        Text(text = label, style = OrtType.subtitle, color = OrtColors.textDim, modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = OrtType.timeFreq.copy(fontSize = OrtType.control.fontSize),
            color = OrtColors.textBody,
        )
    }
}

private fun colorFor(band: LevelBand) = when (band) {
    LevelBand.IN_BAND -> OrtColors.accentGreen
    LevelBand.TOO_QUIET -> OrtColors.accentAmber
    LevelBand.CLIPPING -> OrtColors.haltFill
}

private fun messageFor(band: LevelBand) = when (band) {
    LevelBand.IN_BAND -> "In the band. Leave the radio's volume where it is."
    LevelBand.TOO_QUIET -> "Too quiet — turn the radio's volume up."
    LevelBand.CLIPPING -> "Clipping — turn the radio's volume down."
}
