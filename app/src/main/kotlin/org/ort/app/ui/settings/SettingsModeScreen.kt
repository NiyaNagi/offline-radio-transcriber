package org.ort.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.ort.app.ui.components.Banner
import org.ort.app.ui.components.BannerTone
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.KeyValueRow
import org.ort.app.ui.components.RadioRow
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType
import org.ort.core.capture.CaptureMode

/**
 * `Settings-Mode.dc.html` (CF11, FR-CAP-8, FR-CAP-9, FR-CAP-12, FR-CAP-13, AC-131) — the settings
 * re-entry into the capture mode picker: the same three modes S00 offers, the current one marked,
 * the "what the mode set" rows each with `Change`, and — only while a session is live — the amber
 * banner naming that a pick here applies at the next session, never this one (constitution IV: the
 * audio route is never switched under a live capture).
 *
 * [onSelectMode] is called for every tap, live or idle — [SettingsModeViewState.sessionLive] is
 * what the *caller* checks to decide whether that pick lands as a real re-entry into setup or is
 * only recorded as pending (see [SettingsContent]'s own wiring for exactly which).
 */
@Composable
public fun SettingsModeScreen(
    state: SettingsModeViewState,
    onBack: () -> Unit,
    onSelectMode: (CaptureMode) -> Unit,
    onChangeAudioRoute: () -> Unit,
    onChangeRigLink: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DrillInHeader(parentLabel = "Input and level", onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
            Text(text = "Capture mode", style = OrtType.screenTitle, color = OrtColors.textHigh)
            Text(
                text = "How the radio is connected. Presets both rows below; each stays editable.",
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
            )

            if (state.sessionLive) {
                Banner(
                    title = "A session is running — changes apply when it ends",
                    body = "The audio route is never switched under a live capture. Tonight's record " +
                        "keeps the mode it started with; the next session starts with the one you choose here.",
                    tone = BannerTone.DEGRADED,
                    modifier = Modifier.padding(top = OrtSpacing.sm).testTag(MODE_LIVE_BANNER_TEST_TAG),
                )
            }

            SectionHeader(label = "Mode", modifier = Modifier.padding(top = OrtSpacing.md))
            state.rows.forEach { row ->
                RadioRow(
                    label = modeRowLabel(row),
                    selected = row.current,
                    onClick = { onSelectMode(row.mode) },
                    subtitle = row.descriptionLabel,
                    modifier = Modifier.testTag("${MODE_ROW_TEST_TAG_PREFIX}${row.mode.name}"),
                )
            }

            SectionHeader(label = "What the mode set", modifier = Modifier.padding(top = OrtSpacing.md))
            KeyValueRow(
                key = state.audioRoute.label,
                value = "",
                subLine = state.audioRoute.subLine,
                trailingMarker = { TextAction(text = "Change", onClick = onChangeAudioRoute) },
                modifier = Modifier.testTag(MODE_AUDIO_ROUTE_ROW_TEST_TAG),
            )
            KeyValueRow(
                key = state.rigLink.label,
                value = "",
                subLine = state.rigLink.subLine,
                trailingMarker = { TextAction(text = "Change", onClick = onChangeRigLink) },
                modifier = Modifier.testTag(MODE_RIG_LINK_ROW_TEST_TAG),
            )

            Text(
                text = "Every session records the mode and route it was captured with, so a room-audio " +
                    "night and a radio night are never confused after the fact, and Bluetooth-audio " +
                    "nights are reported as their own source in every accuracy figure.",
                style = OrtType.cardBody,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.lg, bottom = OrtSpacing.lg),
            )
        }
    }
}

/** `current`/`pending` read as plain trailing words on the row's own label — `RadioRow` (ui/components,
 * out of this package's ownership) has no separate trailing-badge slot, so this is appended to the
 * text a screen reader already reads as one node, exactly the way guide §Feedback's own text-not-
 * colour-alone discipline expects it stated. */
private fun modeRowLabel(row: SettingsModeRowViewState): String = when {
    row.current -> "${row.mode.operatorLabel} · current"
    row.pending -> "${row.mode.operatorLabel} · pending, applies next session"
    else -> row.mode.operatorLabel
}

public const val MODE_LIVE_BANNER_TEST_TAG: String = "settings-mode-live-banner"
public const val MODE_ROW_TEST_TAG_PREFIX: String = "settings-mode-row-"
public const val MODE_AUDIO_ROUTE_ROW_TEST_TAG: String = "settings-mode-audio-route-row"
public const val MODE_RIG_LINK_ROW_TEST_TAG: String = "settings-mode-rig-link-row"
