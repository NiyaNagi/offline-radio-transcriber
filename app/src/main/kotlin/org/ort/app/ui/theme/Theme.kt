package org.ort.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The reader's theme (D15, build-plan P13). Dark-first per AGENTS.md and every one of the seven
 * canvas artboards, which are dark exclusively — there is no light artboard to derive a light
 * scheme from, so [OrtTheme] renders the canvas's single dark palette unconditionally. A light
 * variant, if one is ever designed, is a palette addition here plus a `darkTheme` parameter — not
 * a call-site change, since every screen already goes through this one entry point.
 */
private val OrtDarkColorScheme = darkColorScheme(
    background = OrtColors.background,
    surface = OrtColors.surface,
    surfaceVariant = OrtColors.surfaceVariant,
    onBackground = OrtColors.textHigh,
    onSurface = OrtColors.textHigh,
    onSurfaceVariant = OrtColors.textMedium,
    primary = OrtColors.accentGreen,
    onPrimary = Color(0xFF0A2B1E),
    secondary = OrtColors.accentAmber,
    onSecondary = Color(0xFF2B2008),
    outline = OrtColors.divider,
    outlineVariant = OrtColors.divider,
)

@Composable
public fun OrtTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OrtDarkColorScheme,
        typography = OrtType.typography,
        content = content,
    )
}
