package org.ort.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
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
import org.ort.app.ui.data.CorrectionScope
import org.ort.app.ui.data.CorrectionTier
import org.ort.app.ui.data.RankedCandidateViewState
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType
import org.ort.pipeline.passb.LexiconMatch

/**
 * R-052/R-058, `Detail-Correct-A/B/C.dc.html`, `Flow-Correct.dc.html`: the three correction tiers,
 * in order — pick a resolved candidate, search the lexicon, or type a callsign marked unverified.
 * Tier A/B name an already-known identity and, per the flow board's own example ("picked from the
 * resolver's candidates" → "6 overs re-attributed"), propagate to every over sharing the
 * corrected over's voice by default. Tier C is a weaker signal, so it asks for scope explicitly —
 * `Detail-Correct-C.dc.html` is the only board that shows the "Apply to" radio choice.
 */
@Composable
public fun CorrectionSheet(
    currentCallsign: String?,
    candidates: List<RankedCandidateViewState>,
    everyOverSameVoiceCount: Int,
    onSearchLexicon: suspend (String) -> List<LexiconMatch>,
    onApply: (callsign: String, tier: CorrectionTier, scope: CorrectionScope) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tier by remember { mutableStateOf(CorrectionTierStep.MAIN) }

    Sheet(title = "Who was it?", modifier = modifier) {
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
                onSearchLexicon = onSearchLexicon,
                onPick = { callsign ->
                    onApply(callsign, CorrectionTier.SEARCH_LEXICON, CorrectionScope.EVERY_OVER_SAME_VOICE)
                },
                onBack = { tier = CorrectionTierStep.MAIN },
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

/** Tier B: search the lexicon (existing `ReaderPolling.searchLexicon`, called — not edited). */
@Composable
private fun SearchTier(
    onSearchLexicon: suspend (String) -> List<LexiconMatch>,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<LexiconMatch>()) }

    TextAction(text = "‹ Back", onClick = onBack)
    SectionHeader(label = "A station heard before", modifier = Modifier.padding(top = OrtSpacing.sm))
    TextField(
        value = query,
        onValueChange = { text ->
            query = text
            scope.launch { results = onSearchLexicon(text) }
        },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search the lexicon" },
    )
    results.forEach { match ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(role = Role.Button, onClick = { onPick(match.callsign) })
                .padding(vertical = OrtSpacing.sm)
                .semantics(mergeDescendants = true) {
                    contentDescription = "Correct to ${match.callsign}, ${match.ituCountry} (${match.ituPrefix})"
                },
        ) {
            // Match highlighting: the typed query, where it prefixes the result, in `accent/green`
            // (`Detail-Correct-B.dc.html`'s "KA7" prefix in green ahead of "LWH"). A plain fallback
            // when the result is not a prefix match keeps every real result visible either way.
            if (query.isNotBlank() && match.callsign.startsWith(query, ignoreCase = true)) {
                Text(
                    text = match.callsign,
                    style = OrtType.callsignRow,
                    color = OrtColors.accentGreen,
                )
            } else {
                Text(text = match.callsign, style = OrtType.callsignRow, color = OrtColors.textHigh)
            }
            Text(text = "${match.ituCountry} (${match.ituPrefix})", style = OrtType.cardBody, color = OrtColors.textDim)
        }
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
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Typed callsign" },
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
