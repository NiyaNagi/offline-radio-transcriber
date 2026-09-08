package org.ort.app.ui.failures

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Register R-151 (same class as R-123): every takeover with a fixed bottom action bar
 * (`FailRouteScreen`, `FailStorageHaltScreen`, `FailMigrationScreen`, `FailAssetSwapScreen`) used
 * to split its content and its bar as two `Column` siblings, the content one `weight(1f)` —
 * correct at rest, but at maximum font scale the bar itself grows (its own text wraps/grows), and
 * V6 found the last line of scrollable content rendered flush against it with no visible
 * separation — `migration-failed/F20@2x.png`'s "runs in the background" sitting directly on
 * "Rebuild now". The fix the coordinator asked to live in one place: the bar is measured with
 * [onGloballyPositioned] and its *real* height (which reacts to font scale on its own) becomes
 * the scrollable content's own bottom content padding, so the content can always scroll far
 * enough to clear the bar completely, however tall the bar turns out to be — never a guessed
 * fixed dp value that could fall out of sync with the bar's own layout.
 */
@Composable
internal fun FailureActionBarScaffold(
    modifier: Modifier = Modifier,
    actionBar: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    var actionBarHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = actionBarHeight),
            content = content,
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    actionBarHeight = with(density) { coordinates.size.height.toDp() }
                },
            content = actionBar,
        )
    }
}
