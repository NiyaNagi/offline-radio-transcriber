package org.ort.app.ui.failures

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.Banner
import org.ort.app.ui.components.BannerTone
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.Sheet
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * F1 — `Fail-Route.dc.html`. WP11b, register R-100/R-101: a full-screen takeover, the one halting
 * state driven by a real signal today ([org.ort.pipeline.capture.InputStatus.State.Mismatch] —
 * see `FailureMapper.kt`'s kdoc). Constitution IV: "a route that is not the selected device halts
 * capture" — there is deliberately no "continue anyway".
 */
@Composable
public fun FailRouteScreen(
    state: RouteViewState,
    onChooseInputAgain: () -> Unit,
    onEndSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showWhyThisHalts by remember { mutableStateOf(false) }

    // FR-A11Y-3/guide §4: at maximum font scale this content can outgrow the screen, so it scrolls
    // — but `weight` and `verticalScroll` cannot share one Column (Compose throws), so the scroll
    // lives on the inner content Column and `weight` stays on the outer, non-scrolling one; the
    // button bar this way always stays reachable, never pushed off either end.
    // R-126/R-127: the 44dp status-bar inset every Setup screen already carries
    // (`SetupScaffold.kt`'s own `.windowInsetsPadding(WindowInsets.statusBars)`) — a takeover is a
    // full-bleed screen exactly like those, so it needs the same inset or "Halted" collides with
    // the clock.
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OrtColors.bgScreen)
            .failureScreenInset()
            .testTag("failure-route-screen"),
    ) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(
                modifier = Modifier.padding(start = OrtSpacing.lg, top = 10.dp, end = OrtSpacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                HaltDot()
                Text(text = "Halted", style = OrtType.screenTitle, color = OrtColors.textHigh)
            }
            Text(
                text = routeSubtitle(state),
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(start = OrtSpacing.lg, top = 3.dp, end = OrtSpacing.lg),
            )
            Banner(
                title = "Audio switched to the built-in microphone",
                body = "At ${state.sinceLabel} the route changed from ${state.expectedLabel} to " +
                    "${state.actualLabel}. Capture stopped in the same second. Nothing from this mic was recorded.",
                tone = BannerTone.HALTING,
                primaryActionLabel = "Choose the input again",
                onPrimaryAction = onChooseInputAgain,
                secondaryActionLabel = "Why this halts",
                onSecondaryAction = { showWhyThisHalts = true },
                modifier = Modifier.padding(horizontal = OrtSpacing.lg).padding(top = 16.dp),
            )
            Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = 16.dp)) {
                SectionLabel("Audio")
                InfoRow(key = "Input", value = state.expectedLabel, sub = "chosen · not currently routed")
                InfoRow(key = "Routed to", value = state.actualLabel)
            }
            NotRecordedSection(state)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = OrtSpacing.lg, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryButton(
                text = "Choose the input again",
                onClick = onChooseInputAgain,
                modifier = Modifier.fillMaxWidth().testTag("failure-route-choose-input"),
            )
            TextAction(
                text = "End the session here",
                onClick = onEndSession,
                modifier = Modifier.fillMaxWidth().testTag("failure-route-end-session"),
            )
        }
    }

    if (showWhyThisHalts) {
        WhyThisHaltsSheet(onDismiss = { showWhyThisHalts = false })
    }
}

/** R-126: `Fail-Route.dc.html`'s "Why this halts" link — a scrim + WP2's [Sheet] surface (guide
 * §6.9: dismissing by the scrim or a close action is the host screen's job, not [Sheet]'s own).
 * Cites FR-CAP-3/FR-CAP-3a/FR-RUN-13 (the actual halt rule — "a route that is not the selected
 * device halts capture, never a silent fallback") and FR-CAP-2a (why the exact device matters:
 * every retained recording carries the input rate and resampler identity that produced it, and a
 * silently substituted device would make that identity a lie for whatever came after the swap). */
@Composable
private fun WhyThisHaltsSheet(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClickLabel = "Dismiss", onClick = onDismiss)
            .testTag("failure-route-why-this-halts-sheet"),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Sheet(title = "Why this halts") {
            Text(
                text = "A route that is not the selected device halts capture (FR-CAP-3) — recording the " +
                    "room instead of the radio is the highest-consequence silent failure in the system. The " +
                    "built-in mic is a legitimate choice on its own (FR-CAP-3a), but never a silent " +
                    "substitute for a USB device that dropped off the bus. Every retained recording carries " +
                    "the exact input rate and resampler identity that produced it (FR-CAP-2a); a route " +
                    "swapped mid-session would make that identity a lie for whatever came after. Route " +
                    "changes are re-verified against this same rule for the rest of the session (FR-RUN-13).",
                style = OrtType.control,
                color = OrtColors.textBody,
                modifier = Modifier.padding(top = 6.dp),
            )
            TextAction(
                text = "Close",
                onClick = onDismiss,
                modifier = Modifier.padding(top = 12.dp).testTag("failure-route-why-this-halts-close"),
            )
        }
    }
}

/** R-126: `Fail-Route.dc.html`'s own subtitle pattern, "HH:MM:SS · after H:MM:SS · N overs kept"
 * — never the elapsed/count clauses when there is no session to measure against (constitution I:
 * never invented). */
private fun routeSubtitle(state: RouteViewState): String = if (state.sessionElapsedKnown) {
    "${state.sinceLabel} · after ${state.elapsedLabel} · ${state.oversKeptCount} overs kept"
} else {
    "${state.sinceLabel} · capture will not continue on this route"
}

@Composable
private fun NotRecordedSection(state: RouteViewState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(horizontal = OrtSpacing.lg)) {
        SectionLabel("Not recorded")
        Text(
            text = "${state.sinceLabel} onward is a gap, reason route lost",
            style = OrtType.subtitle,
            color = OrtColors.textBody,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "The app would rather record nothing than record the room. When the selected input " +
                "is back, choosing it again re-runs the route check; the session resumes and the gap " +
                "is closed with its length.",
            style = OrtType.cardBody,
            color = OrtColors.textDim,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** R-126/R-127: the 44dp status-bar inset every full-screen failure takeover/screen needs, shared
 * by every `Fail*Screen` in this package so the inset itself is defined in exactly one place —
 * see `FailRouteScreen`'s own kdoc for why (`SetupScaffold.kt`'s identical inset is the reference). */
@Composable
internal fun Modifier.failureScreenInset(): Modifier = this.windowInsetsPadding(WindowInsets.statusBars)

/** The halt dot beside a "Halted" title (`Fail-Route.dc.html`, `Fail-Storage.dc.html`'s own halt stage). */
@Composable
internal fun HaltDot(modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(9.dp).background(OrtColors.haltText, CircleShape))
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = OrtType.sectionLabel,
        color = OrtColors.textFaint,
        modifier = modifier.padding(bottom = 4.dp),
    )
}

@Composable
internal fun InfoRow(key: String, value: String, modifier: Modifier = Modifier, sub: String? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$key: $value${sub?.let { " ($it)" } ?: ""}" },
    ) {
        Text(
            text = key,
            style = OrtType.subtitle,
            color = OrtColors.textDim,
            modifier = Modifier.padding(end = 12.dp),
        )
        Column {
            Text(text = value, style = OrtType.control, color = OrtColors.textHigh)
            sub?.let { Text(text = it, style = OrtType.subLine, color = OrtColors.textFaint) }
        }
    }
}
