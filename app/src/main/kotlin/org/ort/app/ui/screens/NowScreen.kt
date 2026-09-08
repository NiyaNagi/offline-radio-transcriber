package org.ort.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.ort.app.status.StatusViewState
import org.ort.app.ui.data.NowSummaryViewState
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * The "Now" home (build-plan P14, `design/canvas/Main.dc.html`). Renders the existing, tested
 * [StatusScreen] for capture state (FR-UI-7, unchanged — build-plan P13's own "no behaviour
 * change" rule) plus [summary]'s real over/station counts from `:data`.
 *
 * Deliberate divergence from the canvas, and why: `Main.dc.html`'s activity chart (the per-hour
 * bar strip) and "Worth knowing" digest section both depend on data no prompt has built yet — an
 * hour-bucketed activity aggregate is P17's job (`FR-UI-9..12`, "activity patterns"), and a digest
 * is M9's, after the M4 fork the build plan has left explicitly open. Rendering a look-alike chart
 * or digest from nothing would be exactly the confident fabrication constitution I forbids, so
 * "Worth knowing" is an honest, explicit empty state instead of an invented one.
 */
@Composable
public fun NowScreen(status: StatusViewState, summary: NowSummaryViewState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().padding(OrtSpacing.lg)) {
        Text(text = "Now", style = OrtType.titleLarge)
        Text(
            text = "${summary.overCount} overs · ${summary.stationCount} stations",
            style = OrtType.caption,
            modifier = Modifier
                .padding(top = OrtSpacing.xs, bottom = OrtSpacing.md)
                .semantics {
                    contentDescription = "${summary.overCount} overs, ${summary.stationCount} stations heard"
                },
        )

        StatusScreen(state = status)

        Text(
            text = "Worth knowing",
            style = OrtType.sectionLabel,
            modifier = Modifier.padding(top = OrtSpacing.lg, bottom = OrtSpacing.sm),
        )
        Text(
            text = "Nothing to report yet.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics {
                contentDescription = "Nothing to report yet — the digest is not built yet, see spec/build-plan.md"
            },
        )
    }
}
