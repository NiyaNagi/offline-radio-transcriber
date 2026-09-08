package org.ort.app.ui.theme

import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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

@Composable
public fun OrtTheme(content: @Composable () -> Unit) {
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
