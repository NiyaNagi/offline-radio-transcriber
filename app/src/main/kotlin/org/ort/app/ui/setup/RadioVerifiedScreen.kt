package org.ort.app.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType
import org.ort.pipeline.capture.RigStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * S11 (`Setup-Rig-Verified.dc.html`, R-084) — renders a real [RigStatus.State.Connected] or a
 * [RigStatus.State.Stale] (the `Fail-Rig.dc.html` "last known … stale since" treatment, adapted for
 * setup: `Reconnect`/`Change radio` rather than the running-capture banner's "set the frequency by
 * hand", since setup has not started capture yet). [RigStatus.State.Absent] is **not** accepted
 * here — validator finding (register R-120..R-125, halt): the previous version silently `return`ed
 * on anything but `Connected`, rendering a blank black screen for `Stale` (the real `rig-lost` case
 * this package's own debug scenario exists to exercise). [SetupActivity.RenderRadioVerified] now
 * routes `Absent` back to S09 with a banner *before* this composable is ever called, so the
 * `Absent` case genuinely cannot reach here in practice; the `when` below still has no `else`
 * (would not compile against [RigStatus.State]'s three cases without one) so a future fourth
 * `RigStatus.State` cannot silently render nothing here either.
 *
 * Since `:rig`/`:rig-usb` are unbuilt, the only producer of any of this today is the debug scenario
 * simulator poking [RigStatus] directly (WP0) — see [RadioUsbScreen]'s doc comment for the full
 * accounting of what is and is not reachable with real hardware right now (nothing).
 */
@Composable
public fun RadioVerifiedScreen(
    state: RigStatus.State,
    onContinue: () -> Unit,
    onChangeRadio: () -> Unit,
    onReconnect: () -> Unit,
    /** D33/E2-E12/FR-RIG-14 — the transport this rig is connected over ("USB serial"/"Bluetooth
     * SPP"), named in the subtitle so S11 never leaves the operator to guess which link this
     * verified session is actually reading from. `null` only for a caller with nothing real to
     * report yet (never reachable through [SetupActivity]'s own dispatch, which always knows
     * [SetupStore.rigTransport] by the time this screen renders). */
    transportLabel: String? = null,
) {
    when (state) {
        is RigStatus.State.Connected -> RadioVerifiedConnected(state, transportLabel, onContinue, onChangeRadio)
        is RigStatus.State.Stale -> RadioVerifiedStale(state, onReconnect, onChangeRadio)
        is RigStatus.State.Absent -> {
            // See this file's own doc comment: the caller routes Absent away before ever
            // reaching this composable. A blank Box, not a crash, is the last-resort fallback if
            // that ever stops being true — never the fully blank screen the validator found.
        }
    }
}

@Composable
private fun RadioVerifiedConnected(
    connected: RigStatus.State.Connected,
    transportLabel: String?,
    onContinue: () -> Unit,
    onChangeRadio: () -> Unit,
) {
    SetupScaffold(
        step = SetupStep.RADIO_VERIFIED,
        title = "${connected.descriptor} connected",
        subtitle = if (transportLabel != null) {
            "$transportLabel · identified and verified"
        } else {
            "Identified and verified"
        },
        onBack = null,
        bottomActions = {
            PrimaryButton(
                text = "Continue",
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth().testTag("setup-radio-verified-continue"),
            )
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextAction(
                    text = "Change radio",
                    onClick = onChangeRadio,
                    modifier = Modifier.testTag("setup-radio-verified-change"),
                )
            }
        },
    ) {
        RigVerifiedContent(connected)
    }
}

@Composable
private fun RadioVerifiedStale(stale: RigStatus.State.Stale, onReconnect: () -> Unit, onChangeRadio: () -> Unit) {
    val sinceLabel = STALE_SINCE_FORMAT.format(Instant.ofEpochMilli(stale.sinceMillis).atZone(ZoneId.systemDefault()))
    SetupScaffold(
        step = SetupStep.RADIO_VERIFIED,
        title = "${stale.lastKnown.descriptor} — last known",
        subtitle = "Stale since $sinceLabel",
        onBack = null,
        bottomActions = {
            PrimaryButton(
                text = "Reconnect",
                onClick = onReconnect,
                modifier = Modifier.fillMaxWidth().testTag("setup-radio-verified-reconnect"),
            )
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextAction(
                    text = "Change radio",
                    onClick = onChangeRadio,
                    modifier = Modifier.testTag("setup-radio-verified-change"),
                )
            }
        },
    ) {
        SetupHaltBanner(
            title = "The radio stopped reporting at $sinceLabel",
            body = "What is shown below is the last reading, not a live one — the frequency, mode " +
                "and squelch state may have changed since. Reconnect to verify again, or change " +
                "which radio this setup uses.",
        )
        RigVerifiedContent(stale.lastKnown)
    }
}

private val STALE_SINCE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)

/** The reading grid + verified command list — shared with [RadioUsbScreen]'s defensive
 * `Connected`/`Stale` fallback rendering so the two screens never drift on what "verified" shows. */
@Composable
internal fun RigVerifiedContent(connected: RigStatus.State.Connected) {
    Column {
        Text(text = "Reading now".uppercase(), style = OrtType.sectionLabel, color = OrtColors.textFaint)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            connected.bands.forEach { band ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(OrtColors.bgCard, RoundedCornerShape(10.dp))
                        .padding(13.dp)
                        .testTag("setup-radio-verified-band-${band.band}"),
                ) {
                    Text(
                        text = "Band ${band.band}".uppercase(),
                        style = OrtType.columnHeader,
                        color = OrtColors.textLow,
                    )
                    Text(
                        text = band.frequencyHz?.let { "%.3f".format(it / 1_000_000.0) } ?: "—",
                        style = OrtType.figure,
                        color = OrtColors.textHigh,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    if (band.squelchOpen) OrtColors.accentGreen else OrtColors.bgScreen,
                                    CircleShape,
                                ),
                        )
                        Text(
                            text = " ${band.mode ?: "—"} · ${if (band.squelchOpen) "squelch open" else "closed"}",
                            style = OrtType.subLine,
                            color = OrtColors.textMuted,
                        )
                    }
                }
            }
        }
    }
    Column {
        Text(text = "Verified command set".uppercase(), style = OrtType.sectionLabel, color = OrtColors.textFaint)
        listOf(
            "FQ" to "Frequency, per band",
            "BY" to "Squelch state — attributes each over to a band",
            "FO" to "Mode",
            "AI" to "Auto-information — changes arrive unpolled",
            "BL" to "Radio battery, for the status surface",
        ).forEach { (code, label) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = code,
                    style = OrtType.callsignCard,
                    color = OrtColors.textHigh,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Text(
                    text = label,
                    style = OrtType.transcript,
                    color = OrtColors.textBody,
                    modifier = Modifier.weight(1f),
                )
                Box(modifier = Modifier.size(9.dp).background(OrtColors.accentGreen, CircleShape))
            }
        }
    }
}
