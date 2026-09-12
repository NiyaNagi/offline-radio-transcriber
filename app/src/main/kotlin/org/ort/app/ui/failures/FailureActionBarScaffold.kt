package org.ort.app.ui.failures

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import org.ort.app.ui.components.safeAreaBottomPadding

/**
 * Register R-151 (same class as R-123): every takeover with a fixed bottom action bar
 * (`FailRouteScreen`, `FailStorageHaltScreen`, `FailMigrationScreen`, `FailAssetSwapScreen`) used
 * to split its content and its bar as two `Column` siblings, the content one `weight(1f)` —
 * correct at rest, but at maximum font scale the bar itself grows (its own text wraps/grows), and
 * V6 found the last line of scrollable content rendered flush against it with no visible
 * separation — `migration-failed/F20@2x.png`'s "runs in the background" sitting directly on
 * "Rebuild now".
 *
 * Register R-292 (V6 pass 3, `migration-failed/F20-pass3-2x-initial.png`/`-afterscroll.png`/
 * `-fullscroll.png` — reopened from R-252, closed against the same wording): two earlier versions
 * both got this wrong, for two different reasons —
 * 1. `onGloballyPositioned` into a `mutableStateOf`: correct once *settled*, but the padding
 *    started at `0.dp` for the frame the bar's own measurement had not yet reported through (a
 *    state write during layout is only read back on the *next* frame).
 * 2. `SubcomposeLayout` measuring the bar first, then giving the **content's own bottom padding**
 *    the bar's height, while still measuring/placing the scrollable *container* at the *full*
 *    screen height: this fixed the measurement-timing bug, but not the actual defect — padding
 *    *inside* a `verticalScroll` only reserves blank space at the very *end* of the scrollable
 *    content. The bar is a fixed overlay redrawn every frame at a fixed screen position; at *any*
 *    scroll offset other than "scrolled all the way to the bottom", the container's own bottom
 *    edge (still the full screen height) keeps rendering real content directly behind the bar,
 *    which draws over it. That is exactly what V6 pass 3's three screenshots show: wrong at rest,
 *    wrong after a small scroll, right only once scrolled *past* the real content into the
 *    padding.
 *
 * The actual fix (a genuine `Scaffold`-style layout, not a padding hack): the bar is measured
 * first, synchronously, via [subcompose] — but its height then constrains the *content slot's own
 * measured height*, not just its internal padding. [contentConstraints] gives the scrollable
 * `Column` a `maxHeight` of `screenHeight − barHeight`, so its own placed bounds physically stop
 * before the bar's region starts — content cannot render there at *any* scroll offset, first frame
 * included, because the layout system never gives it that space to draw into in the first place.
 */
@Composable
internal fun FailureActionBarScaffold(
    modifier: Modifier = Modifier,
    actionBar: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    SubcomposeLayout(modifier = modifier.fillMaxSize()) { constraints ->
        val looseHeightConstraints = Constraints(
            minWidth = constraints.maxWidth,
            maxWidth = constraints.maxWidth,
            minHeight = 0,
            maxHeight = constraints.maxHeight,
        )
        val barPlaceables = subcompose(FailureActionBarScaffoldSlot.Bar) {
            // R-1003 (halt): this bar had no inset handling at either edge -- `safeAreaBottomPadding()`
            // (`ui/components/SafeArea.kt`) folds straight into this composable's own `barHeightPx`
            // below, the same value [contentConstraints] already subtracts, so the invariant that
            // content never renders behind the bar holds with no second mechanism.
            Column(modifier = Modifier.fillMaxWidth().safeAreaBottomPadding(), content = actionBar)
        }.map { it.measure(looseHeightConstraints) }
        val barHeightPx = barPlaceables.maxOfOrNull { it.height } ?: 0

        val contentHeightPx = (constraints.maxHeight - barHeightPx).coerceAtLeast(0)
        val contentConstraints = Constraints(
            minWidth = constraints.maxWidth,
            maxWidth = constraints.maxWidth,
            minHeight = contentHeightPx,
            maxHeight = contentHeightPx,
        )
        val contentPlaceables = subcompose(FailureActionBarScaffoldSlot.Content) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                content = content,
            )
        }.map { it.measure(contentConstraints) }

        layout(constraints.maxWidth, constraints.maxHeight) {
            contentPlaceables.forEach { it.placeRelative(0, 0) }
            barPlaceables.forEach { it.placeRelative(0, constraints.maxHeight - barHeightPx) }
        }
    }
}

private enum class FailureActionBarScaffoldSlot { Bar, Content }
