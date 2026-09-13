package org.ort.app.ui.improve

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
 * Register R-1056 (field session 1's own class — "primary buttons under the navigation bar",
 * repeated): every Improve state with a fixed bottom action row (`Improve-Running.dc.html`,
 * `Improve-Done.dc.html`) used to lay its body and its action row out as ordinary, unconstrained
 * `Column` siblings with a `Column(Modifier.weight(1f)) {}` spacer standing in for a real
 * constraint. At font scale 2.0, with a banner sharing the screen (`Fail-Level.dc.html`'s own "too
 * quiet" state shrinks `NavHostBody`'s weighted content `Box` further — the validator's own
 * evidence), the body's real height outgrew whatever the weighted spacer left, and a plain `Column`
 * given too little room does not clip or scroll on its own: it keeps placing children past its own
 * bounds. That is exactly what register row R-1056's `05-done@2x-dump.xml` showed — the button
 * row's bounds ending at the literal screen height, no bottom inset, sharing a top edge with body
 * text drawn straight through it.
 *
 * Structurally identical to `ui/failures/FailureActionBarScaffold.kt` (that file's own R-151/R-292
 * doc comment has the fuller account of why this exact shape, not a hand-rolled variant) — the
 * same shape `SetupScaffold.kt`'s own class doc (R-360) independently converged on as the only one
 * proven correct on a real device at font scale 2.0 on cold launch: exactly two [subcompose] slots,
 * the bar measured first with loose constraints, [safeAreaBottomPadding] folded into its own
 * measured height so a pinned Improve action row clears the navigation bar the same way every
 * other pinned bottom surface in this app does (design-guide §10.1) — `windowInsetsPadding`
 * consumes the inset it reads for the subtree below, so this reserves real space only where
 * `NavHostBody`'s own outer copy has not already claimed it, never doubling on top of it (the same
 * consumption chain `OrtNavHost.kt`'s own R-1003 doc comment relies on) — and the scrollable body
 * second, given a hard `maxHeight` of `screenHeight − barHeight`. Never bottom *padding* inside the
 * scroll region instead: R-292 already found that only reserves blank space at the very end of the
 * scrollable content, and lets real content still render — and be drawn over — behind the bar at
 * every other scroll offset, first frame included.
 *
 * `internal` — this file's package, `ui/improve`, is this builder's own file-ownership row; no
 * other package should reach for this as a shortcut around its own screen's real shape.
 */
@Composable
internal fun ImproveActionBarScaffold(
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
        val barPlaceables = subcompose(ImproveActionBarScaffoldSlot.Bar) {
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
        val contentPlaceables = subcompose(ImproveActionBarScaffoldSlot.Content) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                content = content,
            )
        }.map { it.measure(contentConstraints) }

        layout(constraints.maxWidth, constraints.maxHeight) {
            contentPlaceables.forEach { it.placeRelative(0, 0) }
            barPlaceables.forEach { it.placeRelative(0, constraints.maxHeight - barHeightPx) }
        }
    }
}

private enum class ImproveActionBarScaffoldSlot { Bar, Content }
