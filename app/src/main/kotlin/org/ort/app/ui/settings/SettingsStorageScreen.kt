package org.ort.app.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.FilterChipRow
import org.ort.app.ui.components.KeyValueRow
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.components.ToggleRow
import org.ort.app.ui.improve.Plurals
import org.ort.app.ui.navigation.toGigabyteLabel
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * `Settings-Storage.dc.html` (FR-STO-1..8, P9): use by category, the retention policy controls,
 * and a "what will be deleted" preview computed from the policy — this package never deletes
 * anything (FR-STO-3b/P9: deletion is announced in advance, and only actually performed elsewhere).
 */
@Composable
public fun SettingsStorageScreen(
    state: SettingsStorageViewState,
    onBack: () -> Unit,
    onSetBudgetGb: (Int?) -> Unit,
    onToggleAutoPrune: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    // R-133 (round 8): defaults to a no-op so every existing caller keeps compiling unchanged —
    // see [SettingsContent]'s own `onReviewSession` doc comment for why this package cannot
    // resolve the real DG04 (`Session`) destination on its own.
    onReviewSession: (sessionId: String) -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DrillInHeader(parentLabel = "Settings", onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
            Text(text = "Storage and retention", style = OrtType.screenTitle, color = OrtColors.textHigh)
            Text(
                text = "Audio is the only thing that is ever deleted, and never quietly",
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.md),
            )

            Text(
                text = state.usedBytes.toGigabyteLabel() +
                    (state.budgetGb?.let { " used of $it GB budgeted" } ?: " used · no budget set"),
                style = OrtType.control,
                color = OrtColors.textBody,
            )
            StorageCategoryBreakdown(categories = state.categories)

            SectionHeader(label = "Audio", modifier = Modifier.padding(top = OrtSpacing.lg))
            KeyValueRow(
                key = "Budget",
                value = state.budgetGb?.let { "$it GB" } ?: "not set",
                subLine = state.nightsLeftLabel ?: "not enough data to project nights left",
            )
            // R-150/R-251/R-291 (register, rounds 4, 6 and 7 System validator): at font scale 2.0
            // a fixed-width `Row` squeezed each chip's `Text` below its own intrinsic width,
            // wrapping "Unlimited" one letter per line. A hand-rolled `Modifier.horizontalScroll`
            // fix (round 4) then clipped at the right edge with no way to swipe to it (R-251); the
            // round-6 fix moved to WP2's own `FilterChipRow` but, per `StationScreen.kt`'s own
            // R-277 precedent comment, left off the `fillMaxWidth()` a scrollable row needs to
            // report its *real* available width to scroll within rather than whatever width its
            // own unconstrained content measurement happens to settle on — without it the row
            // never registered there was more to scroll to, so "Unli…" kept clipping and a swipe
            // did nothing (R-291's exact report). Same fix as R-277's, applied here too.
            FilterChipRow(modifier = Modifier.fillMaxWidth().testTag(BUDGET_CHIP_ROW_TEST_TAG)) {
                listOf(10, 20, 30, 60).forEach { gb ->
                    TextAction(text = "$gb GB", onClick = { onSetBudgetGb(gb) })
                }
                TextAction(text = "Unlimited", onClick = { onSetBudgetGb(null) })
            }
            KeyValueRow(
                key = "Format",
                value = "Lossless FLAC",
                subLine = "a lossy codec is not proven harmless to callsign resolution yet (FR-STO-2a/2b)",
            )

            SectionHeader(label = "When space runs low", modifier = Modifier.padding(top = OrtSpacing.lg))
            LowSpaceRows(warnAtNightsLeft = state.warnAtNightsLeft, hardFloorLabel = state.hardFloorLabel)
            ToggleRow(
                label = "Automatically prune the oldest audio first",
                checked = state.autoPruneEnabled,
                onCheckedChange = onToggleAutoPrune,
                subLine = "opt-in (FR-STO-3a) · off means you review and choose before anything is deleted",
            )
            // R-133 (round 8, register): the round-7 amber `Banner` here is gone — `:pipeline`'s
            // real `computeNextDeletion` (WP11c) now exists, so this is `Settings-Storage.dc.html`'s
            // own "Next deletion: <date>" row (real session, real over count, real size, a `Review`
            // link) instead of a banner the board never drew. `state.nextDeletion` is real whether
            // or not auto-prune is on — [org.ort.pipeline.capture.NextDeletion]'s own doc comment:
            // "what pruning would do the moment a budget is reached", the honest preview FR-STO-3a
            // promises, not gated on the opt-in toggle that only controls whether it runs
            // *automatically*.
            NextDeletionRow(
                state = state,
                onReviewSession = onReviewSession,
                modifier = Modifier.padding(top = OrtSpacing.sm),
            )

            SectionHeader(label = "Never deleted", modifier = Modifier.padding(top = OrtSpacing.lg))
            Text(
                text = "Transcripts, attributions, corrections, superseded versions, rejected segments. " +
                    "Records are small and are the point — there is no setting for this.",
                style = OrtType.cardBody,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.lg),
            )
        }
    }
}

/** R-133 (register, round 4 System validator): "Warn at" reads the real threshold FR-STO-3/R-105's
 * `StorageForecast` already computes against (not a board literal); "Hard floor" repeats the same
 * "100 MB" `:pipeline`'s own failure screens already show for `RealCaptureService`'s real,
 * internal-to-:pipeline constant (see [SettingsStorageViewState]). Split out of
 * [SettingsStorageScreen] purely to keep that function under detekt's length limit. */
@Composable
private fun LowSpaceRows(warnAtNightsLeft: Int, hardFloorLabel: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        KeyValueRow(
            key = "Warn at",
            value = "$warnAtNightsLeft nights left",
            subLine = "notification and status surface",
        )
        KeyValueRow(
            key = "Then stop retaining audio, keep capturing text",
            value = "always",
            subLine = "the order is fixed: audio goes before transcripts, and capture never stops silently",
        )
        KeyValueRow(
            key = "Hard floor",
            value = hardFloorLabel,
            subLine = "capture stops loudly, never quietly, below this",
        )
    }
}

/**
 * `Settings-Storage.dc.html`'s "Next deletion" row (R-133, round 8): the half-filled amber marker,
 * a real session/over-count/size sub-line, and a `Review` link into that session's own `Session`
 * (DG04) detail — [org.ort.pipeline.capture.computeNextDeletion]'s real answer, not a preview this
 * package invents.
 *
 * `state.nextDeletion == null` reads this package's own honest fallback wording — the board draws
 * no such state at all (checked `Settings-Storage.dc.html` again before writing this; it shows
 * only the populated row), so this is not board-verbatim copy: the exact real headroom when a
 * budget is set ("Nothing scheduled — N GB below the budget"), or "no budget set" when there is
 * none to be below.
 */
@Composable
private fun NextDeletionRow(
    state: SettingsStorageViewState,
    onReviewSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val next = state.nextDeletion
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = OrtSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
    ) {
        NextDeletionMarker(modifier = Modifier.padding(top = 5.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (next != null) {
                Text(
                    text = "Next deletion: ${next.predictedDateLabel}",
                    style = OrtType.rowTitle,
                    color = OrtColors.textHigh,
                )
                Text(
                    text = "audio from ${next.sessionDateLabel}, ${Plurals.count(next.overCount, "over")}, " +
                        "${next.sizeLabel} · transcripts and attributions stay",
                    style = OrtType.subLine,
                    color = OrtColors.textDim,
                    modifier = Modifier.padding(top = 2.dp),
                )
            } else {
                Text(text = "Next deletion", style = OrtType.rowTitle, color = OrtColors.textHigh)
                Text(
                    text = nothingScheduledLabel(state),
                    style = OrtType.subLine,
                    color = OrtColors.textDim,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (next != null) {
            TextAction(
                text = "Review",
                onClick = { onReviewSession(next.sessionId) },
                modifier = Modifier.semantics {
                    contentDescription = "Review the session from ${next.sessionDateLabel}"
                },
            )
        }
    }
}

private fun nothingScheduledLabel(state: SettingsStorageViewState): String {
    val budgetGb = state.budgetGb
    return if (budgetGb != null) {
        val headroomBytes = (budgetGb * 1_000_000_000L - state.usedBytes).coerceAtLeast(0L)
        "Nothing scheduled — ${headroomBytes.toGigabyteLabel()} below the budget"
    } else {
        "Nothing scheduled — no budget set"
    }
}

/** The board's half-filled amber marker for "Next deletion" — the same ring-plus-half-fill
 * technique `ui/components/Feedback.kt`'s own `FailedMarker` uses for a different (attention, not
 * storage) meaning; drawn locally since that composable is `private` to its own file. */
@Composable
private fun NextDeletionMarker(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(9.dp)) {
        val radius = size.minDimension / 2 - 0.75.dp.toPx()
        drawCircle(color = OrtColors.accentAmber, radius = radius, style = Stroke(1.5.dp.toPx()))
        clipRect(right = size.width / 2) {
            drawCircle(color = OrtColors.accentAmber, radius = radius)
        }
    }
}

/**
 * The storage-category bar + legend, split out of [SettingsStorageScreen] purely to keep that
 * function under detekt's length limit.
 *
 * R-351 (register, round 10 System validator): the bar never rendered at all — neither the outer
 * nor the per-segment `Row` carried an explicit height, and an empty-content child `Row`
 * ([weight] alone gives it a share of *width*, never a height) has no intrinsic size to fall back
 * to, so the whole bar measured to zero height regardless of data. Now
 * [STORAGE_BAR_HEIGHT] (`Settings-Storage.dc.html`'s own `height: 8px`), one shared rounded-rect
 * `clip` on the whole bar (the board's `overflow: hidden` on the *outer* element — not a rounded
 * corner on every segment individually, the previous code's own approach), and each segment
 * `fillMaxHeight()`s that fixed height instead of relying on content it never had. A zero-byte
 * category still gets a real, visible hairline share of the bar (`coerceAtLeast(1L)` before
 * dividing by the real total) — never dropped from the bar, and its real `0.0 GB` legend entry is
 * unchanged. The legend now draws the board's own 10×10 colour [ColorSwatch] beside each label —
 * text-only before this round.
 *
 * R-441 (register, halt, Reviewer D): a *fresh* store (every category genuinely `0` bytes, not
 * just one among real data) rendered almost full-width solid colour instead of an empty track —
 * `coerceAtLeast(1L)` on every numerator against a `total` itself coerced to `1L` gave every
 * category an *equal* share, but Compose's own weighted-`Row` rounding at that many-decimal-place
 * equal split (confirmed by direct on-device review, not reproducible from the arithmetic alone on
 * this host) resolved unevenly rather than as four clean quarters. Rather than depend on that
 * rounding behaving a particular way, a genuinely empty store ([realTotalBytes] `== 0L`) now draws
 * **no** weighted segments at all — the bar's own [OrtColors.lineChip] track background *is* the
 * empty state the board calls for, never a coloured fallback standing in for data that does not
 * exist yet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StorageCategoryBreakdown(
    categories: List<SettingsStorageCategoryViewState>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            // R-351: `padding` must sit *outside* `height` in this chain — a fixed `height(8.dp)`
            // sets min == max == 8dp for everything nested inside it, so a `padding(top = sm)`
            // placed after it (as this used to be ordered) does not add space above an 8dp box, it
            // *consumes* the 8dp box's own budget — `OrtSpacing.sm` is itself `8.dp`, so the old
            // order left exactly `0dp` for the bar's real content, the direct cause of R-351.
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = OrtSpacing.sm)
                .height(STORAGE_BAR_HEIGHT)
                .clip(RoundedCornerShape(STORAGE_BAR_CORNER_RADIUS))
                .background(OrtColors.lineChip)
                .testTag(STORAGE_BAR_TEST_TAG),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            // R-441: a genuinely empty store (every category `0`) draws no segments at all — the
            // track's own background above is the empty state; see this composable's own doc
            // comment for why an equal-weight fallback is not used instead.
            val realTotalBytes = categories.sumOf { it.bytes }
            if (realTotalBytes > 0L) {
                categories.forEach { category ->
                    Row(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(category.bytes.coerceAtLeast(1L).toFloat() / realTotalBytes)
                            .background(categoryColor(category.label))
                            .testTag(STORAGE_BAR_SEGMENT_TEST_TAG),
                    ) {}
                }
            }
        }
        // R-150/R-251: the legend wrapped "Records / 0.0 / GB" across separate lines at font
        // scale 2.0 when each label was squeezed into a fixed share of the row's width (round 4);
        // a `horizontalScroll` fix for this specific row was never reported broken on-device
        // (unlike the chip row above), but R-251 asked for "a wrapping legend" specifically — a
        // `FlowRow` wraps whole entries onto a second line rather than requiring a swipe to read
        // the ones that do not fit, which reads better for a legend the operator is not tapping.
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(OrtSpacing.md),
        ) {
            categories.forEach { category ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(OrtSpacing.xs),
                ) {
                    ColorSwatch(color = categoryColor(category.label))
                    Text(
                        text = "${category.label} ${category.bytes.toGigabyteLabel()}",
                        style = OrtType.chip,
                        color = OrtColors.textBody,
                    )
                }
            }
        }
    }
}

/** `Settings-Storage.dc.html`'s own 10×10, 2dp-rounded legend swatch (`.sw`). */
@Composable
private fun ColorSwatch(color: Color, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(STORAGE_SWATCH_SIZE).background(color, RoundedCornerShape(2.dp)))
}

private val STORAGE_BAR_HEIGHT = 8.dp
private val STORAGE_BAR_CORNER_RADIUS = 4.dp
private val STORAGE_SWATCH_SIZE = 10.dp

/** R-351 (register): stable handles so a test can measure the bar's real height and count its
 * real segments — the direct proof of the fix (a `Row` with no height/content gives neither a
 * meaningful semantics size nor a countable child on its own). */
internal const val STORAGE_BAR_TEST_TAG: String = "settings-storage-usage-bar"
internal const val STORAGE_BAR_SEGMENT_TEST_TAG: String = "settings-storage-usage-bar-segment"

/** R-291 (register): a stable handle onto the budget-chip `FilterChipRow` so a test can measure
 * its actual width against the root's, proving `fillMaxWidth()` reaches it — the fix for the row
 * clipping/not-scrolling at font scale 2.0. */
internal const val BUDGET_CHIP_ROW_TEST_TAG: String = "settings-storage-budget-chip-row"

// R-133 (round 8): `Settings-Storage.dc.html`'s four real swatches — Audio/Models (two greens),
// Records (amber), Lexicon (a neutral grey, `oklch(0.62 0.008 250)` on the board, near-identical
// to this token) — `Lexicon` no longer falls into the same `else` bucket `Records` does now that
// there is a real fourth category to tell apart in the bar and its legend.
private fun categoryColor(label: String): androidx.compose.ui.graphics.Color = when (label) {
    "Audio" -> OrtColors.meterIdle
    "Models" -> OrtColors.accentGreen
    "Records" -> OrtColors.accentAmber
    else -> OrtColors.textDim
}
