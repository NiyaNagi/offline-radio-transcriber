package org.ort.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ort.app.ui.audio.PlaybackOutcome
import org.ort.app.ui.audio.PlaybackRate
import org.ort.app.ui.audio.TransmissionAudioPlayer
import org.ort.app.ui.components.ActionBar
import org.ort.app.ui.components.Badge
import org.ort.app.ui.components.BadgeKind
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.PriorBar
import org.ort.app.ui.components.RadioRow
import org.ort.app.ui.components.SecondaryButton
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.components.TextField
import org.ort.app.ui.components.TitleAttributionRow
import org.ort.app.ui.components.WaveformCard
import org.ort.app.ui.components.WaveformViewState
import org.ort.app.ui.data.AmbiguousCandidateViewState
import org.ort.app.ui.data.DetailBodyViewState
import org.ort.app.ui.data.DetailViewState
import org.ort.app.ui.data.LabelCertainty
import org.ort.app.ui.data.LabelOutcome
import org.ort.app.ui.data.LabelledSample
import org.ort.app.ui.data.TransmissionDetailViewState
import org.ort.app.ui.data.TriedStepViewState
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType
import org.ort.core.AttributionState

/**
 * The transmission detail drill-in (ui-conformance WP6, R-050/R-051/R-053/R-054/R-055/R-056/R-057;
 * originally build-plan P14/P16). Four states, one layout (`Detail.dc.html` = INFERRED,
 * `Detail-Confirmed.dc.html`, `Detail-Ambiguous.dc.html`, `Detail-Unknown.dc.html`): marker +
 * title-size callsign, a one-sentence explanation that always states any confidence the
 * attribution carries in prose (the rewritten FR-UI-4 rule — see [org.ort.app.ui.data.DetailViewStateMapper]'s
 * class doc), a mono meta row, playback, the transcript, an inline "why this callsign" preview
 * with a link to the exhaustive [DetailWhyScreen], and a state-dependent bottom action bar.
 *
 * This screen no longer draws its own "‹ Back" row — the host (`ui-conformance-plan` §D's
 * nav-host rule) renders [org.ort.app.ui.components.DrillInHeader] around whichever content
 * composable is current; [TransmissionDetailContent] renders it for this screen today.
 *
 * **R-050, closed.** The header callsign now renders through WP2's
 * [org.ort.app.ui.components.TitleAttributionRow] (27sp mono, the marker at
 * [org.ort.app.ui.components.MARKER_TITLE_SIZE]) — the title-size gap this package's earlier
 * revision reported against [org.ort.app.ui.components.AttributionRow] is closed.
 * [TitleAttributionRow] carries no `alternate` parameter (unlike `AttributionRow`), so AMBIGUOUS's
 * "or QRF" runner-up is rendered as one further `Text` right after it, in the exact style
 * `AttributionRow` itself uses for the same fact — an addition, not a look-alike of
 * [TitleAttributionRow] itself.
 */
@Suppress("LongParameterList") // one callback per distinct, independently-testable interaction the
// four states need (`Detail-Ambiguous.dc.html`'s chooser and `Detail-Unknown.dc.html`'s "I know
// who this is" are genuinely different actions, not variations of one) — same precedent as
// `:data`'s `SearchDao` (see its own doc comment) for a wide, semantically-flat parameter list.
@Composable
public fun TransmissionDetailScreen(
    state: DetailViewState,
    player: TransmissionAudioPlayer,
    onOpenTransmission: (String) -> Unit = {},
    onNotRight: () -> Unit = {},
    onConfirm: () -> Unit = {},
    onChooseCandidate: (String) -> Unit = {},
    onNeither: () -> Unit = {},
    onLeaveAmbiguous: () -> Unit = {},
    onIKnowWhoThisIs: () -> Unit = {},
    onOpenWhy: () -> Unit = {},
    onOpenRevisions: () -> Unit = {},
    onRecordLabel: suspend (LabelledSample) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var labelExpanded by remember(state.detail.id) { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            HeaderSection(state, onOpenTransmission)
            PlaybackSection(state.detail, player)
            TranscriptSection(state.detail)
            AmbiguousChooserSection(state.body, onChooseCandidate, onNeither)
            UnknownTriedSection(state.body)
            WhySection(state, onOpenWhy)
            RevisionsLinkSection(state.detail, onOpenRevisions)
            LabelSampleSection(
                detail = state.detail,
                expanded = labelExpanded,
                onToggle = { labelExpanded = !labelExpanded },
                onRecordLabel = onRecordLabel,
            )
        }
        BottomActionBar(
            state = state,
            onNotRight = onNotRight,
            onConfirm = onConfirm,
            onLeaveAmbiguous = onLeaveAmbiguous,
            onIKnowWhoThisIs = onIKnowWhoThisIs,
            onRecordLabel = { labelExpanded = true },
        )
    }
}

@Composable
private fun HeaderSection(state: DetailViewState, onOpenTransmission: (String) -> Unit) {
    val detail = state.detail
    val alternate = (state.body as? DetailBodyViewState.Ambiguous)?.alternateCallsign
    Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TitleAttributionRow(attribution = detail.attribution, callsign = detail.attribution.stationId)
            // TitleAttributionRow has no `alternate` param — the AMBIGUOUS "or QRF" runner-up is
            // added here, in AttributionRow's own style for the same fact (guide §6.1).
            if (alternate != null) {
                Spacer(modifier = Modifier.width(OrtSpacing.xs))
                Text(text = "or $alternate", style = OrtType.subLine, color = OrtColors.accentAmber)
            }
        }
        if (detail.attribution.corrected) {
            Badge(text = "corrected", kind = BadgeKind.CORRECTED, modifier = Modifier.padding(top = OrtSpacing.xs))
        }
        Text(
            text = state.body.explanation,
            style = OrtType.subtitle,
            color = OrtColors.textMuted,
            modifier = Modifier.padding(top = OrtSpacing.sm),
        )
        val inferred = state.body as? DetailBodyViewState.Inferred
        if (inferred?.sourceTransmissionId != null) {
            TextAction(
                text = "Open the source over",
                onClick = { onOpenTransmission(inferred.sourceTransmissionId) },
            )
        }
        Row(modifier = Modifier.padding(top = OrtSpacing.xs)) {
            Text(text = detail.timeLabel, style = OrtType.timeFreq, color = OrtColors.textFaint)
            Spacer(modifier = Modifier.width(OrtSpacing.md))
            Text(text = detail.frequencyLabel, style = OrtType.timeFreq, color = OrtColors.textFaint)
            Spacer(modifier = Modifier.width(OrtSpacing.md))
            Text(text = detail.durationLabel, style = OrtType.timeFreq, color = OrtColors.textFaint)
            detail.signalLabel?.let {
                Spacer(modifier = Modifier.width(OrtSpacing.md))
                Text(text = it, style = OrtType.timeFreq, color = OrtColors.textFaint)
            }
        }
    }
}

/**
 * R-054, `Detail-Playback.dc.html`: idle / playing (position, scrub, speed) / no-audio /
 * unavailable, via [WaveformCard]. No amplitude data exists anywhere in the schema for a real
 * waveform shape (`TransmissionDetailViewState` carries none), so [WaveformBar] lists are always
 * empty here — an honest flat card with a working play control and duration, not a fabricated
 * shape (constitution I). Scrubbing (WP2's `onScrub`) seeks the real player directly —
 * `positionFraction` is then read back from it on the next poll tick, the same as any other
 * player-driven position change. The spoken-word outline (no word-timing data exists anywhere)
 * stays a named gap in this package's CHANGELOG rather than faked.
 */
@Composable
private fun PlaybackSection(detail: TransmissionDetailViewState, player: TransmissionAudioPlayer) {
    val scope = rememberCoroutineScope()
    var unavailableReason by remember(detail.id) { mutableStateOf<String?>(null) }
    var playing by remember(detail.id) { mutableStateOf(false) }
    var positionFraction by remember(detail.id) { mutableStateOf(0f) }
    var rate by remember(detail.id) { mutableStateOf(PlaybackRate.NORMAL) }

    LaunchedEffect(detail.id, playing) {
        while (playing) {
            positionFraction = player.positionFraction()
            playing = player.isPlaying()
            delay(POSITION_POLL_MILLIS)
        }
    }

    val durationSeconds = detail.durationLabel.removeSuffix("s").toDoubleOrNull() ?: 0.0
    val waveformState = when {
        unavailableReason != null -> WaveformViewState.Unavailable(unavailableReason.orEmpty())
        !detail.hasAudio -> WaveformViewState.NoAudio
        playing -> WaveformViewState.Playing(
            bars = emptyList(),
            cursorFraction = positionFraction,
            positionLabel = "%.1f".format(positionFraction * durationSeconds),
            durationLabel = detail.durationLabel,
            speedLabel = rate.label,
        )

        else -> WaveformViewState.Idle(bars = emptyList(), durationLabel = detail.durationLabel)
    }

    Column(modifier = Modifier.padding(OrtSpacing.lg)) {
        WaveformCard(
            state = waveformState,
            onPlayPause = if (detail.hasAudio) {
                {
                    scope.launch {
                        if (playing) {
                            player.pause()
                            playing = false
                        } else {
                            when (val outcome = player.play(detail.id)) {
                                PlaybackOutcome.Played -> {
                                    unavailableReason = null
                                    playing = true
                                }

                                is PlaybackOutcome.Unavailable -> {
                                    unavailableReason = outcome.reason
                                    playing = false
                                }
                            }
                        }
                    }
                }
            } else {
                null
            },
            // R-054: WP2's WaveformCard reports a scrub as a 0f..1f fraction; seeking the real
            // player and updating positionFraction immediately (not waiting for the next poll
            // tick) is what makes the cursor track the drag rather than lag a whole poll interval.
            onScrub = if (detail.hasAudio) {
                { fraction ->
                    player.seekToFraction(fraction)
                    positionFraction = fraction
                }
            } else {
                null
            },
            modifier = Modifier.testTag("waveform-card"),
        )
        if (playing) {
            Row(modifier = Modifier.padding(top = OrtSpacing.sm)) {
                PlaybackRate.entries.forEach { candidate ->
                    TextAction(
                        text = candidate.label,
                        onClick = {
                            rate = candidate
                            player.setRate(candidate)
                        },
                    )
                }
            }
        }
    }
}

/**
 * The transcript, with the callsign highlighted when it appears verbatim in the text (a literal,
 * honest substring check — never a phonetic-word guess: no data anywhere maps a transcript's
 * spoken words back to the callsign that produced them, so this never claims a highlight it cannot
 * back). CONFIRMED highlights `highlightGreen`; every other carrying state highlights `highlightAmber`.
 */
@Composable
private fun TranscriptSection(detail: TransmissionDetailViewState) {
    val stationId = detail.attribution.stationId
    val highlight = if (detail.attribution.state == AttributionState.CONFIRMED) {
        OrtColors.highlightGreen
    } else {
        OrtColors.highlightAmber
    }
    val text = if (stationId != null) {
        val index = detail.transcriptText.indexOf(stationId, ignoreCase = true)
        if (index >= 0) {
            buildAnnotatedString {
                append(detail.transcriptText.substring(0, index))
                withStyle(SpanStyle(background = highlight)) {
                    append(detail.transcriptText.substring(index, index + stationId.length))
                }
                append(detail.transcriptText.substring(index + stationId.length))
            }
        } else {
            buildAnnotatedString { append(detail.transcriptText) }
        }
    } else {
        buildAnnotatedString { append(detail.transcriptText) }
    }
    Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)) {
        Text(text = text, style = OrtType.bodyProse, color = OrtColors.textSecondary)
    }
}

/** R-057: `Detail-Ambiguous.dc.html`'s chooser — both real candidates with their evidence, `Neither`. */
@Composable
private fun AmbiguousChooserSection(
    body: DetailBodyViewState,
    onChooseCandidate: (String) -> Unit,
    onNeither: () -> Unit,
) {
    if (body !is DetailBodyViewState.Ambiguous) return
    Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)) {
        SectionHeader(label = "Choose, if you heard it")
        body.candidates.forEach { candidate: AmbiguousCandidateViewState ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = OrtSpacing.sm)
                    .semantics(mergeDescendants = true) {
                        contentDescription =
                            "${candidate.callsign}, ${candidate.evidence}, score ${candidate.scoreLabel}"
                    },
            ) {
                Text(text = candidate.callsign, style = OrtType.callsignRow, color = OrtColors.textHigh)
                Spacer(modifier = Modifier.width(OrtSpacing.md))
                Text(text = candidate.evidence, style = OrtType.cardBody, color = OrtColors.textDim)
            }
            TextAction(text = "Choose ${candidate.callsign}", onClick = { onChooseCandidate(candidate.callsign) })
        }
        TextAction(text = "Neither — it was something else", onClick = onNeither)
    }
}

/** R-057: `Detail-Unknown.dc.html`'s "what was tried" — only real, non-fabricated steps. */
@Composable
private fun UnknownTriedSection(body: DetailBodyViewState) {
    if (body !is DetailBodyViewState.Unknown) return
    Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)) {
        SectionHeader(label = "What was tried")
        body.tried.forEach { step: TriedStepViewState ->
            Column(modifier = Modifier.padding(vertical = OrtSpacing.xs)) {
                Text(text = step.title, style = OrtType.control, color = OrtColors.textBody)
                step.detail?.let {
                    Text(
                        text = it,
                        style = OrtType.cardBody,
                        color = OrtColors.textFaint,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/** R-051, FR-UI-8: the inline "why this callsign" preview + a link to the exhaustive [DetailWhyScreen]. */
@Composable
private fun WhySection(state: DetailViewState, onOpenWhy: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)) {
        SectionHeader(label = "Why this callsign", trailingActionLabel = "Full lattice", onTrailingAction = onOpenWhy)
        val why = state.why
        if (!why.hasData) {
            Text(
                text = "No resolver output recorded for this transmission yet.",
                style = OrtType.cardBody,
                color = OrtColors.textMuted,
                modifier = Modifier.padding(top = OrtSpacing.xs),
            )
            return
        }
        why.latticeSummary?.let {
            Text(
                text = "Lattice: $it",
                style = OrtType.cardBody,
                color = OrtColors.textFaint,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
            )
        }
        why.candidates.forEach { candidate ->
            Text(
                text = "${candidate.callsign} — ${candidate.scoreLabel}${if (candidate.chosen) " · chosen" else ""}",
                style = OrtType.control,
                color = if (candidate.chosen) OrtColors.textHigh else OrtColors.textDim,
            )
        }
        why.priors.forEach { prior -> PriorBar(state = prior, modifier = Modifier.padding(top = OrtSpacing.xs)) }
        why.runnerUp?.let {
            Text(
                text = "Runner-up · ${it.callsign} (${it.scoreLabel})",
                style = OrtType.cardBody,
                color = OrtColors.textFaint,
                modifier = Modifier.padding(top = OrtSpacing.sm),
            )
        }
    }
}

/** R-055: the list moved to [DetailRevisionsScreen] — this is only the entry point, still one tap away
 * (constitution III: nothing deleted quietly, but the exhaustive list is no longer inlined here). */
@Composable
private fun RevisionsLinkSection(detail: TransmissionDetailViewState, onOpenRevisions: () -> Unit) {
    if (detail.revisionHistory.isEmpty()) return
    Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)) {
        val count = detail.revisionHistory.size
        TextAction(
            text = if (count == 1) "1 earlier version" else "$count earlier versions",
            onClick = onOpenRevisions,
        )
    }
}

@Composable
private fun BottomActionBar(
    state: DetailViewState,
    onNotRight: () -> Unit,
    onConfirm: () -> Unit,
    onLeaveAmbiguous: () -> Unit,
    onIKnowWhoThisIs: () -> Unit,
    onRecordLabel: () -> Unit,
) {
    when (state.body) {
        is DetailBodyViewState.Confirmed ->
            ActionBar(
                secondaryLabel = "Not right?",
                onSecondary = onNotRight,
                primaryLabel = "Record a label",
                onPrimary = onRecordLabel,
            )

        is DetailBodyViewState.Inferred ->
            ActionBar(
                secondaryLabel = "Not right?",
                onSecondary = onNotRight,
                primaryLabel = "Confirm",
                onPrimary = onConfirm,
            )

        is DetailBodyViewState.Ambiguous ->
            SecondaryButton(
                text = "Leave ambiguous",
                onClick = onLeaveAmbiguous,
                modifier = Modifier.fillMaxWidth().padding(OrtSpacing.lg),
            )

        is DetailBodyViewState.Unknown ->
            PrimaryButton(
                text = "I know who this is",
                onClick = onIKnowWhoThisIs,
                modifier = Modifier.fillMaxWidth().padding(OrtSpacing.lg),
            )
    }
}

private const val POSITION_POLL_MILLIS = 150L

/**
 * R-056, `Controls.dc.html`: the labelled-sample form — closed sets as visible [RadioRow] lists,
 * free-text fields with a real Material [TextField] label (not only a content description).
 */
private class LabelSampleFormState {
    var callsign by mutableStateOf("")
    var certainty by mutableStateOf(LabelCertainty.CERTAIN)
    var outcome by mutableStateOf(LabelOutcome.SPEECH)
    var tactical by mutableStateOf("")
    var threadId by mutableStateOf("")
    var note by mutableStateOf("")

    fun clear() {
        callsign = ""
        tactical = ""
        threadId = ""
        note = ""
    }

    fun toSample(detail: TransmissionDetailViewState): LabelledSample = LabelledSample(
        sessionId = detail.sessionId,
        startSample = detail.startSample,
        endSample = detail.endSample,
        outcome = outcome,
        doubled = false,
        callsign = callsign,
        certainty = if (callsign.isBlank()) null else certainty,
        tactical = tactical,
        threadId = threadId,
        note = note,
    )
}

@Composable
private fun LabelSampleSection(
    detail: TransmissionDetailViewState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRecordLabel: suspend (LabelledSample) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val form = remember(detail.id) { LabelSampleFormState() }

    Column(modifier = Modifier.padding(OrtSpacing.lg)) {
        TextAction(
            text = if (expanded) "Hide labelled sample" else "Record labelled sample",
            onClick = onToggle,
            modifier = Modifier.semantics { contentDescription = "Record labelled sample" },
        )
        if (!expanded) return@Column

        SectionHeader(label = "Outcome", modifier = Modifier.padding(top = OrtSpacing.sm))
        LabelOutcome.entries.forEach { candidate ->
            RadioRow(
                label = candidate.wire,
                selected = form.outcome == candidate,
                onClick = { form.outcome = candidate },
            )
        }

        TextField(
            value = form.callsign,
            onValueChange = { form.callsign = it },
            label = "Labelled callsign",
            mono = true,
            contentDescriptionText = "Labelled callsign",
        )
        if (form.callsign.isNotBlank()) {
            SectionHeader(label = "Certainty", modifier = Modifier.padding(top = OrtSpacing.sm))
            LabelCertainty.entries.forEach { candidate ->
                RadioRow(
                    label = candidate.wire,
                    selected = form.certainty == candidate,
                    onClick = { form.certainty = candidate },
                )
            }
        }
        TextField(
            value = form.tactical,
            onValueChange = { form.tactical = it },
            label = "Tactical callsign",
            mono = true,
            contentDescriptionText = "Tactical callsign",
        )
        TextField(
            value = form.threadId,
            onValueChange = { form.threadId = it },
            label = "Thread id",
            contentDescriptionText = "Thread id",
        )
        TextField(
            value = form.note,
            onValueChange = { form.note = it },
            label = "Note",
            contentDescriptionText = "Note",
        )
        TextAction(
            text = "Save",
            onClick = {
                scope.launch {
                    onRecordLabel(form.toSample(detail))
                    form.clear()
                    onToggle()
                }
            },
            modifier = Modifier.padding(top = OrtSpacing.sm).semantics { contentDescription = "Save labelled sample" },
        )
    }
}
