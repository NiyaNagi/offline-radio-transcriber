package org.ort.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.NavRow
import org.ort.app.ui.components.OrtIcons
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
 *
 * R-130 (round 4, System validator): this screen draws no `ScreenHeader` of its own — `OrtNavHost`'s
 * `NavHostBody` already renders one for the whole `SETTINGS` destination (root and every sub-screen
 * alike) before dispatching to `SettingsContent`, so a second one here stacked two bare drawer-icon
 * rows and pushed every section down. [onDrawer] stays a parameter (unused in this file) only so
 * `SettingsContent`'s own signature — and `OrtNavHost.kt`'s call site, outside this package's row —
 * need no edit.
 */
@Suppress("UnusedParameter") // onDrawer: kept only so SettingsContent's signature needs no edit — see kdoc above.
@Composable
public fun SettingsRootScreen(
    state: SettingsRootViewState,
    onDrawer: () -> Unit,
    onOpen: (SettingsScreenId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
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
                    NavRow(
                        rowTitle = row.label,
                        onClick = { onOpen(row.screen) },
                        icon = iconFor(row.screen),
                        subLine = row.subLine,
                    )
                }
            }
        }
    }
}

/** R-131 (register, round 5 System validator): the settings-root rows now draw WP2's [NavRow]
 * (`Settings.dc.html`'s own row family) instead of this package's own hand-rolled equivalent
 * ("`SettingsNavRow`", removed by this migration) — the migration this round's brief asked for,
 * and it closes R-131's "rows lack their 18px leading icons" for free (a slot the removed
 * composable never had). No bespoke icon exists per [SettingsScreenId] (`ui/components` is outside
 * this round's file ownership to add one to), so this reuses the closest existing `OrtIcons` glyph
 * for each — `TIER`/`ABOUT` both fall back to [OrtIcons.settings] and `CONTRIBUTE` to
 * [OrtIcons.lock] (a privacy-consent screen), rather than drawing no icon at all. */
private fun iconFor(screen: SettingsScreenId): ImageVector = when (screen) {
    SettingsScreenId.CAPTURE -> OrtIcons.capture
    SettingsScreenId.RIG -> OrtIcons.rig
    SettingsScreenId.TIER -> OrtIcons.settings
    SettingsScreenId.STORAGE -> OrtIcons.storage
    SettingsScreenId.ASSETS -> OrtIcons.models
    SettingsScreenId.EXPORT -> OrtIcons.export
    SettingsScreenId.CONTRIBUTE -> OrtIcons.lock
    SettingsScreenId.DIAGNOSTICS -> OrtIcons.diagnostics
    SettingsScreenId.ABOUT -> OrtIcons.settings
}

/** A tinted status dot for a settings sub-screen row (`Settings-Capture`/`Settings-Rig`'s green/
 * amber/hollow markers) — this package's own local component, no shared equivalent exists. */
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
