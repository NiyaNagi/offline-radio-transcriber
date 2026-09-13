package org.ort.app.ui.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.ort.app.ui.data.FrequencyListEntryViewState
import org.ort.app.ui.data.FrequencyPolling

/**
 * The "Frequencies" destination's polling wrapper (R-074, ui-conformance-plan WP8) — the
 * `*Content.kt` composable `OrtNavHost` (WP3) dispatches `FREQUENCIES` to.
 */
@Composable
public fun FrequenciesContent(context: Context, onOpen: (Long) -> Unit, modifier: Modifier = Modifier) {
    var frequencies by remember { mutableStateOf(emptyList<FrequencyListEntryViewState>()) }
    // Register R-1022/R-1051 (halt, constitution I/IV): `true` until the first real
    // `FrequencyPolling.listFrequencies` read lands — see `FrequenciesListScreen`'s own `loading`
    // parameter kdoc.
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        frequencies = FrequencyPolling.listFrequencies(context)
        loading = false
    }
    FrequenciesListScreen(
        frequencies = frequencies,
        onOpen = onOpen,
        modifier = modifier,
        // R-215: "N heard all time · M tonight" — the frequency counts, not any one row's.
        heardAllTimeCount = frequencies.size,
        heardTonightCount = frequencies.count { it.tonightCount > 0 },
        loading = loading,
    )
}
