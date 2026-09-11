package org.ort.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
 * producer of a `Connected`/`Stale` state — see `SettingsPolling.rig`'s own doc comment).
 *
 * Register R-839 (retired the old "no radio support in this build yet" copy): a genuinely
 * unconfigured rig is an ordinary, expected state — not a build limitation — so this reads "No rig
 * configured — overs are logged against the frequency you set" with a real, live `Change radio`
 * action (the same [onSwitchTransport] the `Link` row's own `Switch` and the bottom `Change radio`
 * button both call — one real re-entry point, `SettingsContent`'s own doc comment), never a dead
 * end. Still drawn via [FailedState] (guide §6.8's own "a capability is missing" amber shape still
 * applies — there genuinely is no rig doing anything right now), just with honest copy and a real
 * action instead of a claim about "this build."
 *
 * Once a rig *has* connected (or gone stale), the full `Connection` section (register R-835) is
 * real: `Switch`/`Change radio` re-enter setup at the rig-transport step (WPD's still-in-flight
 * `RIG_TRANSPORT`, forward-compatible today — see [SettingsContent]'s own doc comment), and
 * `Reconnect` re-reads [org.ort.pipeline.capture.RigStatus] fresh via [onReconnect] — reconnection
 * itself is the transport's own self-healing job (`RigSupervisor`'s own doc comment), not something
 * this screen triggers directly.
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
                // R-839 (register): retired "no radio support in this build yet" — see this
                // screen's own doc comment.
                // The `Change radio` action itself lives only in the bottom row below (shown in
                // every state, connected/stale/absent alike) — not duplicated here via `FailedState
                // .actionLabel`, which would render the identical action twice on one screen.
                FailedState(
                    title = "No rig configured",
                    body = "Overs are logged against the frequency you set in Settings › Input " +
                        "and level's manual entry, until a radio is connected.",
                    modifier = Modifier.padding(top = OrtSpacing.lg),
                )
            } else {
                RigConnectionSection(state = state, onSwitchTransport = onSwitchTransport)
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

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.lg, bottom = OrtSpacing.lg),
                horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
            ) {
                SecondaryButton(
                    text = "Reconnect",
                    enabled = state.connected || state.staleSinceLabel != null,
                    onClick = onReconnect,
                    modifier = Modifier.weight(1f),
                )
                // R-835/R-839: a second, always-live way to re-enter setup at the rig-transport
                // step — identical to the `Link` row's own `Switch` above, shown beside `Reconnect`
                // regardless of connection state (unlike `Reconnect`, choosing a different radio
                // never depends on one already having connected).
                SecondaryButton(
                    text = "Change radio",
                    enabled = true,
                    onClick = onSwitchTransport,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Register R-835: CF06's `Connection` section, real once a rig is connected or stale — the
 * attribution explanation (static app policy, not a per-rig fact), `Rig module`, `Link`, and,
 * where the matched descriptor states them, `Auto-information`/`Radio battery`. Split out of
 * [SettingsRigScreen] purely to keep that composable under detekt's `LongMethod` limit.
 */
@Composable
private fun RigConnectionSection(
    state: SettingsRigViewState,
    onSwitchTransport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionHeader(label = "How overs are attributed to a band", modifier = Modifier.padding(top = OrtSpacing.md))
        KeyValueRow(
            key = "By squelch state, BY",
            value = "",
            subLine = "the radio mixes both bands into one audio stream · whichever squelch opened " +
                "owns the over · both open at once is logged as both, marked so",
        )

        SectionHeader(label = "Connection", modifier = Modifier.padding(top = OrtSpacing.md))
        KeyValueRow(key = "Rig module", value = "", subLine = state.rigModuleLabel)
        KeyValueRow(
            key = "Link",
            value = "",
            // Register R-835 reopened (Reviewer C2, run 3): the address clause used to be folded
            // silently into the transport clause (`transport + (address?.let { " · $it" } ?: "")`)
            // — when [state.linkAddressLabel] was `null`, the *whole* address fact vanished with no
            // trace, reading as though it were never expected at all rather than genuinely unknown.
            // Now every real transport clause states the address fact explicitly, honest either way
            // (constitution I) — never silently dropped the way the old fold did.
            subLine = listOfNotNull(
                state.transportLabel?.let { transport ->
                    "$transport · " + (state.linkAddressLabel ?: "address not yet reported by this build")
                } ?: "transport and address not yet reported by this build",
                "paired in system settings".takeIf { state.transportLabel?.contains("Bluetooth") == true },
                state.otherTransportLabel,
            ).joinToString(" · "),
            trailingMarker = { TextAction(text = "Switch", onClick = onSwitchTransport) },
        )
        // A plain `KeyValueRow`, not `ToggleRow` (`ui/components`, not mine to add an `enabled`
        // param to): this build has no real preference behind this fact — it is the connected
        // descriptor's own fixed, structural behaviour (`unsolicited` push, always on when the
        // descriptor declares it), not something this screen can actually let the operator flip.
        // A live-looking toggle with no real effect on tap would be a worse dishonesty than a
        // plain "On" value.
        state.autoInformation?.let { ai ->
            KeyValueRow(key = ai.label, value = "On", subLine = ai.subLine)
        }
        // Register R-881 (Validator V11, device, spec): the mnemonic jammed into the key ("Radio
        // battery, BL") truncated at font scale 2.0 with an empty value beside it and produced a
        // malformed content-desc ("Radio battery, BL, , not reported by this rig module" — a stray
        // separator either side of the empty value). The label, value and mnemonic are three
        // distinct facts; each now gets its own real slot, matching the row's own established shape
        // elsewhere on this screen (a plain key, the real value, the CAT clause as the sub-line).
        KeyValueRow(
            key = "Radio battery",
            value = state.batteryLabel,
            subLine = "BL",
        )
    }
}

/** CF06's subtitle line: `Connected [· <transport>][· <polling clause>]`, `Stale since <label>`,
 * or `Not connected` — never a differently-worded restatement of the same facts elsewhere on this
 * screen. Register R-845: the trailing polling clause (`state.pollingClause` — "reading both bands
 * unpolled" only while the connected descriptor's own push is real, else "polled every N s", both
 * from `SettingsPolling.rig`) is appended only while actually connected — a stale link is not
 * currently being read at all, polled or otherwise. */
private fun rigSubtitle(state: SettingsRigViewState): String = when {
    state.connected -> listOfNotNull(
        "Connected",
        state.transportLabel,
        state.pollingClause.takeIf { it != NOT_REPORTED_BY_RIG_MODULE },
    ).joinToString(" · ")
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
        // R-835: the board's own per-band over count — see `SettingsRigBandViewState
        // .overCountLabel`'s own doc comment for why this is always the honest fallback today.
        Text(
            text = band.overCountLabel,
            style = OrtType.subLine,
            color = OrtColors.textFaint,
            modifier = Modifier.padding(top = 1.dp).testTag(BAND_TILE_OVER_COUNT_TEST_TAG),
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
internal const val BAND_TILE_OVER_COUNT_TEST_TAG: String = "settings-rig-band-tile-over-count"
