package org.ort.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.Banner
import org.ort.app.ui.components.BannerTone
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.KeyValueRow
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.components.ToggleRow
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
            Row(horizontalArrangement = Arrangement.spacedBy(OrtSpacing.md)) {
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
            ToggleRow(
                label = "Automatically prune the oldest audio first",
                checked = state.autoPruneEnabled,
                onCheckedChange = onToggleAutoPrune,
                subLine = "opt-in (FR-STO-3a) · off means you review and choose before anything is deleted",
            )
            if (!state.autoPruneEnabled) {
                Banner(
                    title = "What will be deleted, when a budget is reached",
                    body = "Nothing yet — no budget has been reached. When one is, this bulk export-and-" +
                        "prune preview names the exact sessions and over count before anything is removed " +
                        "(FR-STO-3a/3b). Transcripts, attributions and corrections are never deleted.",
                    tone = BannerTone.DEGRADED,
                    modifier = Modifier.padding(top = OrtSpacing.sm),
                )
            }

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

/** The storage-category bar + legend, split out of [SettingsStorageScreen] purely to keep that
 * function under detekt's length limit. */
@Composable
private fun StorageCategoryBreakdown(
    categories: List<SettingsStorageCategoryViewState>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val total = categories.sumOf { it.bytes }.coerceAtLeast(1L)
            categories.forEach { category ->
                Row(
                    modifier = Modifier.weight(category.bytes.coerceAtLeast(1L).toFloat() / total)
                        .background(categoryColor(category.label), RoundedCornerShape(2.dp)),
                ) {}
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(OrtSpacing.md),
        ) {
            categories.forEach { category ->
                Text(
                    text = "${category.label} ${category.bytes.toGigabyteLabel()}",
                    style = OrtType.chip,
                    color = OrtColors.textBody,
                )
            }
        }
    }
}

private fun categoryColor(label: String): androidx.compose.ui.graphics.Color = when (label) {
    "Audio" -> OrtColors.meterIdle
    "Models" -> OrtColors.accentGreen
    else -> OrtColors.accentAmber
}
