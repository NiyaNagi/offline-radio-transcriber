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
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.Sheet
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * S01 (`Setup-Welcome.dc.html`, R-080) — the only setup screen with no [SetupScaffold] chrome: no
 * back target (it is the first screen), no step indicator (the sequence has not begun), just the
 * app wordmark, the offline promise, the five things setup will ask for, `Begin`, and a `Sheet`
 * carrying `Settings-About.dc.html`'s own "the offline promise" copy verbatim (P8's own board
 * cites that file for this text — this package does not invent it).
 *
 * R-123/R-220 (register R-120..R-125, R-220..R-227): `Column`'s own layout already keeps the
 * fixed [WelcomeFooter] and the scrollable content above it from ever overlapping, at any font
 * scale, with no extra machinery — the unweighted [WelcomeFooter] is always measured for its real
 * height first, and the `weight(1f)` scrollable region above it gets exactly what is left. An
 * earlier version of this fix added a redundant `onGloballyPositioned`-measured, state-fed-back
 * `Spacer` on top of that already-correct layout; removed after register R-220 (validator finding)
 * traced a static, reproducible ghost render to exactly that class of same-frame
 * state-write-back-into-a-sibling's-measurement pattern (`SetupScaffold`'s own doc comment carries
 * the fuller account — the two fixes are identical in shape and were removed together).
 *
 * **R-360 (validator pass 5, spec — reopens R-341, same defect a third time, `SetupScaffold`'s own
 * class doc has the full account):** the `weight(1f)` account above is correct once *settled*, not
 * on the *first frame* at a large font scale — confirmed clipped on a real device at 2.0 on cold
 * launch (`setup/S01-welcome-pass5@2x.png` vs. `-afterscroll.png`). Rebuilt on `SubcomposeLayout`,
 * structurally identical to WP11b's `FailureActionBarScaffold` (R-292) and to `SetupScaffold`'s own
 * R-360 fix: [WelcomeFooter] measured first (loose constraints), the header + scrollable asks second
 * with a hard `maxHeight` of `screenHeight − footerHeight`.
 */
@Composable
public fun WelcomeScreen(onBegin: () -> Unit, modifier: Modifier = Modifier) {
    var sheetOpen by remember { mutableStateOf(false) }

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
                WelcomeFooter(onBegin = onBegin, onWhatIsCaptured = { sheetOpen = true })
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
                    WelcomeAsks()
                }
            }.map { it.measure(contentConstraints) }

            layout(constraints.maxWidth, constraints.maxHeight) {
                contentPlaceables.forEach { it.placeRelative(0, 0) }
                footerPlaceables.forEach { it.placeRelative(0, constraints.maxHeight - footerHeightPx) }
            }
        }

        if (sheetOpen) {
            WelcomeSheet(onDismiss = { sheetOpen = false })
        }
    }
}

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
            text = "Everything your radio heard, written down, on this phone only.",
            style = OrtType.screenTitle,
            color = OrtColors.textHigh,
            modifier = Modifier.padding(top = 22.dp),
        )
        Text(
            text = "Transcription, callsign resolution and the log all run here. No account, " +
                "no upload, no network in the capture path — ever.",
            style = OrtType.bodyProse,
            color = OrtColors.textSecondary,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
}

@Composable
private fun WelcomeAsks() {
    Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
        Text(
            text = "Setup takes a few minutes and asks for".uppercase(),
            style = OrtType.sectionLabel,
            color = OrtColors.textFaint,
        )
        WELCOME_ASKS.forEachIndexed { index, (text, optional) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${index + 1}",
                    style = OrtType.signal,
                    color = OrtColors.textLow,
                    modifier = Modifier.size(width = 22.dp, height = 16.dp),
                )
                Text(text = text, style = OrtType.rowTitle, color = OrtColors.textBody, modifier = Modifier.weight(1f))
                if (optional) {
                    Text(text = "optional", style = OrtType.signal, color = OrtColors.textFaint)
                }
            }
        }
    }
}

@Composable
private fun WelcomeFooter(onBegin: () -> Unit, onWhatIsCaptured: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.md),
        verticalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
    ) {
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
private fun WelcomeSheet(onDismiss: () -> Unit) {
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
        Sheet(title = "What is captured", modifier = Modifier.testTag("setup-welcome-sheet")) {
            Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                OFFLINE_PROMISE_POINTS.forEach { point ->
                    Row(modifier = Modifier.padding(vertical = 9.dp)) {
                        Box(
                            modifier = Modifier
                                .padding(top = 5.dp, end = 11.dp)
                                .size(9.dp)
                                .background(OrtColors.accentGreen, CircleShape),
                        )
                        Text(text = point, style = OrtType.subtitle, color = OrtColors.textBody)
                    }
                }
            }
        }
    }
}

/** `Setup-Welcome.dc.html`'s five numbered asks, in board order — [Boolean] is whether the row
 * carries the "optional" tag (only the radio row does). */
private val WELCOME_ASKS: List<Pair<String, Boolean>> = listOf(
    "Microphone and notifications" to false,
    "Which input is the radio — and proof that it is" to false,
    "An input level set against the noise floor" to false,
    "Permission to keep running overnight" to false,
    "A radio to read the frequency from" to true,
)

/** `Settings-About.dc.html`'s "the offline promise" section, verbatim — the same three bullets,
 * not a paraphrase, so this Sheet and the About screen never say something different about the
 * same guarantee (guide §9: never fabricate, never drift). */
private val OFFLINE_PROMISE_POINTS: List<String> = listOf(
    "No part of capture, transcription, resolution or the reader can reach the network. The " +
        "build enforces it: only one module may link an HTTP client, and none of these is that module.",
    "Voiceprints, the names you give stations, what this phone has learned about who is around " +
        "when, and your location never leave it — not in an export, a contribution, a diagnostic " +
        "bundle or a backup.",
    "Nothing is deleted quietly. Every attribution carries its confidence. A weaker phone knows " +
        "less; it is never more wrong.",
)
