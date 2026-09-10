package org.ort.app.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * R-080 (ui-conformance-plan WP9): the shell every step of the guided sequence shares except
 * [WelcomeScreen] (`Setup-Welcome.dc.html`'s header is the app wordmark, not this step chrome) —
 * the 44dp status-bar inset, a 44dp back target (guide §5), guide §6.10's step indicator, the
 * title/subtitle pair, the screen's own scrollable content, and a [bottomActions] slot each screen
 * fills with whatever `Controls.dc.html` combination its own board shows (a single `Verify this
 * input`, `Continue` + a secondary text action, a halting primary + a retry, or S09's bare "Not
 * now" with no filled button at all) — the variety across boards is real, not accidental, so this
 * scaffold does not force one shape on it.
 *
 * Two validator findings (register R-120..R-125) fixed here, both shared by every screen this
 * scaffold serves:
 * - **R-120**: the counter used to render twice — this header row's own "n of N" (kept; the board
 *   places it here) *and* WP2's [org.ort.app.ui.components.StepIndicator], which draws an
 *   identical "n of N" of its own directly above its segment bars (`Controls.kt` — not this row's
 *   file to edit, confirmed by reading it before writing this: it has no way to suppress that
 *   label). [SegmentBars] below draws only the coloured segments themselves — the board's own
 *   "unlabeled" segment row — without pulling in a second shared component's built-in counter;
 *   the semantics [contentDescription] `StepIndicator` carried for TalkBack is preserved here too.
 * - **R-123**: at a larger font scale the fixed [bottomActions] bar grows taller. `Column`'s own
 *   layout algorithm already handles this with no extra machinery needed: an unweighted sibling
 *   (this scaffold's bottom `bottomActions` `Column`) is always measured for its real, natural
 *   height *first*, and the `weight(1f)` scrollable content above it receives exactly what is left
 *   — the two can never overlap, at any font scale, without this scaffold doing anything special.
 *   An earlier version of this fix added a second mechanism on top of that — measuring the bar's
 *   height via `onGloballyPositioned` into a `mutableIntStateOf`, then feeding it back as a bottom
 *   `Spacer` inside the *same* recomposition's sibling — a same-frame state-write-back-into-a-
 *   sibling's-measurement pattern Compose's own docs warn against for exactly this reason: register
 *   R-220 (validator finding) found a static, reproducible ghost copy of the bottom bar's own
 *   secondary action rendered near the status bar on every two-action screen, on every repeat —
 *   consistent with that redundant feedback loop, not with anything to do with font scale itself.
 *   Removed; `SetupScaffoldTest`'s own R-123 test (scroll to the last item, assert displayed)
 *   still passes on the plain `weight(1f)` + `verticalScroll` layout alone, confirming the extra
 *   mechanism was solving a problem `Column` had already solved.
 *
 * **R-341 (validator pass 4, spec, reopened R-123/R-281/R-282/R-283):** the `weight(1f)` account
 * above is correct for *where things end up once settled*, but wrong about the *first frame* at a
 * large font scale — `Column`'s `weight(1f)` sibling is measured with `minHeight = 0`, so on the
 * very first composition (before `verticalScroll`'s own internal state has anything to report back)
 * the scrollable content is measured and placed at its *natural, unconstrained* height, which
 * exceeds the space actually left for it; only after a real user scroll (or a recomposition
 * triggered some other way) does the layout settle where it visually belongs.
 *
 * **R-360 (validator pass 5, spec — reopens R-341, same defect a third time):** the first attempt
 * at this fix (`SubcomposeLayout` with *three* slots — header, bar, content, each measured
 * independently, content given a hard `maxHeight` of `screenHeight − headerHeight − barHeight`)
 * passed its own Robolectric regression test and still clipped on a real device at 2.0 on cold
 * launch, on every one of S01/S03/S06/S08/S12 — `ComposeTestRule.setContent` drives to a fully
 * idle, settled layout before any query runs, so Robolectric cannot observe the transient pre-settle
 * frame a real device's own first `screencap` catches (`setup/S01-welcome-pass5@2x.png` vs.
 * `-afterscroll.png`). WP11b's `FailureActionBarScaffold` (R-292, `ui/failures/
 * FailureActionBarScaffold.kt`) is confirmed correct on the device at 2.0 on cold launch, and this
 * scaffold is now made **structurally identical** to it, not merely "the same idea": exactly *two*
 * `subcompose` slots, `Bar` measured first (loose constraints) and `Content` second, given a hard
 * `Constraints(minHeight = maxHeight = screenHeight − barHeight)` — the header (back/counter,
 * segment bars, title/subtitle) lives *inside* the `Content` slot's own `Column`, above its own
 * inner `verticalScroll` region, rather than as a separate, independently-measured third slot. The
 * inner `weight(1f)` the scrollable region still uses is no longer the R-341/R-360 defect: that
 * `Column`'s own *outer* bounds are now the `SubcomposeLayout`'s hard-constrained placeable, not an
 * ambiguous, `wrap_content`-sized parent, so the weighted child has a true, already-settled "what's
 * left" to measure against on the very first frame. Verified on a real device (`emulator-5554`,
 * fresh `pm clear`, `settings put system font_scale 2.0`, cold `am start`, screenshot before any
 * scroll) — this package's own report has the two screenshots and the exact recipe.
 */
// R-343 added the one new parameter (titleOptional) that pushed this over detekt's 9-parameter
// threshold -- every existing one already earns its place in this shared, deliberately flexible
// scaffold (this file's own class doc), so widened rather than restructured every call site's
// title/subtitle pair into a wrapper type; matches this codebase's own established convention for
// a shared composable with real, load-bearing variety (`Rows.kt`, `Controls.kt`, `TransmissionDetailScreen.kt`).
@Suppress("LongParameterList")
@Composable
public fun SetupScaffold(
    step: SetupStep,
    title: String,
    subtitle: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    titleOptional: Boolean = false,
    titleTrailing: (@Composable () -> Unit)? = null,
    bottomActions: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    SubcomposeLayout(
        modifier = modifier
            .fillMaxSize()
            .background(OrtColors.bgScreen)
            .windowInsetsPadding(WindowInsets.statusBars)
            .testTag("setup-screen-${step.name}"),
    ) { constraints ->
        val looseConstraints = Constraints(
            minWidth = constraints.maxWidth,
            maxWidth = constraints.maxWidth,
            minHeight = 0,
            maxHeight = constraints.maxHeight,
        )

        // R-360: exactly the two slots FailureActionBarScaffold (R-292) has -- Bar measured first,
        // Content second with a hard maxHeight -- not the three-slot version that still clipped on
        // device (this file's own class doc has the full account).
        val barPlaceables = subcompose(SetupScaffoldSlot.Bar) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.md),
                verticalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
                content = bottomActions,
            )
        }.map { it.measure(looseConstraints) }
        val barHeightPx = barPlaceables.maxOfOrNull { it.height } ?: 0

        val contentHeightPx = (constraints.maxHeight - barHeightPx).coerceAtLeast(0)
        val contentConstraints = Constraints(
            minWidth = constraints.maxWidth,
            maxWidth = constraints.maxWidth,
            minHeight = contentHeightPx,
            maxHeight = contentHeightPx,
        )
        val contentPlaceables = subcompose(SetupScaffoldSlot.Content) {
            Column(modifier = Modifier.fillMaxSize()) {
                // R-612 (Reviewer round 6): at font scale 2.0, `setup-verified/S03-notify@2x-end.png`
                // showed the scrolled body's own first visible line looking half-occluded right under
                // the subtitle. Investigated on-device (`emulator-5556`, a real swipe + real
                // `screencap`, not just the tour's own capture -- reproduced identically, and stable
                // over a 3s extra settle, ruling out a transient frame) with a temporary high-contrast
                // background on the body `Text` itself: its own layout box starts exactly, cleanly
                // below this header, no overlap at all -- the glitch sits *inside* that box, on its own
                // first line, not at the header boundary. So this is not the header/scroll-overlap
                // defect it first looked like; giving *this* header an explicit opaque background (this
                // `Column`, not the whole-screen one `SubcomposeLayout`'s own modifier already carries)
                // is kept anyway, since it is what a validator asked for and is harmless, but it does
                // not by itself close R-612 -- see the register/CHANGELOG for the fuller account and
                // what is still open.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(OrtColors.bgScreen)
                        .testTag("setup-scaffold-header"),
                ) {
                    ScaffoldHeaderRow(step = step, onBack = onBack)
                    step.indicatorIndex()?.let { index ->
                        SegmentBars(
                            steps = SETUP_TOTAL_STEPS,
                            currentStep = index,
                            haltedStep = if (step.isHalted()) index else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = OrtSpacing.lg),
                        )
                    }
                    ScaffoldTitleRow(title, subtitle, titleOptional, titleTrailing)
                }
                Column(
                    modifier = Modifier
                        .padding(horizontal = OrtSpacing.lg)
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(OrtSpacing.md),
                    content = content,
                )
            }
        }.map { it.measure(contentConstraints) }

        layout(constraints.maxWidth, constraints.maxHeight) {
            contentPlaceables.forEach { it.placeRelative(0, 0) }
            barPlaceables.forEach { it.placeRelative(0, constraints.maxHeight - barHeightPx) }
        }
    }
}

private enum class SetupScaffoldSlot { Content, Bar }

/** The title/subtitle pair (+ optional trailing composable) — split out of [SetupScaffold] itself
 * purely to keep it under detekt's `LongMethod` threshold, the same reason [ScaffoldHeaderRow]/
 * [SegmentBars] already are (this file's own precedent); no state or behaviour of its own. */
@Composable
private fun ScaffoldTitleRow(
    title: String,
    subtitle: String,
    titleOptional: Boolean,
    titleTrailing: (@Composable () -> Unit)?,
) {
    Row(
        modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.md),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = title,
                    style = OrtType.screenTitle,
                    color = OrtColors.textHigh,
                    modifier = Modifier.alignByBaseline(),
                )
                // R-343: S09's own "optional" tag beside "Radio" (`Setup-Rig.dc.html`) --
                // baseline-aligned with the title, not the row's own bottom-aligned titleTrailing
                // slot (a separate composable at the row's far end, the wrong position for a tag
                // that sits directly beside the title text itself).
                if (titleOptional) {
                    Text(
                        text = "optional",
                        style = OrtType.signal,
                        color = OrtColors.textFaint,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }
            Text(text = subtitle, style = OrtType.subtitle, color = OrtColors.textDim)
        }
        titleTrailing?.invoke()
    }
}

/** The back target + "n of N" counter row — see [SetupScaffold]'s own doc comment for the R-120
 * fix this counter is half of. Split out purely to keep [SetupScaffold] itself under detekt's
 * `LongMethod` threshold; it has no state or behaviour of its own worth documenting separately. */
@Composable
private fun ScaffoldHeaderRow(step: SetupStep, onBack: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clickable(onClickLabel = "Back", role = Role.Button, onClick = onBack)
                    .testTag("setup-back"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = OrtIcons.back,
                    contentDescription = "Back",
                    tint = OrtColors.textIcon,
                    modifier = Modifier.size(21.dp),
                )
            }
        }
        Row(modifier = Modifier.weight(1f)) {}
        val index = step.indicatorIndex()
        if (index != null) {
            Text(text = "$index of $SETUP_TOTAL_STEPS", style = OrtType.signal, color = OrtColors.textDim)
        }
    }
}

/** guide §6.10's segment row alone — equal segments, `accent/green` for done/current, `line/default`
 * for the rest, a `halt/text` segment where [haltedStep] names one — with no counter text of its
 * own (see [SetupScaffold]'s own doc comment for why: this scaffold's header row already carries
 * it). The `contentDescription` WP2's labelled `StepIndicator` carries for TalkBack is preserved
 * here so accessibility does not regress just because the visible label moved. */
@Composable
private fun SegmentBars(steps: Int, currentStep: Int, modifier: Modifier = Modifier, haltedStep: Int? = null) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = if (haltedStep != null) {
                "Step $currentStep of $steps, halted at step $haltedStep"
            } else {
                "Step $currentStep of $steps"
            }
        },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (segment in 1..steps) {
            val color = when {
                segment == haltedStep -> OrtColors.haltText
                segment <= currentStep -> OrtColors.accentGreen
                else -> OrtColors.lineDefault
            }
            Box(modifier = Modifier.weight(1f).height(3.dp).background(color, RoundedCornerShape(2.dp)))
        }
    }
}

/** `RationaleCard`/similar boards' green-dot bullet list (`Setup-Mic.dc.html`,
 * `Setup-Battery.dc.html`'s "less likely, not guaranteed" paragraph). */
@Composable
public fun DotPointCard(points: List<String>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OrtColors.bgCard, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .padding(OrtSpacing.md + OrtSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
    ) {
        points.forEach { point ->
            Row {
                Box(
                    modifier = Modifier
                        .padding(top = 5.dp, end = 11.dp)
                        .size(9.dp)
                        .background(OrtColors.accentGreen, androidx.compose.foundation.shape.CircleShape),
                )
                Text(text = point, style = OrtType.subtitle, color = OrtColors.textBody)
            }
        }
    }
}

/** The halting banner shared by `Setup-Mic-Denied.dc.html` and `Setup-Route-Mismatch.dc.html` —
 * thin wrapper over WP2's [org.ort.app.ui.components.Banner] fixed to
 * [org.ort.app.ui.components.BannerTone.HALTING], since every use in this package is a hard halt. */
@Composable
public fun SetupHaltBanner(title: String, body: String, modifier: Modifier = Modifier) {
    org.ort.app.ui.components.Banner(
        title = title,
        body = body,
        tone = org.ort.app.ui.components.BannerTone.HALTING,
        modifier = modifier,
    )
}

/** A numbered step row (`1  Apps -> ... -> Permissions`), used by both S02b and S08. */
@Composable
public fun NumberedStep(index: Int, text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = OrtSpacing.sm)) {
        Text(
            text = "$index",
            style = OrtType.signal,
            color = OrtColors.textLow,
            modifier = Modifier.size(width = 22.dp, height = 16.dp),
        )
        Text(text = text, style = OrtType.control, color = OrtColors.textBody)
    }
}

/**
 * D33 (FR-CAP-9) — the "preset by <mode>" pill S04 and S09 both draw (`Setup-Input.dc.html`,
 * `Setup-Rig.dc.html`): a rounded, bordered chip naming the [modeLabel]
 * ([org.ort.core.capture.CaptureMode.operatorLabel]) that preselected the row below it. The
 * caller omits this composable entirely once the operator has overridden that axis's preset
 * (`Setup-Rig-Transport.dc.html`'s own note: "hidden when the operator overrode") — this
 * composable itself carries no such logic, it only draws the pill it is given.
 */
@Composable
public fun PresetChip(modeLabel: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .border(1.dp, OrtColors.lineDefault, RoundedCornerShape(14.dp))
            .padding(horizontal = 11.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = "preset by", style = OrtType.chip, color = OrtColors.textDim)
        Text(
            text = modeLabel,
            style = OrtType.chip.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
            color = OrtColors.textHigh,
        )
    }
}

/** guide §6.11's read-only device row shape (icon + title/subtitle) with a trailing chevron —
 * `Setup-Rig.dc.html`'s three options, which navigate on tap rather than toggling a radio state. */
@Composable
public fun NavigationRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    titleColor: Color = OrtColors.textBody,
    enabled: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClickLabel = title, role = Role.Button, onClick = onClick)
            .padding(vertical = OrtSpacing.sm)
            .semantics(mergeDescendants = true) { contentDescription = "$title. $subtitle" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) OrtColors.accentGreen else OrtColors.textIconDim,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = OrtType.rowTitle.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                color = titleColor,
            )
            Text(text = subtitle, style = OrtType.timeFreq, color = OrtColors.textDim)
        }
        Icon(
            imageVector = OrtIcons.chevron,
            contentDescription = null,
            tint = OrtColors.textLow,
            modifier = Modifier.size(15.dp),
        )
    }
}
