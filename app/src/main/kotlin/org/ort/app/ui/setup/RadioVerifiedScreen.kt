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
import org.ort.app.ui.components.Banner
import org.ort.app.ui.components.BannerTone
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.settings.SettingsPolling
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType
import org.ort.pipeline.capture.RigStatus
import org.ort.rig.RigCapability
import org.ort.rig.RigTransportKind
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
@Suppress("LongParameterList") // R-903: two more real, load-bearing facts joined transportLabel --
// see this function's own doc comment for why each earns its place, the same standing exception
// SetupScaffold's own class doc already grants this package.
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
    /** R-903 (reviewer A2, run 3, design): the real elapsed seconds S10b's own open -> identify ->
     * verify checklist measured (`SetupActivity`'s own timer, started the moment a paired device
     * was picked, stopped the moment `RigLinkState.Verified` was reached) — `null` whenever no such
     * probe ran (the USB-serial lane has no equivalent timed handshake today), which
     * [radioVerifiedSubtitle] renders as the plain "identified and verified" clause, never a
     * fabricated duration (constitution I). */
    verifyDurationSeconds: Double? = null,
    /** AC-133: `true` only when the connected rig's own catalogue entry declares *both*
     * `BLUETOOTH_SPP` and `USB_SERIAL` transports with an identical, non-empty
     * [org.ort.rig.RigCapability] set — [sameCommandSetAsUsb] is the pure check; never asserted
     * for a rig this build cannot actually prove it for. */
    sameCommandSetAsUsb: Boolean = false,
    /** R-1013/R-1014 (WPD3): the capability labels [org.ort.app.ui.setup.RigLinkState.VerifyTimedOut]
     * named as never observed — non-empty only when S10b's checklist concluded there rather than at
     * [org.ort.app.ui.setup.RigLinkState.Verified]. **The absolute constraint this parameter exists
     * to satisfy: a non-empty list here must never render as if the link were fully verified** —
     * neither the marker dot, nor the subtitle, nor the section header may say "verified" outright
     * while this is non-empty (constitution I). Empty (the default) for a genuinely complete
     * verification and for the USB lane, which has no equivalent partial-verification outcome
     * (`SetupActivity.onConnectRigTransport`'s own USB branch never sets this, the same way it
     * never carries a Bluetooth attempt's own measured [verifyDurationSeconds]). */
    missingCapabilities: List<String> = emptyList(),
) {
    when (state) {
        is RigStatus.State.Connected -> RadioVerifiedConnected(
            state,
            transportLabel,
            verifyDurationSeconds,
            sameCommandSetAsUsb,
            missingCapabilities,
            onContinue,
            onChangeRadio,
        )
        is RigStatus.State.Stale -> RadioVerifiedStale(state, onReconnect, onChangeRadio)
        is RigStatus.State.Absent -> {
            // See this file's own doc comment: the caller routes Absent away before ever
            // reaching this composable. A blank Box, not a crash, is the last-resort fallback if
            // that ever stops being true — never the fully blank screen the validator found.
        }
    }
}

/**
 * R-903 (reviewer A2, run 3): S11's subtitle clause build, pure so the three real facts it can
 * carry — the transport, a genuinely *measured* verify duration, and AC-133's "same command set as
 * USB" — are each testable without a screen. `Setup-Rig-Verified.dc.html`'s own worked example:
 * `"Bluetooth SPP · identified and verified in 1.2 s · same command set as USB"`. A `null`
 * [transportLabel] (never reachable through [SetupActivity]'s own dispatch — see [RadioVerifiedScreen]'s
 * own doc comment) still returns a real sentence, capitalised as the board's own fallback case reads.
 */
internal fun radioVerifiedSubtitle(
    transportLabel: String?,
    verifyDurationSeconds: Double?,
    sameCommandSetAsUsb: Boolean,
    /** R-1013/R-1014: non-zero only for a [org.ort.app.ui.setup.RigLinkState.VerifyTimedOut] link —
     * see [RadioVerifiedScreen]'s own [missingCapabilities] doc comment for the absolute constraint
     * this exists to satisfy. Takes precedence over [verifyDurationSeconds] (which is `null` for
     * this case regardless — `SetupActivity.onRigLinkStateChanged` only ever measures a duration for
     * a genuine [org.ort.app.ui.setup.RigLinkState.Verified]), never claiming "verified" when this
     * is positive. */
    missingCapabilityCount: Int = 0,
): String {
    // R-1014: "partially confirmed", never a variant of the word "verified" — the absolute
    // constraint is that this subtitle must never read as Verified for a partial link.
    val verifyClause = when {
        missingCapabilityCount > 0 -> "identified, command set partially confirmed"
        verifyDurationSeconds != null -> "identified and verified in %.1f s".format(Locale.ROOT, verifyDurationSeconds)
        else -> "identified and verified"
    }
    if (transportLabel == null) return verifyClause.replaceFirstChar { it.uppercase() }
    val clauses = mutableListOf(transportLabel, verifyClause)
    if (sameCommandSetAsUsb) clauses += "same command set as USB"
    return clauses.joinToString(" · ")
}

/**
 * AC-133: the one honest source for "same command set as USB" — a rig whose catalogue entry
 * declares both [RigTransportKind.BLUETOOTH_SPP] and [RigTransportKind.USB_SERIAL] with an
 * identical, non-empty capability set (the TH-D75A's own descriptor, `docs/reference/th-d75a-cat.md`).
 * `false` whenever [currentTransport] is not Bluetooth at all (the clause only ever compares
 * Bluetooth *against* USB, never the other way around), either transport is undeclared, or the two
 * sets differ or are both empty — never guessed from the rig's name or verified flag alone.
 */
internal fun sameCommandSetAsUsb(
    transportCapabilities: Map<RigTransportKind, Set<RigCapability>>,
    currentTransport: RigTransportKind?,
): Boolean {
    if (currentTransport != RigTransportKind.BLUETOOTH_SPP) return false
    val bluetooth = transportCapabilities[RigTransportKind.BLUETOOTH_SPP] ?: return false
    val usb = transportCapabilities[RigTransportKind.USB_SERIAL] ?: return false
    return bluetooth.isNotEmpty() && bluetooth == usb
}

@Composable
private fun RadioVerifiedConnected(
    connected: RigStatus.State.Connected,
    transportLabel: String?,
    verifyDurationSeconds: Double?,
    sameCommandSetAsUsb: Boolean,
    missingCapabilities: List<String>,
    onContinue: () -> Unit,
    onChangeRadio: () -> Unit,
) {
    val partiallyVerified = missingCapabilities.isNotEmpty()
    SetupScaffold(
        step = SetupStep.RADIO_VERIFIED,
        title = "${SettingsPolling.stripManufacturerPrefix(connected.descriptor)} connected",
        subtitle = radioVerifiedSubtitle(
            transportLabel,
            verifyDurationSeconds,
            sameCommandSetAsUsb,
            missingCapabilityCount = missingCapabilities.size,
        ),
        titleLeading = {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    // R-1013/R-1014: the absolute constraint applied to the one marker every other
                    // screen reads at a glance — amber, never the plain green "fully verified" dot,
                    // the moment this link's own checklist concluded at VerifyTimedOut.
                    .background(
                        if (partiallyVerified) OrtColors.accentAmber else OrtColors.accentGreen,
                        CircleShape,
                    )
                    .testTag("setup-radio-verified-marker"),
            )
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
        if (partiallyVerified) {
            Banner(
                title = "Command set only partially verified",
                body = "Not observed: ${missingCapabilities.joinToString(", ")}. Normal for a quiet band " +
                    "or an idle control — reconnect later from Settings if it matters for this session.",
                tone = BannerTone.DEGRADED,
                modifier = Modifier.fillMaxWidth().testTag("setup-radio-verified-partial-banner"),
            )
        }
        RigVerifiedContent(connected, partiallyVerified = partiallyVerified)
    }
}

@Composable
private fun RadioVerifiedStale(stale: RigStatus.State.Stale, onReconnect: () -> Unit, onChangeRadio: () -> Unit) {
    val sinceLabel = STALE_SINCE_FORMAT.format(Instant.ofEpochMilli(stale.sinceMillis).atZone(ZoneId.systemDefault()))
    SetupScaffold(
        step = SetupStep.RADIO_VERIFIED,
        title = "${SettingsPolling.stripManufacturerPrefix(stale.lastKnown.descriptor)} — last known",
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
 * `Connected`/`Stale` fallback rendering so the two screens never drift on what "verified" shows.
 * [partiallyVerified] (R-1013/R-1014, default `false` — [RadioUsbScreen]'s own call site never
 * has a partial-verification outcome to report) only renames the section header away from the
 * word "Verified" itself; the per-command reference rows below it are the protocol's own generic
 * command set, not a per-session per-capability observation this function has the data to redraw
 * precisely — see this round's own report for why that deeper precision is left as a known gap
 * rather than fabricated here. */
@Composable
internal fun RigVerifiedContent(connected: RigStatus.State.Connected, partiallyVerified: Boolean = false) {
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
        Text(
            text = (if (partiallyVerified) "Command set" else "Verified command set").uppercase(),
            style = OrtType.sectionLabel,
            color = OrtColors.textFaint,
        )
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
