package org.ort.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.RadioRow
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.Sheet
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.components.TextField
import org.ort.app.ui.data.CorrectionScope
import org.ort.app.ui.data.CorrectionTier
import org.ort.app.ui.data.RankedCandidateViewState
import org.ort.app.ui.data.StationSearchOutcome
import org.ort.app.ui.data.StationSearchRow
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * R-052/R-058, `Detail-Correct-A/B/C.dc.html`, `Flow-Correct.dc.html`: the three correction tiers,
 * in order — pick a resolved candidate, search the lexicon, or type a callsign marked unverified.
 * Tier A/B name an already-known identity and, per the flow board's own example ("picked from the
 * resolver's candidates" → "6 overs re-attributed"), propagate to every over sharing the
 * corrected over's voice by default. Tier C is a weaker signal, so it asks for scope explicitly —
 * `Detail-Correct-C.dc.html` is the only board that shows the "Apply to" radio choice.
 *
 * Height-capped and independently scrollable, the same pattern `SearchScreen.kt`'s
 * `SearchFiltersSheet` (WP7) uses: `Sheet` (WP2) is the static surface only — "not the scaffold
 * around it" — so the scrollable, height-capped container wraps the `Sheet(...)` call here rather
 * than being added to the shared component. `fillMaxHeight(0.85f)`, not `fillMaxSize()`, is guide
 * §6.9's "never taller than the screen minus 120px" cap in fraction form, so the scrim a caller
 * draws above this sheet (`TransmissionDetailContent`'s `CorrectionSheetOverlay`) stays visible and
 * tappable to dismiss — an uncapped scrollable column would expand to the full height and silently
 * cover the scrim, swallowing its tap.
 */
@Composable
public fun CorrectionSheet(
    currentCallsign: String?,
    candidates: List<RankedCandidateViewState>,
    everyOverSameVoiceCount: Int,
    onSearchStations: suspend (String) -> StationSearchOutcome,
    onApply: (callsign: String, tier: CorrectionTier, scope: CorrectionScope) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tier by remember { mutableStateOf(CorrectionTierStep.MAIN) }

    Column(modifier = modifier.fillMaxHeight(0.85f).verticalScroll(rememberScrollState())) {
        Sheet(title = "Who was it?") {
            when (tier) {
                CorrectionTierStep.MAIN -> MainTier(
                    currentCallsign = currentCallsign,
                    candidates = candidates,
                    onPick = { callsign ->
                        onApply(callsign, CorrectionTier.PICK_CANDIDATE, CorrectionScope.EVERY_OVER_SAME_VOICE)
                    },
                    onOpenSearch = { tier = CorrectionTierStep.SEARCH },
                    onOpenType = { tier = CorrectionTierStep.TYPE },
                )

                CorrectionTierStep.SEARCH -> SearchTier(
                    onSearchStations = onSearchStations,
                    onPick = { callsign ->
                        onApply(callsign, CorrectionTier.SEARCH_LEXICON, CorrectionScope.EVERY_OVER_SAME_VOICE)
                    },
                    onBack = { tier = CorrectionTierStep.MAIN },
                    onOpenType = { tier = CorrectionTierStep.TYPE },
                )

                CorrectionTierStep.TYPE -> TypeTier(
                    everyOverSameVoiceCount = everyOverSameVoiceCount,
                    onApply = { callsign, scope -> onApply(callsign, CorrectionTier.FREE_TEXT, scope) },
                    onBack = { tier = CorrectionTierStep.MAIN },
                )
            }
            TextAction(text = "Cancel", onClick = onDismiss, modifier = Modifier.padding(top = OrtSpacing.sm))
        }
    }
}

private enum class CorrectionTierStep { MAIN, SEARCH, TYPE }

/** Tier A: the resolver's other candidates, then the two "someone else" doors into tier B/C. */
@Composable
private fun MainTier(
    currentCallsign: String?,
    candidates: List<RankedCandidateViewState>,
    onPick: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenType: () -> Unit,
) {
    val others = candidates.filterNot { it.callsign == currentCallsign }
    SectionHeader(label = "The resolver's other candidates")
    others.forEach { candidate ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(role = Role.Button, onClick = { onPick(candidate.callsign) })
                .padding(vertical = OrtSpacing.sm)
                .semantics(mergeDescendants = true) {
                    contentDescription = "Correct to ${candidate.callsign}, ${candidate.scoreLabel}"
                },
        ) {
            Text(text = candidate.callsign, style = OrtType.callsignRow, color = OrtColors.textHigh)
            Text(text = candidate.scoreLabel, style = OrtType.cardBody, color = OrtColors.textDim)
        }
    }
    SectionHeader(label = "Someone else", modifier = Modifier.padding(top = OrtSpacing.md))
    TextAction(text = "A station heard before", onClick = onOpenSearch)
    TextAction(text = "Type a callsign", onClick = onOpenType)
}

/**
 * R-185 (halt), `Detail-Correct-B.dc.html`: "A station heard before" — [onSearchStations] (real
 * [org.ort.app.ui.data.CorrectionPolling.searchHeardStations]) is Tier B's own search of stations
 * *this device has heard*, distinct from Tier C's grammar/ITU validator and from the true lexicon
 * search audit F-018 gave this tier's old call site — see [CorrectionPolling.searchHeardStations]'s
 * own doc comment for why this reverts that decision. Board's own "Matches · N of M" and "Not here
 * — type a callsign instead" (routes to Tier C) both render from the real [StationSearchOutcome].
 */
@Composable
private fun SearchTier(
    onSearchStations: suspend (String) -> StationSearchOutcome,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
    onOpenType: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf<StationSearchOutcome?>(null) }

    LaunchedEffect(Unit) { outcome = onSearchStations("") }

    TextAction(text = "‹ Back", onClick = onBack)
    SectionHeader(label = "A station heard before", modifier = Modifier.padding(top = OrtSpacing.sm))
    TextField(
        value = query,
        onValueChange = { text ->
            query = text
            scope.launch { outcome = onSearchStations(text) }
        },
        placeholder = "callsign or name",
        mono = true,
        contentDescriptionText = "Search stations heard",
    )
    val result = outcome
    if (result != null) {
        SectionHeader(
            label = "Matches · ${result.matchCount} of ${result.totalCount}",
            modifier = Modifier.padding(top = OrtSpacing.md),
        )
        result.rows.forEach { row ->
            StationSearchResultRow(row = row, query = query, onClick = { onPick(row.stationId) })
        }
    }
    Text(
        text = "Only stations this phone has heard appear here — it is not a callsign database. " +
            "Naming one that has a voice on file also tells the matcher this voice is theirs.",
        style = OrtType.subLine,
        color = OrtColors.textFaint,
        modifier = Modifier.padding(top = OrtSpacing.md),
    )
    TextAction(
        text = "Not here — type a callsign instead",
        onClick = onOpenType,
        modifier = Modifier.padding(top = OrtSpacing.md),
    )
}

@Composable
private fun StationSearchResultRow(row: StationSearchRow, query: String, onClick: () -> Unit) {
    val name = row.userName
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = OrtSpacing.sm)
            .semantics(mergeDescendants = true) {
                contentDescription = "Correct to ${row.stationId}" +
                    (name?.let { ", $it" } ?: "") + ", ${row.evidence}"
            },
    ) {
        // Match highlighting: the typed query, where it prefixes the result, in `accent/green`
        // (`Detail-Correct-B.dc.html`'s "KA7" prefix in green ahead of "LWH").
        if (query.isNotBlank() && row.stationId.startsWith(query, ignoreCase = true)) {
            Text(text = row.stationId, style = OrtType.callsignRow, color = OrtColors.accentGreen)
        } else {
            Text(text = row.stationId, style = OrtType.callsignRow, color = OrtColors.textHigh)
        }
        name?.let {
            Text(text = it, style = OrtType.cardBody, color = OrtColors.textBody)
        }
        Text(text = row.evidence, style = OrtType.cardBody, color = OrtColors.textDim)
    }
}

/** Tier C: typed callsign, recorded unverified, with the explicit scope choice. */
@Composable
private fun TypeTier(
    everyOverSameVoiceCount: Int,
    onApply: (callsign: String, scope: CorrectionScope) -> Unit,
    onBack: () -> Unit,
) {
    var callsign by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf(CorrectionScope.THIS_OVER_ONLY) }

    TextAction(text = "‹ Back", onClick = onBack)
    SectionHeader(label = "Type a callsign", modifier = Modifier.padding(top = OrtSpacing.sm))
    TextField(
        value = callsign,
        onValueChange = { callsign = it.uppercase() },
        mono = true,
        contentDescriptionText = "Typed callsign",
    )
    Text(
        text = "Recorded as unverified. This callsign was not parsed from the audio and has not " +
            "been heard before, so it cannot feed the priors or the voice match.",
        style = OrtType.cardBody,
        color = OrtColors.accentAmberText,
        modifier = Modifier.padding(top = OrtSpacing.sm),
    )
    SectionHeader(label = "Apply to", modifier = Modifier.padding(top = OrtSpacing.md))
    RadioRow(
        label = "This over only",
        selected = scope == CorrectionScope.THIS_OVER_ONLY,
        onClick = { scope = CorrectionScope.THIS_OVER_ONLY },
    )
    RadioRow(
        label = "Every over matched to this voice",
        selected = scope == CorrectionScope.EVERY_OVER_SAME_VOICE,
        onClick = { scope = CorrectionScope.EVERY_OVER_SAME_VOICE },
        count = everyOverSameVoiceCount.toString(),
    )
    PrimaryButton(
        text = "Save unverified correction",
        onClick = { onApply(callsign, scope) },
        enabled = callsign.isNotBlank(),
        modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.lg),
    )
}
