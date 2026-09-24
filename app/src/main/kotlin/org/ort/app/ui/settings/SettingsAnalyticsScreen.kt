package org.ort.app.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.ort.app.analytics.AnalyticsAppWiring
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.FailedState
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.ToggleRow
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType
import org.ort.telemetry.AnalyticsTier

/**
 * [SettingsAnalyticsScreen]'s opt-in disclosure block — a real Compose `testTag`, so the assertion
 * that the block is present is rooted in this screen rather than in whatever else happens to render
 * the same words (R-1160, R-1070). Read back by
 * `SettingsAnalyticsScreenTest` and, once it is captured, by the tour.
 */
public const val ANALYTICS_OPT_IN_NOT_COLLECTED_TAG: String = "analytics-opt-in-not-collected"

/**
 * P28 (D42, FR-ANL-1..14): three toggles, one per tier, each stating in plain language what that
 * tier sends when on (FR-ANL-9) — matching FR-ANL-2..4's own field lists verbatim, the same
 * "prose that becomes an FR-tested claim" discipline `SettingsContributeScreen.kt` already applies
 * to its own category list. Tier 1's toggle can turn tier 1 off entirely (FR-ANL-1); tiers 2 and 3
 * are opt-in and start unchecked. [SettingsAnalyticsViewState.destinationConfigured] renders the
 * honest D48 state: `false` (the default today, no endpoint deployed) means every event queues
 * on-device and nothing is ever sent, whatever the three toggles say.
 *
 * **R-1086 (register; constitution I; D42, FR-ANL-2):** tier 1's row used to say "Crashes and
 * ANRs", but no ANR-detection mechanism exists anywhere in this codebase — no watchdog runs, and
 * [org.ort.telemetry.AnalyticsTier1Payload.Crash.isAnr] is deliberately nullable ("never
 * measured") for exactly that reason ([org.ort.app.analytics.CrashPayloads]'s own doc comment).
 * "and ANRs" is dropped here, from setup's identical paragraph (that step was
 * `AnalyticsConsentScreen` when R-1086 was fixed; D58 folded it into `WelcomeScreen`'s analytics
 * sheet, so the KDoc link is spelled out rather than left dangling), from
 * `design/canvas/Settings-Analytics.dc.html` and
 * `design/canvas/Setup-Analytics-Consent.dc.html`, and from `docs/privacy-policy.md`'s tier 1
 * description.
 *
 * **R-1193 (register; constitution I; D42, FR-ANL-3, FR-ANL-4):** the same defect one layer up, and
 * worse, because the subject is the operator's own recordings. **Tier 3 has no producer anywhere in
 * this repository** — nothing constructs an [org.ort.telemetry.AnalyticsTier3Payload] outside a
 * test — and [org.ort.telemetry.AnalyticsTier2Payload.Transcript] has none either; the one live
 * tier-2 path is [org.ort.app.analytics.CorrectionAnalytics], which sends the callsign pair alone.
 * The two rows still state FR-ANL-2..4's field lists because FR-ANL-9 requires exactly that, so the
 * correction is a disclosure rather than a rewrite: the [FailedState] below says plainly that
 * neither tier is collected yet, the same shape
 * [org.ort.app.ui.settings.SettingsContributeScreen] already uses for the contribution channel's
 * missing upload client. The toggles stay live and keep writing through, because the operator's
 * recorded choice is the standing consent a later producer would need (FR-ANL-9's "three toggles"
 * is not satisfied by two and a disabled control).
 *
 * The disclosure's truth is not asserted by this screen's own test alone — that would be another
 * claim proving itself. `org.ort.telemetry.AnalyticsProducerReachabilityTest` asserts the
 * production fact (no `src/main` source in the repository constructs either payload) and goes red
 * the moment a producer is built, which is what forces this copy to change in the same commit.
 */
@Composable
public fun SettingsAnalyticsScreen(
    state: SettingsAnalyticsViewState,
    onBack: () -> Unit,
    onToggleTier1: (Boolean) -> Unit,
    onToggleTier2: (Boolean) -> Unit,
    onToggleTier3: (Boolean) -> Unit,
    onResetInstallId: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DrillInHeader(parentLabel = "Settings", onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
            Text(text = "Analytics", style = OrtType.screenTitle, color = OrtColors.textHigh)
            Text(
                text = if (state.destinationConfigured) {
                    "Queued events send only while capture is not running."
                } else {
                    "No destination is configured in this build — events queue on this phone and " +
                        "nothing is ever sent."
                },
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
            )

            SectionHeader(label = "On by default", modifier = Modifier.padding(top = OrtSpacing.md))
            ToggleRow(
                label = "Usage and quality (tier 1)",
                checked = state.tier1Enabled,
                onCheckedChange = onToggleTier1,
                subLine = "Crashes, screens and actions used, per-pass speed, capture " +
                    "uptime, the setup funnel, and aggregate quality rates — never a transcript, " +
                    "callsign, name, station knowledge or location.",
            )

            SectionHeader(label = "Opt-in", modifier = Modifier.padding(top = OrtSpacing.md))
            ToggleRow(
                label = "Transcripts and callsigns (tier 2)",
                checked = state.tier2Enabled,
                onCheckedChange = onToggleTier2,
                subLine = "Transcript text and callsigns, including what the recognizer heard " +
                    "paired with your correction.",
            )
            ToggleRow(
                label = "Audio (tier 3)",
                checked = state.tier3Enabled,
                onCheckedChange = onToggleTier3,
                subLine = "Retained over audio together with its corrected transcript.",
            )
            FailedState(
                title = "Neither opt-in tier is collected in this build",
                body = "No audio is ever recorded for analytics here, so the tier 3 switch changes " +
                    "nothing yet — your choice is kept for when it does. Tier 2 collects only the " +
                    "callsign you chose and the one it replaced; no transcript text is collected.",
                modifier = Modifier
                    .padding(top = OrtSpacing.sm)
                    .testTag(ANALYTICS_OPT_IN_NOT_COLLECTED_TAG),
            )

            SectionHeader(label = "Never in any tier", modifier = Modifier.padding(top = OrtSpacing.md))
            Text(
                text = "A user-supplied name, station knowledge, or location finer than a grid " +
                    "square never leaves the device through this channel, in any combination of " +
                    "tiers above.",
                style = OrtType.cardBody,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.md),
            )

            SectionHeader(label = "This install", modifier = Modifier.padding(top = OrtSpacing.md))
            Text(
                text = state.installIdLabel,
                style = OrtType.cardBody,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs),
            )
            org.ort.app.ui.components.NavRow(
                rowTitle = "Reset install id",
                subLine = "Starts a new, unlinked id and asks the destination to erase every row " +
                    "tied to the old one.",
                onClick = onResetInstallId,
                icon = org.ort.app.ui.components.OrtIcons.lock,
            )
        }
    }
}

/** [SettingsAnalyticsScreen]'s own state-builder — deliberately not added to `SettingsPolling.kt`
 * (outside this unit's narrow, named integration point; see `SettingsRootScreen.kt`'s own doc
 * comment on the identical choice P27 made for `SettingsLicensesScreen`). Calls
 * [AnalyticsAppWiring.configureOnce] defensively so this screen renders real state even if
 * `OrtApplication.onCreate` has not run yet in whatever hosts it (a Robolectric test's own
 * `Application`, `OrtApplication`, never calls it at all — see that class's own Robolectric guard). */
public object SettingsAnalyticsPolling {
    public fun current(context: Context): SettingsAnalyticsViewState {
        AnalyticsAppWiring.configureOnce(context)
        val installId = AnalyticsAppWiring.installIdStore.currentId()
        return SettingsAnalyticsViewState(
            tier1Enabled = AnalyticsAppWiring.tierPreferences.isEnabled(AnalyticsTier.TIER_1),
            tier2Enabled = AnalyticsAppWiring.tierPreferences.isEnabled(AnalyticsTier.TIER_2),
            tier3Enabled = AnalyticsAppWiring.tierPreferences.isEnabled(AnalyticsTier.TIER_3),
            destinationConfigured = AnalyticsAppWiring.isDestinationConfigured(),
            installIdLabel = "id ...${installId.takeLast(SHORT_ID_CHARS)}",
        )
    }

    private const val SHORT_ID_CHARS = 8
}
