package org.ort.app.ui.failures

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints

/**
 * Register R-151 (same class as R-123): every takeover with a fixed bottom action bar
 * (`FailRouteScreen`, `FailStorageHaltScreen`, `FailMigrationScreen`, `FailAssetSwapScreen`) used
 * to split its content and its bar as two `Column` siblings, the content one `weight(1f)` —
 * correct at rest, but at maximum font scale the bar itself grows (its own text wraps/grows), and
 * V6 found the last line of scrollable content rendered flush against it with no visible
 * separation — `migration-failed/F20@2x.png`'s "runs in the background" sitting directly on
 * "Rebuild now". The bar's real height (which reacts to font scale on its own) becomes the
 * scrollable content's own bottom content padding, so the content can always scroll far enough to
 * clear the bar completely, however tall the bar turns out to be — never a guessed fixed dp value
 * that could fall out of sync with the bar's own layout.
 *
 * Register R-252 (V6 pass 2, `migration-failed/F20-pass2@2x.png`): an earlier version measured the
 * bar with `onGloballyPositioned` into a `mutableStateOf` — correct once *settled*, but the
 * padding started at `0.dp` for the frame the bar's own measurement had not yet reported through
 * (state written during composition/layout is only read back on the *next* frame), so the first
 * frame at maximum font scale rendered exactly the overlap the register row caught: the bottom
 * bullet under the bar, "Save a diagnostic bundle first" under the closing paragraph. `SubcomposeLayout`
 * fixes this at the root — [subcompose] measures the bar's real content *during this same
 * measurement pass*, synchronously, before the scrollable content is ever subcomposed, so the
 * bottom padding the content receives is correct from the very first frame; there is no "settle"
 * frame to have a gap during.
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
            Column(modifier = Modifier.fillMaxWidth(), content = actionBar)
        }.map { it.measure(looseHeightConstraints) }
        val barHeightPx = barPlaceables.maxOfOrNull { it.height } ?: 0
        val barHeightDp = barHeightPx.toDp()

        val contentPlaceables = subcompose(FailureActionBarScaffoldSlot.Content) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = barHeightDp),
                content = content,
            )
        }.map { it.measure(constraints) }

        layout(constraints.maxWidth, constraints.maxHeight) {
            contentPlaceables.forEach { it.placeRelative(0, 0) }
            barPlaceables.forEach { it.placeRelative(0, constraints.maxHeight - barHeightPx) }
        }
    }
}

private enum class FailureActionBarScaffoldSlot { Bar, Content }
