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
import org.ort.app.ui.data.StationDetailViewState
import org.ort.app.ui.data.StationIdentityViewState
import org.ort.app.ui.data.StationPatternViewState
import org.ort.app.ui.data.StationPolling
import org.ort.app.ui.theme.OrtSpacing
import org.ort.core.SystemClock

private enum class StationSubScreen { NONE, PATTERN, IDENTITY }

/**
 * The station drill-in's polling wrapper (R-071/R-072/R-073, ui-conformance-plan WP8) — the
 * `*Content.kt` composable `OrtNavHost` (WP3) dispatches an opened station to. Owns the local
 * "which sub-screen" state for `Station-Pattern` (reached from "By day") and `Station-Identity`
 * (reached from the header's kebab), since both are full-screen presentations over this same
 * station rather than separate drawer destinations.
 */
@Composable
public fun StationDetailContent(
    context: Context,
    stationId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenTransmission: (String) -> Unit = {},
) {
    var sub by remember(stationId) { mutableStateOf(StationSubScreen.NONE) }
    var detail by remember(stationId) { mutableStateOf<StationDetailViewState?>(null) }
    var pattern by remember(stationId) { mutableStateOf<StationPatternViewState?>(null) }
    var identity by remember(stationId) { mutableStateOf<StationIdentityViewState?>(null) }

    LaunchedEffect(stationId) {
        detail = StationPolling.stationDetail(context, stationId, nowMillis = SystemClock.wallMillis())
    }
    LaunchedEffect(stationId, sub) {
        if (sub == StationSubScreen.PATTERN && pattern == null) {
            pattern = StationPolling.stationPattern(context, stationId, nowMillis = SystemClock.wallMillis())
        }
        if (sub == StationSubScreen.IDENTITY && identity == null) {
            identity = StationPolling.stationIdentity(context, stationId)
        }
    }

    when (sub) {
        StationSubScreen.PATTERN -> {
            val current = pattern
            if (current != null) {
                StationPatternScreen(state = current, onBack = { sub = StationSubScreen.NONE }, modifier = modifier)
            } else {
                LoadingLine(modifier)
            }
        }

        StationSubScreen.IDENTITY -> {
            val current = identity
            if (current != null) {
                StationIdentityScreen(state = current, onBack = { sub = StationSubScreen.NONE }, modifier = modifier)
            } else {
                LoadingLine(modifier)
            }
        }

        StationSubScreen.NONE -> {
            val current = detail
            if (current != null) {
                StationDetailScreen(
                    state = current,
                    onBack = onBack,
                    modifier = modifier,
                    onOpenTransmission = onOpenTransmission,
                    onOpenPattern = { sub = StationSubScreen.PATTERN },
                    onOpenIdentity = { sub = StationSubScreen.IDENTITY },
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
            .semantics { contentDescription = "Loading station detail" },
    )
}
