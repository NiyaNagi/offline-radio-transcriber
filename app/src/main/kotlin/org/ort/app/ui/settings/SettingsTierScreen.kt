package org.ort.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.RadioRow
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * `Settings-Tier.dc.html` (P11, FR-TIER-1..7): states what this device therefore does *not* know
 * at a lower tier, plainly, per P11 — a weaker device knows less; it never shows a shakier number.
 * [SettingsTierViewState.currentTierLabel] comes from the same shed-level placeholder
 * `RealCaptureService.tierFromShedLevel()` already uses (no real tier detector exists — FR-TIER-1's
 * measured-throughput detector is unbuilt; this is named honestly rather than presented as one).
 */
@Composable
public fun SettingsTierScreen(
    state: SettingsTierViewState,
    onBack: () -> Unit,
    onSelectOverride: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DrillInHeader(parentLabel = "Settings", onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
            Text(text = "Tier and capability", style = OrtType.screenTitle, color = OrtColors.textHigh)
            Text(
                text = "A weaker device knows less. It is never more wrong.",
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
            )

            Column(
                modifier = Modifier.fillMaxWidth().background(OrtColors.bgCard, SETTINGS_CARD_SHAPE).padding(14.dp),
            ) {
                Text(
                    text = "${state.currentTierLabel} of ${state.maxTierLabel}",
                    style = OrtType.figure,
                    color = OrtColors.textHigh,
                )
                Text(
                    text = "No measured tier detector exists yet (FR-TIER-1) — this number is derived from " +
                        "the shed-shedding level, the same placeholder the capture notification uses.",
                    style = OrtType.cardBody,
                    color = OrtColors.textDim,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // R-134 (round 4, System validator): "the tiers" is static architecture copy — what
            // each tier can and cannot do is a fixed design fact, not a live measurement, so it
            // needs no signal to state honestly (`Settings-Tier.dc.html` verbatim).
            SectionHeader(label = "The tiers", modifier = Modifier.padding(top = OrtSpacing.lg))
            TIER_CAPABILITIES.forEach { tier -> TierCapabilityRow(tier) }

            SectionHeader(label = "Override", modifier = Modifier.padding(top = OrtSpacing.lg))
            RadioRow(
                label = "Let the phone choose",
                selected = !state.isOverridden,
                onClick = { onSelectOverride(null) },
                subtitle = "drops a tier when warm or behind, comes back when it can, tells you both times",
            )
            // Register R-932 (Reviewer D2, run 3, polish): "Hold at T0"/"T1"/"T2" were abbreviations
            // against this same screen's own "tier 1/2/3" prose above and the guide's "enum values
            // are prose" rule — `Settings-Tier.dc.html`'s own example reads "Hold at tier 2". The
            // underlying override identifier (`SettingsStore.tierOverrideName`, "T0"/"T1"/"T2" —
            // read verbatim elsewhere, e.g. the root Settings row's "held at T2") is unchanged;
            // only this row's own displayed label is prose now. "The tiers this device can hold"
            // (the finding's own phrase) is every sub-maximum tier — T0..T2, `MAX_TIER` (3) excluded
            // since holding at the max is what "Let the phone choose" already does whenever nothing
            // sheds — there is no per-device tier ceiling below that to further restrict against:
            // `SettingsTierScreen`'s own "No measured tier detector exists yet (FR-TIER-1)" notice
            // already states this build has no such detector, so every device offers the same three.
            listOf(0, 1, 2).forEach { tier ->
                val tierId = "T$tier"
                RadioRow(
                    label = "Hold at tier $tier",
                    selected = state.isOverridden && state.overrideLabel == "Held at $tierId",
                    onClick = { onSelectOverride(tierId) },
                    subtitle = OVERRIDE_CONSEQUENCES[tierId],
                )
            }
            Column(modifier = Modifier.padding(bottom = OrtSpacing.lg)) {}
        }
    }
}

/** One row of [TIER_CAPABILITIES] — tier number, name, and what it does/does not know. */
private data class TierCapability(val ordinal: Int, val title: String, val detail: String)

/** `Settings-Tier.dc.html` verbatim (R-134) — static per-tier architecture facts, true regardless
 * of which tier is currently running, so no live signal is needed to state them. */
private val TIER_CAPABILITIES: List<TierCapability> = listOf(
    TierCapability(
        ordinal = 1,
        title = "Capture and a live transcript",
        detail = "Tiny model only. No voice match, no Pass C, no threads. Attributes only what it " +
            "hears spelled out plainly — everything else is unknown, not guessed. The field-phone tier.",
    ),
    TierCapability(
        ordinal = 2,
        title = "Adds the small model and voice matching",
        detail = "Fewer transcript errors, inferred attributions, threads. Pass C off — ambiguous " +
            "overs stay ambiguous rather than resolve from the audio. Where this phone lands when warm.",
    ),
    TierCapability(
        ordinal = 3,
        title = "Everything",
        // Amended 2026-09-10 (`Settings-Tier.dc.html`, FR-DIG-3b, D5): names the prose-digest
        // capability — the *only* tier that ever loads the bundled language model, and only for
        // prose summaries, never for callsigns. Lower tiers need no matching sentence of their
        // own: neither mentions loading a language model at all, which is itself the honest "not
        // this tier" statement (constitution I — an absent claim, not a second negative one).
        detail = "Pass C resolves callsigns against the audio itself, not the transcript. The most " +
            "callsigns, and the ones it is least sure of are still marked that way. The only tier " +
            "that loads the bundled language model — for prose summaries, never for callsigns.",
    ),
)

/** Register R-974 (Reviewer D3, run 4a, polish): "Hold at tier 0" and "Hold at tier 1" carried no
 * sub-line at all while "Let the phone choose" and "Hold at tier 2" both did — the same
 * cost/trade-off shape [TIER_CAPABILITIES] itself already documents per tier, just never surfaced
 * here for these two. Each sentence is sourced from that same table's own ordinal-1/ordinal-2
 * detail text (never invented): T0 forgoes voice matching and threads entirely (ordinal 1's
 * "Tiny model only. No voice match... no threads"); T1 restores those but still leaves Pass C off
 * (ordinal 2's "Pass C off — ambiguous overs stay ambiguous"). T2's own pre-existing sentence
 * already carries the same shape one tier up (Pass C deferred to Improve, one below the max). */
private val OVERRIDE_CONSEQUENCES: Map<String, String?> = mapOf(
    "T0" to "the coolest, longest-running hold · no voice matching, no threads, plainly spelled callsigns only",
    "T1" to "voice matching and threads restored · Pass C still off, ambiguous overs stay ambiguous until run later",
    "T2" to "cooler and longer on battery · Pass C can be run later from Improve records",
)

@Composable
private fun TierCapabilityRow(tier: TierCapability, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = OrtSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(OrtSpacing.md),
    ) {
        Text(
            text = "${tier.ordinal}",
            style = OrtType.rowTitle,
            color = OrtColors.textHigh,
            modifier = Modifier.padding(top = 1.dp),
        )
        Column {
            Text(text = tier.title, style = OrtType.control, color = OrtColors.textHigh)
            Text(
                text = tier.detail,
                style = OrtType.subLine,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
