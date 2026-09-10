package org.ort.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.FailedState
import org.ort.app.ui.components.KeyValueRow
import org.ort.app.ui.components.SecondaryButton
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * `Settings-Rig.dc.html` (FR-RIG; amended 2026-09-10, FR-RIG-14/15). FR-RIG-1..12 describe a full
 * rig-module contract with a built-in TH-D75A module and a null module for manual entry —
 * [SettingsRigViewState.connected] is `false` on every build today whenever no rig has ever been
 * configured (register R-084's plan-level note; the debug scenario simulator is the only current
 * producer of a `Connected`/`Stale` state — see `SettingsPolling.rig`'s own doc comment), and this
 * renders that honestly as a [FailedState] rather than a fabricated `Reconnect`/`Change radio` pair
 * with nothing behind them.
 *
 * Once a rig *has* connected (or gone stale), the Link/Reconnect actions below are real: `Switch`
 * re-enters setup at the rig-transport step (WPD's still-in-flight `RIG_TRANSPORT`, forward-
 * compatible today — see [SettingsContent]'s own doc comment), and `Reconnect` — until WPC2's real
 * reconnect entry point exists — re-reads [RigStatus] fresh via [onReconnect], `TODO(WPC2)`d in
 * this one place so the real call is a one-line swap later.
 */
@Composable
public fun SettingsRigScreen(
    state: SettingsRigViewState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onReconnect: () -> Unit = {},
    onSwitchTransport: () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DrillInHeader(parentLabel = "Settings", onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
            Text(text = state.descriptorLabel, style = OrtType.screenTitle, color = OrtColors.textHigh)
            Text(
                text = rigSubtitle(state),
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
            )

            if (state.bands.isNotEmpty()) {
                SectionHeader(label = "Bands", modifier = Modifier.padding(top = OrtSpacing.md))
                BandTilesRow(bands = state.bands, modifier = Modifier.padding(top = OrtSpacing.sm))
            }

            if (!state.connected && state.staleSinceLabel == null) {
                // R-444 (register, Reviewer D): "FR-RIG's module contract (…)" named the
                // requirement, not a fact this screen's own operator would recognise — guide §9's
                // discipline `CaptureStatusViewState.radioFacts` (R-263) already established for
                // the identical fact: "no radio support in this build yet", verbatim, never a
                // second differently-worded claim about the same thing.
                FailedState(
                    title = "No rig module is connected",
                    body = "No radio support in this build yet. Capture is unaffected — " +
                        "frequencies are logged from Settings › Input and level's manual entry " +
                        "until a rig module exists.",
                    modifier = Modifier.padding(top = OrtSpacing.lg),
                )
            } else {
                SectionHeader(label = "Connection", modifier = Modifier.padding(top = OrtSpacing.md))
                KeyValueRow(
                    key = "Link",
                    value = "",
                    subLine = state.transportLabel?.let { transport ->
                        "$transport" + (state.linkAddressLabel?.let { " · $it" } ?: "")
                    } ?: "transport and address not yet reported by this build",
                    trailingMarker = { TextAction(text = "Switch", onClick = onSwitchTransport) },
                )
                KeyValueRow(
                    key = "If it disconnects",
                    value = "",
                    // `Settings-Rig.dc.html`'s own "If it disconnects" row is shown regardless of
                    // whether the link is currently stale — a standing policy statement — with the
                    // real, live fact (`state.staleSinceLabel` — already worded "since <label>",
                    // `SettingsPolling.rig`) folded in only when it is actually true right now.
                    subLine = "Capture continues on the last-known frequency, marked stale" +
                        (state.staleSinceLabel?.let { " — it is now, $it" } ?: "") +
                        " · every over logged meanwhile carries a stale mark · the link is retried with " +
                        "backoff · you are told loudly, and again when it is back",
                )
            }

            SecondaryButton(
                text = "Reconnect",
                enabled = state.connected || state.staleSinceLabel != null,
                onClick = onReconnect,
                modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.lg, bottom = OrtSpacing.lg),
            )
        }
    }
}

/** CF06's subtitle line: `Connected [· <transport>]`, `Stale since <label>`, or `Not connected` —
 * never a differently-worded restatement of the same three facts elsewhere on this screen. */
private fun rigSubtitle(state: SettingsRigViewState): String = when {
    state.connected -> "Connected" + (state.transportLabel?.let { " · $it" } ?: "")
    // [SettingsRigViewState.staleSinceLabel] is already worded "since <label>" (`SettingsPolling.rig`).
    state.staleSinceLabel != null -> "Stale ${state.staleSinceLabel}"
    else -> "Not connected"
}

/**
 * R-413 (register, halt, Reviewer D): the band row used to hand every `Tile` `Modifier.fillMaxWidth()`
 * inside a plain (unweighted) `Row` — the first tile claimed the *entire* row for itself, leaving
 * the second one squeezed into essentially `0px`, which forced its own mono frequency text
 * (`"146.960?"`) to wrap one character per line down the whole screen at font scale 1.0, let alone
 * 2.0. `ui/components`'s own shared `Tile` (`Rows.kt`) has no `maxLines`/`softWrap` override to fix
 * this from the call site, so this package draws its own [BandTile] instead of reusing it — the
 * same reason [NextDeletionMarker]/[ColorSwatch] in `SettingsStorageScreen.kt` are this package's
 * own local composables rather than a shared one that does not fit.
 *
 * [FlowRow] (not a plain `Row`) lets the two tiles stack onto their own lines when the row is too
 * narrow for both side by side (a large font scale, a narrow device) instead of being crushed —
 * each tile keeps a real, measured minimum width ([rememberBandFrequencyMinWidth], the real render
 * width of this screen's own worst-case sample, `"146.960?"`, the stale-band marker included) so
 * neither tile's own text ever again depends on however much space happens to be left over.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BandTilesRow(bands: List<SettingsRigBandViewState>, modifier: Modifier = Modifier) {
    val minWidth = rememberBandFrequencyMinWidth()
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
    ) {
        bands.forEach { band ->
            BandTile(
                band = band,
                modifier = Modifier.widthIn(min = minWidth).testTag(BAND_TILE_TEST_TAG),
            )
        }
    }
}

@Composable
private fun BandTile(band: SettingsRigBandViewState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(SETTINGS_CARD_BG, SETTINGS_CARD_SHAPE)
            .padding(vertical = OrtSpacing.md, horizontal = OrtSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = band.frequencyLabel,
            style = OrtType.figure,
            color = OrtColors.textHigh,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.testTag(BAND_TILE_FREQUENCY_TEST_TAG),
        )
        Text(
            text = "${band.label} · ${band.statusLabel}",
            style = OrtType.subLine,
            color = OrtColors.textFaint,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** The real, measured render width of this screen's own worst-case band frequency sample
 * (`"146.960?"` — the stale-band `?` suffix included, real device data per
 * `SettingsPolling.rig`/`rigLastKnown`, never a guessed constant) at [OrtType.figure] — the same
 * `rememberTextMeasurer` technique `ui/components/Rows.kt`'s own `rememberTimeColumnWidth`/
 * `rememberFreqColumnWidth` already use for an identical "never let a mono value's own column
 * shrink narrower than its real content" contract. */
@Composable
private fun rememberBandFrequencyMinWidth(): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, density) {
        with(density) { measurer.measure(BAND_FREQUENCY_SAMPLE, OrtType.figure).size.width.toDp() }
    }
}

private const val BAND_FREQUENCY_SAMPLE = "146.960?"

/** R-413: stable handles so a test can assert the frequency `Text` renders as one real line (never
 * wraps) at both font scale 1.0 and 2.0 — the direct proof of the fix. */
internal const val BAND_TILE_TEST_TAG: String = "settings-rig-band-tile"
internal const val BAND_TILE_FREQUENCY_TEST_TAG: String = "settings-rig-band-tile-frequency"
