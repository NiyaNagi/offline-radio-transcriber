package org.ort.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.components.ScreenHeader
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * R-090 (`Settings.dc.html`, FR-CFG-1): the settings root — grouped rows (Capture / Records /
 * Privacy / About), each with a one-line status sub-line computed by [SettingsPolling] from real
 * state. Before this, the `SETTINGS` destination opened straight into `ModelsScreen` with no root
 * at all (R-090's `what is wrong`) — every row here now drills into its own screen with WP2's
 * [org.ort.app.ui.components.DrillInHeader].
 */
@Composable
public fun SettingsRootScreen(
    state: SettingsRootViewState,
    onDrawer: () -> Unit,
    onOpen: (SettingsScreenId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(onDrawer = onDrawer)
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(
                text = "Settings",
                style = OrtType.screenTitle,
                color = OrtColors.textHigh,
                modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm),
            )
            state.sections.forEach { section ->
                SectionHeader(
                    label = section.label,
                    modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm),
                )
                section.rows.forEach { row ->
                    SettingsNavRow(row = row, onClick = { onOpen(row.screen) })
                }
            }
        }
    }
}

/** guide §6.5-adjacent: a settings-row navigation control — name, sub-line, trailing chevron, a
 * real 44dp target — this package's own local component since no shared `ui/components` row of
 * this exact shape (name + sub-line + chevron drill-in) exists yet; adding one there is outside
 * this package's row (the `ui/components` package is WP2's). */
@Composable
internal fun SettingsNavRow(row: SettingsRowViewState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)
            .semantics(mergeDescendants = true) { contentDescription = "${row.label}. ${row.subLine}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrtSpacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = row.label, style = OrtType.rowTitle, color = OrtColors.textHigh)
            Text(
                text = row.subLine,
                style = OrtType.subLine,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(
            imageVector = OrtIcons.chevron,
            contentDescription = null,
            tint = OrtColors.textSignal,
            modifier = Modifier.size(15.dp),
        )
    }
}

/** A tinted status dot for a settings sub-screen row (`Settings-Capture`/`Settings-Rig`'s green/
 * amber/hollow markers) — local to this package for the same reason [SettingsNavRow] is. */
@Composable
internal fun SettingsStatusDot(on: Boolean, modifier: Modifier = Modifier) {
    val color = if (on) OrtColors.accentGreen else OrtColors.lineControl
    androidx.compose.foundation.Canvas(modifier = modifier.size(9.dp)) {
        drawCircle(color = color)
    }
}

/** guide §11's "no colour as the only signal" applied to a badge-styled uppercase tag, used by
 * several sub-screens for "locked"/"always"/"not tracked" style facts that are not a [Badge]. */
@Composable
internal fun SettingsMonoTag(text: String, modifier: Modifier = Modifier) {
    Text(text = text, style = OrtType.signal, color = OrtColors.textDim, modifier = modifier)
}

internal val SETTINGS_CARD_SHAPE = RoundedCornerShape(10.dp)
internal val SETTINGS_CARD_BG: Color = OrtColors.bgCard
