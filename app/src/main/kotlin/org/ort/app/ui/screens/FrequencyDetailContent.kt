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
import org.ort.app.ui.data.FrequencyDetailViewState
import org.ort.app.ui.data.FrequencyPolling
import org.ort.app.ui.theme.OrtSpacing
import org.ort.core.SystemClock

private enum class FrequencySubScreen { NONE, CHANGE }

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
    // R-017: passed straight through to `FrequencyDetailScreen`'s own `backLabel` — see
    // `StationDetailScreen`'s doc comment for the same reasoning.
    backLabel: String = "Frequencies",
) {
    var sub by remember(frequencyHz) { mutableStateOf(FrequencySubScreen.NONE) }
    var detail by remember(frequencyHz) { mutableStateOf<FrequencyDetailViewState?>(null) }
    var change by remember(frequencyHz) { mutableStateOf<FrequencyChangeViewState?>(null) }

    LaunchedEffect(frequencyHz) {
        detail = FrequencyPolling.frequencyDetail(context, frequencyHz, nowMillis = SystemClock.wallMillis())
    }
    LaunchedEffect(frequencyHz, sub) {
        if (sub == FrequencySubScreen.CHANGE && change == null) {
            change = FrequencyPolling.frequencyChange(context, frequencyHz)
        }
    }

    when (sub) {
        FrequencySubScreen.CHANGE -> {
            val current = change
            if (current != null) {
                FrequencyChangeScreen(
                    state = current,
                    onBack = { sub = FrequencySubScreen.NONE },
                    modifier = modifier,
                    onViewOvers = {},
                )
            } else {
                LoadingLine(modifier)
            }
        }

        FrequencySubScreen.NONE -> {
            val current = detail
            if (current != null) {
                FrequencyDetailScreen(
                    state = current,
                    onBack = onBack,
                    modifier = modifier,
                    onOpenStation = onOpenStation,
                    onOpenChange = { sub = FrequencySubScreen.CHANGE },
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
