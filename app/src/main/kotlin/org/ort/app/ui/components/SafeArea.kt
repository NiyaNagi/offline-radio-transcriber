package org.ort.app.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Register R-1003 (halt) — the operator's Oppo Find X9 Ultra (1440x3168 @ 480dpi, ColorOS): every
 * action button on the setup rig-selection step overlapped the system navigation buttons, and the
 * Bluetooth rig step's only escape action (`Use USB instead`, the last item in that same pinned
 * bar) was reported missing because it was drawn underneath them.
 *
 * Root cause: every pinned-bottom action block in this app applied `.windowInsetsPadding(
 * WindowInsets.statusBars)` for its own *top* inset (`SetupScaffold.kt`, `WelcomeScreen.kt`,
 * `ui/failures/FailRoute.kt`'s `failureScreenInset()`) and nothing at all for its *bottom* —
 * `navigationBars`, `systemBars`, `safeDrawing` and `safeContent` appeared nowhere in
 * `app/src/main` before this file. `ui/failures/FailureActionBarScaffold.kt` had no inset handling
 * at either edge. On a device whose navigation is a gesture pill or a three-button bar, every one
 * of those pinned blocks rendered flush against, and partly behind, the live system bar.
 *
 * [safeAreaBottomPadding] is the one mechanism every pinned-bottom surface in this app now shares,
 * rather than four hand-rolled ones — the reason that matters is on the record: register R-957, a
 * padding change in the navigation host that nobody classified as visual, once moved the live bar
 * 125px on every screen. It reads [WindowInsets.navigationBars]' bottom component alone (never a
 * hardcoded constant), so it degrades correctly across three-button navigation, gesture navigation,
 * and no navigation bar at all.
 *
 * Applied at exactly one point in each layout that pins a bottom bar — never separately to the bar
 * *and* the content column beside or above it, which is the exact shape of the R-957
 * double-reservation bug this shared helper exists to prevent a second copy of. Composable because
 * [WindowInsets.navigationBars] itself reads the ambient inset through composition.
 *
 * [insets] defaults to the real [WindowInsets.navigationBars] every production call site uses.
 * Parameterised, rather than hardcoded, on the chance a future test environment can drive it — but
 * **no Robolectric test in this codebase can currently prove this function reserves real space**.
 * Confirmed by three independent attempts (this package's own report has the details): dispatching
 * a real `WindowInsetsCompat` to the activity's decor view, via both the platform call and
 * `ViewCompat.dispatchApplyWindowInsets`, with and without `WindowCompat.setDecorFitsSystemWindows
 * (window, false)`, left [WindowInsets.navigationBars] reading zero before and after; and — the
 * decisive one — even `Modifier.windowInsetsPadding(WindowInsets(bottom = 40))`, Compose's own
 * top-level function called directly with a fixed, non-View-backed [WindowInsets] instance whose
 * `getBottom(density)` correctly reports `40`, measured no differently under `createComposeRule()`
 * than with no padding modifier at all. `windowInsetsPadding` itself is inert under this project's
 * Robolectric setup, not merely hard to drive — the closing evidence for register row R-1003 is a
 * device dump, never a test in this file or any file that calls it.
 */
@Composable
public fun Modifier.safeAreaBottomPadding(insets: WindowInsets = WindowInsets.navigationBars): Modifier =
    this.windowInsetsPadding(insets.only(WindowInsetsSides.Bottom))
