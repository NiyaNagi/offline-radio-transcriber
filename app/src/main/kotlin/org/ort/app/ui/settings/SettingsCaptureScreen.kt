package org.ort.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.KeyValueRow
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.components.TextField
import org.ort.app.ui.components.ToggleRow
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType
import org.ort.core.capture.CaptureMode

/**
 * `Settings-Capture.dc.html`: input/level facts from `InputStatus`/`LevelStatus` (WP11c).
 *
 * R-132 (round 4, System validator): [onOpenInputSetup] backs both `Device`'s `Change` and
 * `Re-verify the route now`'s `Verify` — both real actions land on the same place in this build
 * (`SetupActivity` reopened at its `INPUT` step, `SettingsContent`'s own doc comment says exactly
 * why: there is no narrower "re-verify only, keep the same device" entry point, only the full
 * input-selection-then-verify step `Setup-Input.dc.html`/`Setup-Verify.dc.html` already is).
 * [onOpenLevelMeter] defaults to a no-op so every existing caller keeps compiling unchanged; the
 * host (`OrtNavHost`, WP3's row) is expected to wire it to the real `Level-Meter` destination the
 * same way it wires every other cross-package drill-in.
 */
@Composable
public fun SettingsCaptureScreen(
    state: SettingsCaptureViewState,
    onBack: () -> Unit,
    toggles: SettingsCaptureToggleActions,
    modifier: Modifier = Modifier,
    onOpenInputSetup: () -> Unit = {},
    onOpenLevelMeter: () -> Unit = {},
    onOpenModeSettings: () -> Unit = {},
    onEditManualFrequency: ((String) -> Unit)? = null,
) {
    // Register R-957 (root cause, WPI's host comparison on 5558): R-826's own static trailing
    // clearance below was a *second* reservation stacked on top of `NavHostBody`'s own real,
    // measured one — that host wraps every destination it does not embed a live bar for (this
    // screen included; `embedsOwnLiveBar` is `NOW`/`CAPTURE` only) in
    // `Box(Modifier.weight(1f).padding(top = clearance, bottom = liveBarHeight))`, so the scroll
    // viewport this screen's own `modifier` parameter already sits inside is reduced by the live
    // bar's real height *before* this composable ever sees it. Adding a second, static 44dp on top
    // left a real, reproducible 45dp dead band between the scroll viewport's own bottom and the
    // live bar's top on every live-session capture at 1.0 (confirmed on-device: `ScrollView`
    // bounds ending 125px/45dp short of the bar's own top) — content was always reachable (nothing
    // was clipped), it just read as "cut with blank space" the same on every capture. One
    // mechanism only now: this screen trusts the host's own real reservation and adds none of its
    // own — the scroll viewport's own bottom lands exactly at the live bar's top (or, with no live
    // bar showing, at the screen's own natural end — `liveBarHeight` is `0.dp` then, per
    // `NavHostBody`'s own default).
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        DrillInHeader(parentLabel = "Settings", onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
            Text(text = "Input and level", style = OrtType.screenTitle, color = OrtColors.textHigh)
            Text(
                text = "Changing the input re-verifies the route before capture continues",
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
            )

            // CF02 (amended 2026-09-10, FR-CAP-12): the leading Capture-mode row — icon per mode.
            // `KeyValueRow` (ui/components, out of this package's ownership) has no leading-icon
            // slot, so this row is its own small composable below, the same reason
            // `SettingsRigScreen.kt`'s own `BandTile` draws itself rather than adapting a shared row.
            SectionHeader(label = "Capture mode", modifier = Modifier.padding(top = OrtSpacing.md))
            CaptureModeRow(state = state, onChange = onOpenModeSettings)

            SectionHeader(label = "Input", modifier = Modifier.padding(top = OrtSpacing.md))
            KeyValueRow(
                key = "Device",
                value = state.inputLabel,
                subLine = state.inputSubLine,
                trailingMarker = { TextAction(text = "Change", onClick = onOpenInputSetup) },
            )
            KeyValueRow(
                key = "Re-verify the route now",
                value = "",
                subLine = "30 s · capture pauses for it · a gap is recorded",
                trailingMarker = { TextAction(text = "Verify", onClick = onOpenInputSetup) },
            )

            SectionHeader(label = "Level", modifier = Modifier.padding(top = OrtSpacing.md))
            KeyValueRow(
                key = "Speech",
                value = state.levelLabel,
                subLine = state.levelSubLine,
                trailingMarker = { TextAction(text = "Meter", onClick = onOpenLevelMeter) },
            )
            ToggleRow(
                label = "Warn when out of band",
                checked = state.levelWarnEnabled,
                onCheckedChange = toggles.onToggleLevelWarn,
                subLine = "notification and status surface · below −30 or clipping",
            )

            SectionHeader(label = "Enhancement", modifier = Modifier.padding(top = OrtSpacing.md))
            ToggleRow(
                label = "Noise reduction before transcription",
                checked = state.noiseReductionEnabled,
                onCheckedChange = toggles.onToggleNoiseReduction,
                subLine = "on the copy the models hear · the retained audio is untouched",
            )
            ToggleRow(
                label = "Band-pass for FM voice",
                checked = state.bandPassEnabled,
                onCheckedChange = toggles.onToggleBandPass,
                subLine = "300–3000 Hz · off if you monitor anything but voice",
            )

            SectionHeader(
                label = "Frequency, when no radio is connected",
                modifier = Modifier.padding(top = OrtSpacing.md),
            )
            ManualFrequencyRow(manualFrequencyMhz = state.manualFrequencyMhz, onEdit = onEditManualFrequency)

            Text(
                text = "The level is set on the radio, not here. The app reads it and tells you when it " +
                    "drifts; it never adjusts gain on the way in, so what is retained is what the radio put out.",
                style = OrtType.cardBody,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.lg, bottom = OrtSpacing.lg),
            )
        }
    }
}

/**
 * CF02's leading Capture-mode row (`Settings-Capture.dc.html`, amended 2026-09-10, FR-CAP-12) — an
 * icon per mode, [SettingsCaptureViewState.modeLabel]/[modeSubLine], and `Change` → CF11. Its own
 * small composable (see [SettingsCaptureScreen]'s own doc comment for why) rather than
 * [KeyValueRow], which has no leading-icon slot.
 */
@Composable
private fun CaptureModeRow(state: SettingsCaptureViewState, onChange: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = OrtSpacing.sm).testTag(CAPTURE_MODE_ROW_TEST_TAG),
        horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
    ) {
        Icon(
            imageVector = captureModeIcon(state.mode),
            contentDescription = null,
            tint = OrtColors.textDim,
            modifier = Modifier.size(18.dp).padding(top = 1.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = state.modeLabel, style = OrtType.rowTitle, color = OrtColors.textHigh)
            Text(
                text = state.modeSubLine,
                style = OrtType.subLine,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        TextAction(text = "Change", onClick = onChange)
    }
}

/** `ui/components` (out of this package's ownership) carries no dedicated Bluetooth/RF glyph, so
 * [org.ort.app.ui.components.OrtIcons.rig] stands in for Bluetooth-radio mode — that mode's own
 * distinguishing fact against USB-radio is the rig link, which is what that icon already depicts.
 * `null` (mode not yet known — a fresh install) reuses the built-in-mic glyph as the neutral
 * default, matching S00's own "local microphone" being the first, always-available option. */
private fun captureModeIcon(mode: CaptureMode?) = when (mode) {
    CaptureMode.USB_RADIO -> OrtIcons.usbAudio
    CaptureMode.BLUETOOTH_RADIO -> OrtIcons.rig
    CaptureMode.LOCAL_MICROPHONE, null -> OrtIcons.builtInMic
}

/** E2-F01 (`spec/e2e-capture-modes-plan.md`): the stable handle a test or the tour uses to find
 * CF02's own Capture-mode row. */
public const val CAPTURE_MODE_ROW_TEST_TAG: String = "settings-capture-mode-row"

/** R-132 (register, round 4 System validator): `Edit` is real when a caller wires [onEdit] —
 * `SettingsStore.manualFrequencyMhz` is a real writable field (confirmed by reading
 * `SettingsStore.kt` before adding this), previously read-only in this screen. `null` (the
 * default `onEditManualFrequency` [SettingsCaptureScreen] passes through) keeps the row honest for
 * any caller that has not wired a save path yet: no `Edit` action that would silently do nothing.
 * Split out of [SettingsCaptureScreen] purely to keep that function under detekt's length limit. */
@Composable
private fun ManualFrequencyRow(
    manualFrequencyMhz: String?,
    onEdit: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(manualFrequencyMhz) { mutableStateOf(manualFrequencyMhz.orEmpty()) }
    if (onEdit != null && editing) {
        TextField(
            value = draft,
            onValueChange = { draft = it },
            label = "Log overs against, MHz",
            modifier = modifier,
            trailingAction = {
                TextAction(text = "Save", onClick = {
                    onEdit(draft)
                    editing = false
                })
            },
        )
    } else {
        KeyValueRow(
            key = "Log overs against",
            value = manualFrequencyMhz ?: "not set",
            subLine = "used only while the rig is disconnected or absent",
            modifier = modifier,
            trailingMarker = onEdit?.let { { TextAction(text = "Edit", onClick = { editing = true }) } },
        )
    }
}
