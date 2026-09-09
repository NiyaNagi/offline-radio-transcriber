package org.ort.app.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.ort.app.ui.data.FrequencyChangeViewState
import org.ort.app.ui.data.FrequencyDetailView
import org.ort.app.ui.data.FrequencyDetailViewState
import org.ort.app.ui.data.FrequencyPolling
import org.ort.app.ui.data.TimeWindow
import org.ort.app.ui.theme.OrtSpacing
import org.ort.core.SystemClock

/**
 * The frequency drill-in's polling wrapper (R-074, ui-conformance-plan WP8) — the `*Content.kt`
 * composable `OrtNavHost` (WP3) dispatches an opened frequency to. Owns the local "which
 * sub-screen" state for `Frequency-Change`, reached from the detail screen's "Busier than usual"
 * action when [FrequencyDetailViewState.busierThanUsual] holds.
 */
@Composable
public fun FrequencyDetailContent(
    context: Context,
    frequencyHz: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenStation: (String) -> Unit = {},
    // R-276 (register, spec): `Frequency-Change`'s "The N overs" action — WP3 routes this to the
    // Log filtered by frequency and window. Defaulted to a no-op so `OrtNavHost.kt` compiles
    // unchanged until WP3 wires it, the same pattern `onOpenTransmission`/`onOpenStation` already
    // established elsewhere in this package.
    onOpenOvers: (Long, TimeWindow) -> Unit = { _, _ -> },
    // R-432 (register, spec): `Frequency-Change`'s own second bottom pill — see
    // `FrequencyChangeScreen.onOpenThread`'s own doc comment. Defaulted to a no-op so
    // `OrtNavHost.kt` compiles unchanged until WP3 can identify the busiest thread to route to.
    onOpenThread: () -> Unit = {},
    // R-276 (register, spec, coordinator round 2026-09-08): which sub-screen this drill-in opens
    // on. Defaulted to `Detail` so every existing caller (`OrtNavHost.kt`) still compiles
    // unchanged; WP3 passes `Change` when reopening after "The N overs" round-trips through the
    // Log, so system back lands the operator on `Frequency-Change` again, not the drill-in root.
    initialView: FrequencyDetailView = FrequencyDetailView.Detail,
    // R-017: passed straight through to `FrequencyDetailScreen`'s own `backLabel` — see
    // `StationDetailScreen`'s doc comment for the same reasoning.
    backLabel: String = "Frequencies",
) {
    var sub by remember(frequencyHz) { mutableStateOf(initialView) }
    var detail by remember(frequencyHz) { mutableStateOf<FrequencyDetailViewState?>(null) }
    var change by remember(frequencyHz) { mutableStateOf<FrequencyChangeViewState?>(null) }

    LaunchedEffect(frequencyHz) {
        detail = FrequencyPolling.frequencyDetail(context, frequencyHz, nowMillis = SystemClock.wallMillis())
    }
    LaunchedEffect(frequencyHz, sub) {
        if (sub == FrequencyDetailView.Change && change == null) {
            change = FrequencyPolling.frequencyChange(context, frequencyHz)
        }
    }

    when (sub) {
        FrequencyDetailView.Change -> {
            val current = change
            if (current != null) {
                FrequencyChangeScreen(
                    state = current,
                    onBack = { sub = FrequencyDetailView.Detail },
                    modifier = modifier,
                    onOpenOvers = onOpenOvers,
                    onOpenThread = onOpenThread,
                )
            } else {
                LoadingLine(modifier)
            }
        }

        FrequencyDetailView.Detail -> {
            val current = detail
            if (current != null) {
                FrequencyDetailScreen(
                    state = current,
                    onBack = onBack,
                    modifier = modifier,
                    onOpenStation = onOpenStation,
                    onOpenChange = { sub = FrequencyDetailView.Change },
                    backLabel = backLabel,
                )
            } else {
                LoadingLine(modifier)
            }
        }
    }
}

@Composable
private fun LoadingLine(modifier: Modifier) {
    Text(
        text = "Loading…",
        modifier = modifier
            .padding(OrtSpacing.lg)
            .semantics { contentDescription = "Loading frequency detail" },
    )
}
