package org.ort.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.FailedState
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.ToggleRow
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * `Settings-Contribute.dc.html` (FR-CON-1..8, constitution V): per-category consent, nothing on by
 * default, and the never-included list verbatim from the constitution — voiceprints, names,
 * station knowledge, precise location. The upload channel itself has no client in `:net` yet
 * (grepped before writing this), so `Preview bundle` is honestly disabled rather than a fake
 * success — the board's own "Preview bundle · nothing selected" already reads as an inert state,
 * this makes the reason explicit.
 */
@Composable
public fun SettingsContributeScreen(
    state: SettingsContributeViewState,
    onBack: () -> Unit,
    onToggleCategory: (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DrillInHeader(parentLabel = "Settings", onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
            Text(text = "Contribute to the corpus", style = OrtType.screenTitle, color = OrtColors.textHigh)
            Text(
                text = "Off unless you turn each part on. Nothing leaves this phone until you save a " +
                    "bundle and send it yourself.",
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
            )

            SectionHeader(
                label = "Can be included, each with its own switch",
                modifier = Modifier.padding(top = OrtSpacing.md),
            )
            state.categories.forEachIndexed { index, category ->
                ToggleRow(
                    label = category.label,
                    checked = category.enabled,
                    onCheckedChange = { onToggleCategory(index, it) },
                    subLine = category.subLine,
                )
            }

            SectionHeader(
                label = "Never included — there is no switch",
                modifier = Modifier.padding(top = OrtSpacing.md),
            )
            state.neverIncluded.forEach { item ->
                Row(modifier = Modifier.padding(vertical = OrtSpacing.xs)) {
                    Icon(
                        imageVector = OrtIcons.dismiss,
                        contentDescription = null,
                        tint = OrtColors.textDim,
                        modifier = Modifier.padding(top = 3.dp).size(15.dp),
                    )
                    Column(modifier = Modifier.padding(start = OrtSpacing.md)) {
                        Text(text = item.title, style = OrtType.control, color = OrtColors.textBody)
                        item.subLine?.let {
                            Text(
                                text = it,
                                style = OrtType.subLine,
                                color = OrtColors.textDim,
                                modifier = Modifier.padding(top = 1.dp),
                            )
                        }
                    }
                }
            }

            SectionHeader(label = "Before anything is saved", modifier = Modifier.padding(top = OrtSpacing.md))
            Text(
                text = "You read the bundle — every file listed, every row openable — then you choose " +
                    "where it goes. The app cannot send it.",
                style = OrtType.cardBody,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs),
            )

            FailedState(
                title = "Preview bundle is not available in this build",
                body = "No contribution upload client exists in :net yet — nothing here can be saved or " +
                    "sent regardless of which categories are on.",
                modifier = Modifier.padding(top = OrtSpacing.lg, bottom = OrtSpacing.lg),
            )
        }
    }
}
