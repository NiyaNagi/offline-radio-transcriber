package org.ort.app.ui.components

/**
 * R-465/R-542 (register — WP9 found and device-verified this first, `setup-level/S07-level.png`;
 * `LevelMeterScreen.kt`'s `LevelHistoryChart` carried the byte-identical defect, R-542): a fixed
 * reference line (a target-band edge, the clip line, a noise-floor line) whose own `fraction` lands
 * exactly at `0f` or `1f` — the scale's own floor or ceiling — is drawn exactly on the canvas's own
 * top/bottom row. Two things both have to be true at once for that line to then never actually
 * render, both found by sampling real rendered pixels on a device rather than by inspection: (1) a
 * full-width stroke centred exactly on that row is bisected by the canvas's own bounds, so half its
 * width is clipped away, and (2) the chart's own enclosing `Box` draws its border *over* its
 * children (so the border stays visible even where a child fills the whole `Box`), overpainting the
 * remaining half even after a first fix that only inset the line by its own half-stroke. Zero
 * non-background pixels near the expected row was the confirmed, on-device symptom either way.
 *
 * [referenceLineY] clears both problems by keeping every fixed line at least [edgeClearancePx] from
 * either edge — never merely a stroke width to be halved, but the full clearance both the stroke's
 * own half-width and whatever draws over the canvas at its border need. A line already clear of the
 * edges is unaffected, since [Float.coerceIn] is a no-op there. **Bars are unaffected on purpose** —
 * a history bar's own rectangle is meant to reach the true edge, unclamped; only fixed reference
 * lines go through this.
 *
 * Shared between [org.ort.app.ui.setup.LevelScreen] (S07, where WP9 first found and fixed this,
 * register R-465) and [org.ort.app.ui.screens.LevelMeterScreen] (N06, register R-542) rather than
 * each keeping its own private copy — the one other place in this codebase drawing fixed reference
 * lines against a bounded scale.
 */
public fun referenceLineY(fraction: Float, heightPx: Float, edgeClearancePx: Float): Float =
    (heightPx * (1f - fraction.coerceIn(0f, 1f))).coerceIn(edgeClearancePx, heightPx - edgeClearancePx)
