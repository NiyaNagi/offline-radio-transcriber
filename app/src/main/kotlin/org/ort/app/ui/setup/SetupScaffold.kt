package org.ort.app.ui.setup

import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 */
@Composable
public fun SetupScaffold(
    step: SetupStep,
    title: String,
    subtitle: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    titleTrailing: (@Composable () -> Unit)? = null,
    bottomActions: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OrtColors.bgScreen)
            .windowInsetsPadding(WindowInsets.statusBars)
            .testTag("setup-screen-${step.name}"),
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
        Row(
            modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.md),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = OrtType.screenTitle, color = OrtColors.textHigh)
                Text(text = subtitle, style = OrtType.subtitle, color = OrtColors.textDim)
            }
            titleTrailing?.invoke()
        }
        Column(
            modifier = Modifier
                .padding(horizontal = OrtSpacing.lg)
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(OrtSpacing.md),
        ) {
            content()
        }
        Column(
            modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.md),
            verticalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
        ) {
            bottomActions()
        }
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
