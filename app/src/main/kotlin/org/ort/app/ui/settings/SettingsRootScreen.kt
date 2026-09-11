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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.NavRow
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
 *
 * R-130 (round 4)/round 6 (WP3's smoke test find): this screen drew no `ScreenHeader` of its own
 * for one round, on the premise that `OrtNavHost`'s `NavHostBody` always draws one for the whole
 * `SETTINGS` destination — true only while entry always started at this root. Once WP3's
 * `initialScreen` began landing directly on a *sub*-screen, the host's `ScreenHeader` and that
 * sub-screen's own `DrillInHeader` rendered stacked, since the host header is keyed on the drawer
 * destination, not on this composable's internal state. This root now draws its own `ScreenHeader`
 * again (drawer icon via [onDrawer], search via [onSearch]) and `OrtNavHost.kt`'s own header for
 * `SETTINGS` must be removed to match — outside this round's file ownership, see this package's
 * report.
 */
@Composable
public fun SettingsRootScreen(
    state: SettingsRootViewState,
    onDrawer: () -> Unit,
    onOpen: (SettingsScreenId) -> Unit,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(onDrawer = onDrawer, onSearch = onSearch)
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
 * [OrtIcons.lock] (a privacy-consent screen), rather than drawing no icon at all.
 *
 * R-451 (register, polish, Reviewer D): `CAPTURE`'s row ("Input and level") drew `OrtIcons.capture`
 * — an 8-ray sunburst, the board's own icon for a *different* concept — rather than
 * `Settings.dc.html`'s own recorder glyph for this exact row (a rounded-rectangle mic capsule and
 * its stand). `ui/components/OrtIcons.kt` has no such glyph to reuse and is outside this round's
 * file ownership to add one to, so [SettingsInputAndLevelIcon] below draws it locally, from the
 * board's own SVG (`<rect x="6" y="3" width="12" height="9" rx="2">` converted to its path
 * equivalent, `<path d="M12 12v4M8 21h8M12 16v5">` verbatim) — the same technique
 * `OrtIcons.kt`'s own doc comment describes using for every other icon there.
 */
internal fun iconFor(screen: SettingsScreenId): ImageVector = when (screen) {
    SettingsScreenId.CAPTURE -> SettingsInputAndLevelIcon
    SettingsScreenId.RIG -> OrtIcons.rig
    SettingsScreenId.TIER -> OrtIcons.settings
    SettingsScreenId.STORAGE -> OrtIcons.storage
    SettingsScreenId.ASSETS -> OrtIcons.models
    SettingsScreenId.EXPORT -> OrtIcons.export
    SettingsScreenId.CONTRIBUTE -> OrtIcons.lock
    SettingsScreenId.DIAGNOSTICS -> OrtIcons.diagnostics
    SettingsScreenId.ABOUT -> OrtIcons.settings
    // WPE (CF11, `spec/e2e-capture-modes-plan.md`): MODE is reached only from CF02's own `Change`
    // row (and seeded directly for the tour) — it never appears as its own row in this root list,
    // so this branch exists only to keep `iconFor` exhaustive over `SettingsScreenId`'s closed set.
    SettingsScreenId.MODE -> SettingsInputAndLevelIcon
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

/** R-451: `Settings.dc.html`'s own "Input and level" row glyph — see [iconFor]'s own doc comment
 * for the exact board SVG this traces and why it is drawn locally rather than added to the shared
 * `OrtIcons` set. `Color.Black` is a build-time placeholder only, exactly like every `OrtIcons`
 * glyph's own doc comment says of its own paths — a caller always supplies the real tint via
 * `Icon(..., tint = ...)`, which overrides it ([NavRow]'s own icon slot does this already). */
internal val SettingsInputAndLevelIcon: ImageVector = ImageVector.Builder(
    name = "settingsInputAndLevel",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    addPath(
        pathData = addPathNodes(
            "M8 3L16 3A2 2 0 0 1 18 5L18 10A2 2 0 0 1 16 12L8 12A2 2 0 0 1 6 10L6 5A2 2 0 0 1 8 3Z",
        ),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.9f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
    addPath(
        pathData = addPathNodes("M12 12v4M8 21h8M12 16v5"),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.9f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}.build()
