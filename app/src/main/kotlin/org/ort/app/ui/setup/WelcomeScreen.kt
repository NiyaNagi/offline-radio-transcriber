package org.ort.app.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import org.ort.app.ui.OfflinePromiseCopy
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.Sheet
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.components.ToggleRow
import org.ort.app.ui.components.safeAreaBottomPadding
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * The two disclosures Welcome now carries, and what the operator can do about them without leaving
 * this screen. [destinationConfigured] is D48's own fact, rendered for the same reason
 * `SettingsAnalyticsScreen` renders it (R-1085): a screen that implies the two toggles below share
 * data, in a build where nothing leaves the device whatever they choose, sets a false expectation on
 * the one surface most likely to set it.
 */
public data class WelcomeAnalyticsState(
    val tier2Enabled: Boolean = false,
    val tier3Enabled: Boolean = false,
    val destinationConfigured: Boolean = false,
)

/**
 * **Welcome — the first of four screens (P39, D58, AC-198).**
 *
 * The only setup screen with no [SetupScaffold] chrome: no back target (it is the first screen), no
 * step indicator (the sequence has not begun). One line on what the app does, the FR-ANL-14 sentence
 * **once and only here** (AC-203, from [OfflinePromiseCopy.WELCOME_PROMISE] — the approved wording
 * lives there, and is never retyped), the two notices this flow used to spend a screen each on, and
 * `Begin`.
 *
 * **The jurisdiction notice is folded in, not deleted (AC-166 as amended by D58).** That criterion
 * requires the notice to be *shown and acknowledged before capture*, not to occupy a step of its own:
 * it is a row here, with its full text one tap away in a sheet, and `Begin` is the acknowledgement.
 * The shown-once and cannot-start-until-dismissed halves are unchanged and still binding —
 * [SetupStateMachine.stepFor]'s `welcomeSeen` gate is what enforces them, and
 * [SetupActivity.onBegin] writes `jurisdictionNoticeSeen` as it always did.
 *
 * **The analytics disclosure is folded in the same way (AC-180 as amended).** The explanation and the
 * two **unchecked** toggles are present and reachable within the first-run flow — one tap, in the
 * second sheet — and declining both still costs nothing (FR-ANL-10). Neither ever gates anything.
 *
 * **Copy (D58).** The screen used to list five numbered things setup would ask for; four of those
 * asks no longer exist, and the list was describing a flow that has been deleted. It is gone. What is
 * left is one claim, two notices and a button. **The jurisdiction sheet deliberately no longer
 * repeats "everything this app records stays on this device unless you choose to export or share
 * it"** — that is a second privacy claim on the same screen, which AC-203 forbids, and
 * [OfflinePromiseCopy.WELCOME_PROMISE] above already says it correctly.
 *
 * **R-360 (validator pass 5, spec, `SetupScaffold`'s own class doc has the full account):** the
 * fixed footer and the scrollable content are measured by [SubcomposeLayout], footer first with loose
 * constraints, content second with a hard `maxHeight` of `screenHeight − footerHeight`. A plain
 * `Column` + `weight(1f)` is correct once *settled* and clipped on a real device at font scale 2.0 on
 * cold launch, three separate times.
 */
@Composable
public fun WelcomeScreen(
    onBegin: () -> Unit,
    modifier: Modifier = Modifier,
    analytics: WelcomeAnalyticsState = WelcomeAnalyticsState(),
    onToggleTier2: (Boolean) -> Unit = {},
    onToggleTier3: (Boolean) -> Unit = {},
) {
    var openSheet by remember { mutableStateOf<WelcomeSheetKind?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        SubcomposeLayout(
            modifier = Modifier
                .fillMaxSize()
                .background(OrtColors.bgScreen)
                .windowInsetsPadding(WindowInsets.statusBars)
                .testTag("setup-screen-WELCOME"),
        ) { constraints ->
            val looseConstraints = Constraints(
                minWidth = constraints.maxWidth,
                maxWidth = constraints.maxWidth,
                minHeight = 0,
                maxHeight = constraints.maxHeight,
            )

            val footerPlaceables = subcompose(WelcomeScaffoldSlot.Footer) {
                WelcomeFooter(onBegin = onBegin, onWhatIsCaptured = { openSheet = WelcomeSheetKind.Promise })
            }.map { it.measure(looseConstraints) }
            val footerHeightPx = footerPlaceables.maxOfOrNull { it.height } ?: 0

            val contentHeightPx = (constraints.maxHeight - footerHeightPx).coerceAtLeast(0)
            val contentConstraints = Constraints(
                minWidth = constraints.maxWidth,
                maxWidth = constraints.maxWidth,
                minHeight = contentHeightPx,
                maxHeight = contentHeightPx,
            )
            val contentPlaceables = subcompose(WelcomeScaffoldSlot.Content) {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    WelcomeHeader()
                    WelcomeNotices(onOpen = { openSheet = it })
                }
            }.map { it.measure(contentConstraints) }

            layout(constraints.maxWidth, constraints.maxHeight) {
                contentPlaceables.forEach { it.placeRelative(0, 0) }
                footerPlaceables.forEach { it.placeRelative(0, constraints.maxHeight - footerHeightPx) }
            }
        }

        openSheet?.let { kind ->
            WelcomeSheetHost(
                kind = kind,
                analytics = analytics,
                onToggleTier2 = onToggleTier2,
                onToggleTier3 = onToggleTier3,
                onDismiss = { openSheet = null },
            )
        }
    }
}

/** The three things Welcome can open over itself — the offline promise's own detail, the jurisdiction
 * notice's full text (AC-166), and the analytics explanation with its two unchecked toggles (AC-180). */
internal enum class WelcomeSheetKind { Promise, Jurisdiction, Analytics }

private enum class WelcomeScaffoldSlot { Content, Footer }

@Composable
private fun WelcomeHeader() {
    Column(modifier = Modifier.padding(OrtSpacing.lg)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                imageVector = OrtIcons.frequencies,
                contentDescription = null,
                tint = OrtColors.accentGreen,
                modifier = Modifier.size(26.dp),
            )
            Text(
                text = "Offline radio transcriber".uppercase(),
                style = OrtType.columnHeader,
                color = OrtColors.textFaint,
            )
        }
        Text(
            text = "Everything your radio heard, written down.",
            style = OrtType.screenTitle,
            color = OrtColors.textHigh,
            modifier = Modifier.padding(top = 22.dp),
        )
        // AC-203: the FR-ANL-14 sentence, once, in the whole first-run flow. Never retyped — the
        // approved wording and its spec obligations live in OfflinePromiseCopy.
        Text(
            text = OfflinePromiseCopy.WELCOME_PROMISE,
            style = OrtType.bodyProse,
            color = OrtColors.textSecondary,
            modifier = Modifier.padding(top = 14.dp).testTag("setup-welcome-promise"),
        )
    }
}

/** The two acknowledged notices (AC-166, AC-180) — a row each, full content one tap away. They are
 * [NavigationRow]s rather than paragraphs on purpose: a row with a chevron says "there is more here
 * and you can read it", which is what "reachable" has to mean to be true. */
@Composable
private fun WelcomeNotices(onOpen: (WelcomeSheetKind) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
        Text(
            text = "Before you start".uppercase(),
            style = OrtType.sectionLabel,
            color = OrtColors.textFaint,
        )
        NavigationRow(
            title = "Recording radio traffic",
            subtitle = "The law differs by where you are. Check yours.",
            onClick = { onOpen(WelcomeSheetKind.Jurisdiction) },
            modifier = Modifier.testTag("setup-welcome-jurisdiction-row"),
            // OrtIcons has no "notice" glyph and this package does not own `ui/components` — `lock`
            // is the nearest honest one already in the set (a constraint the operator has to satisfy),
            // and it is never the only thing carrying the meaning: the row's own title and sub-line do.
            icon = OrtIcons.lock,
        )
        NavigationRow(
            title = "Usage and quality analytics",
            subtitle = "On, and closed to what it can contain. Transcripts and audio are off.",
            onClick = { onOpen(WelcomeSheetKind.Analytics) },
            modifier = Modifier.testTag("setup-welcome-analytics-row"),
            icon = OrtIcons.diagnostics,
        )
    }
}

@Composable
private fun WelcomeFooter(onBegin: () -> Unit, onWhatIsCaptured: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        // R-1003 (halt): see `SetupScaffold.kt`'s identical fix for the account -- this footer had the
        // same missing-bottom-inset defect, and the same fix folds straight into `footerHeightPx`.
        modifier = modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.md).safeAreaBottomPadding(),
        verticalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
    ) {
        // AC-166: `Begin` *is* the acknowledgement, so the screen says so rather than leaving the
        // operator to infer it. One line, stating what the tap means — not a reassurance.
        Text(
            text = "Beginning confirms you have read the two notes above.",
            style = OrtType.signal,
            color = OrtColors.textFaint,
            modifier = Modifier.fillMaxWidth().testTag("setup-welcome-acknowledgement"),
        )
        PrimaryButton(
            text = "Begin",
            onClick = onBegin,
            modifier = Modifier.fillMaxWidth().testTag("setup-welcome-begin"),
        )
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextAction(
                text = "What is captured, and what never leaves this phone",
                onClick = onWhatIsCaptured,
                modifier = Modifier.testTag("setup-welcome-what-is-captured"),
            )
        }
    }
}

@Composable
private fun WelcomeSheetHost(
    kind: WelcomeSheetKind,
    analytics: WelcomeAnalyticsState,
    onToggleTier2: (Boolean) -> Unit,
    onToggleTier3: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val scrimInteractionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OrtColors.bgPage.copy(alpha = 0.78f))
            .clickable(
                interactionSource = scrimInteractionSource,
                indication = null,
                onClickLabel = "Dismiss",
                onClick = onDismiss,
            )
            .testTag("setup-welcome-sheet-scrim"),
    )
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        when (kind) {
            WelcomeSheetKind.Promise -> PromiseSheet()
            WelcomeSheetKind.Jurisdiction -> JurisdictionSheet()
            WelcomeSheetKind.Analytics -> AnalyticsSheet(analytics, onToggleTier2, onToggleTier3)
        }
    }
}

@Composable
private fun PromiseSheet() {
    Sheet(title = "What is captured", modifier = Modifier.testTag("setup-welcome-sheet")) {
        Column(modifier = Modifier.heightIn(max = SHEET_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
            OfflinePromiseCopy.POINTS.forEachIndexed { index, point ->
                DotPoint(text = point, testTag = "setup-welcome-promise-point-$index")
            }
        }
    }
}

/**
 * AC-166's full text. The legality of recording radio transmissions varies by jurisdiction (amateur
 * and scanner traffic, two-party consent rules for voice recordings, and local law on retaining or
 * sharing it) — this states that plainly and asks the operator to confirm they have checked their own
 * local law; it makes no legal claim of its own about any specific jurisdiction (constitution I: a
 * claim this app cannot verify is never asserted as fact).
 */
@Composable
private fun JurisdictionSheet() {
    Sheet(title = "Recording radio traffic", modifier = Modifier.testTag("setup-welcome-jurisdiction-sheet")) {
        Column(
            modifier = Modifier.heightIn(max = SHEET_MAX_HEIGHT).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
        ) {
            Text(
                text = "Rules on recording amateur and scanner radio traffic — and on retaining or " +
                    "sharing what you capture — differ by country, state and sometimes locality. This " +
                    "app does not know which rules apply where you are.",
                style = OrtType.bodyProse,
                color = OrtColors.textSecondary,
                modifier = Modifier.testTag("setup-welcome-jurisdiction-body"),
            )
            DotPoint(
                text = "Check your own local law before you start an overnight or unattended session.",
                testTag = "setup-welcome-jurisdiction-point",
            )
        }
    }
}

/**
 * AC-180's explanation and its two unchecked toggles, one tap from Welcome. The toggles write straight
 * through as they are flipped (the caller's own analytics controller) — there is no `Continue` to
 * confirm, because there is nothing here that gates anything (FR-ANL-10).
 *
 * **R-1086 (register; constitution I; D42, FR-ANL-2):** the tier 1 paragraph deliberately does not say
 * "and ANRs" — no ANR-detection mechanism exists anywhere in this codebase, and a setup screen
 * promising a category the app never collects is the same defect class as a confident wrong callsign,
 * arriving as copy.
 */
@Composable
private fun AnalyticsSheet(
    analytics: WelcomeAnalyticsState,
    onToggleTier2: (Boolean) -> Unit,
    onToggleTier3: (Boolean) -> Unit,
) {
    Sheet(title = "Usage and quality analytics", modifier = Modifier.testTag("setup-welcome-analytics-sheet")) {
        Column(
            modifier = Modifier.heightIn(max = SHEET_MAX_HEIGHT).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
        ) {
            Text(
                text = "On: crashes, which screens and actions are used, per-pass speed, capture " +
                    "uptime, and aggregate quality rates. Never a transcript, callsign, name, station " +
                    "knowledge or location. Turn it off any time in Settings.",
                style = OrtType.bodyProse,
                color = OrtColors.textSecondary,
                modifier = Modifier.testTag("setup-welcome-analytics-body"),
            )
            Text(
                text = if (analytics.destinationConfigured) {
                    "Queued events send only while capture is not running."
                } else {
                    "No destination is configured in this build — events queue on this phone and " +
                        "nothing is ever sent, whatever you choose below."
                },
                style = OrtType.cardBody,
                color = OrtColors.textFaint,
                modifier = Modifier.testTag("setup-analytics-destination-disclosure"),
            )
            ToggleRow(
                label = "Also share transcripts and callsigns",
                checked = analytics.tier2Enabled,
                onCheckedChange = onToggleTier2,
                subLine = "Off unless you turn it on — includes what the recognizer heard paired with " +
                    "your correction.",
            )
            ToggleRow(
                label = "Also share audio",
                checked = analytics.tier3Enabled,
                onCheckedChange = onToggleTier3,
                subLine = "Off unless you turn it on — retained over audio with its corrected transcript.",
            )
        }
    }
}

@Composable
private fun DotPoint(text: String, testTag: String) {
    Row(modifier = Modifier.padding(vertical = 9.dp)) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp, end = 11.dp)
                .size(9.dp)
                .background(OrtColors.accentGreen, CircleShape),
        )
        Text(
            text = text,
            style = OrtType.subtitle,
            color = OrtColors.textBody,
            modifier = Modifier.testTag(testTag),
        )
    }
}

private val SHEET_MAX_HEIGHT = 320.dp
