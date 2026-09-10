package org.ort.app.ui.theme

import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import android.graphics.Color as AndroidColor

/**
 * The reader's theme (D15, build-plan P13; R-001/R-006, ui-conformance-plan WP1). Dark-first per
 * AGENTS.md, constitution and guide §12 ("no light theme") — every artboard is dark exclusively,
 * so [OrtTheme] renders the canvas's single dark palette unconditionally. A light variant, if one
 * is ever designed, is a palette addition here plus a `darkTheme` parameter — not a call-site
 * change, since every screen already goes through this one entry point.
 *
 * R-006: wraps `content` in a [Surface] that fills the maximum available size on
 * [OrtColors.bgScreen] — so a screen sits on the design's own ground, never on whatever the
 * platform window background happens to be. `res/values/themes.xml`'s `Theme.Ort` sets the same
 * colour as `android:windowBackground` for the brief instant before Compose takes over, and both
 * [org.ort.app.ui.ReaderActivity] and [org.ort.app.MainActivity] call
 * `enableEdgeToEdge(statusBarStyle = OrtSystemBarStyle, navigationBarStyle = OrtSystemBarStyle)`
 * before `setContent` so content draws behind a transparent system-bar strip rather than under an
 * opaque platform one — the 44dp the boards leave clear is then a `WindowInsets.statusBars`
 * padding a screen applies itself (`TopAppBar`'s default insets already do this for the nav host;
 * the bare-permission screens in `MainActivity` do it directly), never a hardcoded dp value.
 *
 * [OrtTheme] also provides a [Density] scaled by [ortScaledDensity] before composing
 * [MaterialTheme]/[Surface], so every entry point (`MainActivity`, `ReaderActivity`,
 * `SetupActivity`, `ScenarioReaderActivity`, the screenshot tour) lays its screens out at the
 * design boards' own 390dp width regardless of the device's real `screenWidthDp` — see that
 * function's own doc comment for the finding and the arithmetic ([ortScaleFor]). Every caller
 * that itself overrides [LocalDensity] for a font-scale reason (`SetupActivity.EXTRA_FONT_SCALE`,
 * `ScreenshotTourActivity`) does so either before this composes (reading back the scaled density
 * magnitude and substituting its own `fontScale`) or after it (reading the already-scaled ambient
 * density as its own base) — never in place of it — so the two concerns compose rather than
 * collide.
 */

/**
 * R-008: the one status/navigation-bar style every Activity in this app must pass to
 * `enableEdgeToEdge()`. The no-arg overload defaults to `SystemBarStyle.auto`, which picks *light*
 * system-bar icons whenever the OS itself is not in night mode — on a device/emulator not in
 * night mode that rendered dark icons on this app's dark ground, nearly invisible (found on
 * `emulator-5554`, both `MainActivity`'s permission screens and `ReaderActivity`). This app has no
 * light theme at all (guide §12), so the bars must always use the dark style (light icons)
 * regardless of the OS's own night-mode state — never `auto`. `res/values/themes.xml`'s
 * `windowLightStatusBar`/`windowLightNavigationBar` (both `false`) state the same thing for the
 * pre-Compose frame `enableEdgeToEdge()` briefly runs under, before this call takes over.
 */
public val OrtSystemBarStyle: SystemBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
private val OrtDarkColorScheme = darkColorScheme(
    background = OrtColors.bgScreen,
    surface = OrtColors.bgScreen,
    surfaceVariant = OrtColors.bgRaised,
    surfaceTint = OrtColors.bgScreen,
    onBackground = OrtColors.textHigh,
    onSurface = OrtColors.textHigh,
    onSurfaceVariant = OrtColors.textBody,
    primary = OrtColors.accentGreen,
    onPrimary = OrtColors.accentOnGreen,
    primaryContainer = OrtColors.bgCard,
    onPrimaryContainer = OrtColors.textHigh,
    secondary = OrtColors.accentAmber,
    onSecondary = OrtColors.accentOnAmber,
    error = OrtColors.haltFill,
    onError = OrtColors.haltOnFill,
    errorContainer = OrtColors.haltBg,
    onErrorContainer = OrtColors.haltText,
    outline = OrtColors.lineDefault,
    outlineVariant = OrtColors.lineFaint,
    scrim = OrtColors.bgPage,
)

/** `OrtThemeTest` looks for this tag to prove the root [Surface] R-006 requires genuinely exists,
 * rather than only a colour scheme with nothing painting it. */
internal const val ORT_THEME_SURFACE_TAG = "ortThemeSurface"

/**
 * The board's own authored width (`design/design-guide.md` §4-5: every artboard under
 * `design/canvas/` is a fixed `390x844` root, 1 board px = 1 dp). [ortScaleFor] scales device
 * density so this width, not the device's raw `screenWidthDp`, is what every screen lays out
 * against.
 */
internal const val ORT_BOARD_WIDTH_DP = 390f

/**
 * Lower clamp for [ortScaleFor]: never shrink below the system's own density. A phone at or
 * under the board's 390dp width must keep its native layout — scaling it down would clip content
 * the board never accounted for shrinking further.
 */
internal const val ORT_SCALE_MIN = 1.0f

/**
 * Upper clamp for [ortScaleFor]: never exceed this on a tablet or foldable. Past roughly a 527dp
 * device width (390 * 1.35) the board's own proportion is no longer the right target — that is a
 * distinct layout, not a bigger board — so the scale stops growing rather than overshoot into one.
 */
internal const val ORT_SCALE_MAX = 1.35f

/**
 * The pure arithmetic behind the scaled [LocalDensity] `OrtTheme` provides (finding recorded
 * 2026-09-09: `results/ui-audit-findx9/overnight/L01-log.png` vs
 * `results/ui-audit-board390/overnight/L01-log.png`). Extracted from composition and tested
 * directly, in addition to the Robolectric-driven `OrtThemeScaleTest`, per `register.md`
 * R-551/R-590: Robolectric's own text/density measurement has bitten two other builders here, so
 * the arithmetic must be checkable without relying on it to measure a real layout.
 */
internal fun ortScaleFor(containerWidthDp: Float): Float =
    (containerWidthDp / ORT_BOARD_WIDTH_DP).coerceIn(ORT_SCALE_MIN, ORT_SCALE_MAX)

/**
 * The scaled [Density] `OrtTheme` provides. Reads the real available width from
 * [LocalConfiguration] (`screenWidthDp` — density-independent already, so immune to this same
 * override feeding back into itself) rather than a hardcoded per-device table, computes
 * [ortScaleFor] against the board's own 390dp, and multiplies only the density magnitude —
 * [Density.fontScale] is carried through from the ambient [LocalDensity] unchanged, so the system
 * font-scale setting (FR-A11Y-3, AC-63), `SetupActivity.EXTRA_FONT_SCALE` and the screenshot
 * tour's own per-step `LocalDensity` override all keep working exactly as before: each of those
 * composes *around* this one (see `Theme.kt`'s class doc) and reads back the density magnitude
 * this function already scaled, substituting only its own fontScale.
 */
@Composable
internal fun ortScaledDensity(): Density {
    val base = LocalDensity.current
    val containerWidthDp = LocalConfiguration.current.screenWidthDp.toFloat()
    val scale = ortScaleFor(containerWidthDp)
    return Density(density = base.density * scale, fontScale = base.fontScale)
}

@Composable
public fun OrtTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDensity provides ortScaledDensity()) {
        MaterialTheme(
            colorScheme = OrtDarkColorScheme,
            typography = OrtType.typography,
            content = {
                Surface(
                    modifier = Modifier.fillMaxSize().testTag(ORT_THEME_SURFACE_TAG),
                    color = OrtColors.bgScreen,
                ) {
                    content()
                }
            },
        )
    }
}
