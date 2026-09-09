package org.ort.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.ClickableText
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ort.app.ui.audio.PlaybackOutcome
import org.ort.app.ui.audio.PlaybackRate
import org.ort.app.ui.audio.TransmissionAudioPlayer
import org.ort.app.ui.audio.WaveformSummary
import org.ort.app.ui.components.ActionBar
import org.ort.app.ui.components.Badge
import org.ort.app.ui.components.BadgeKind
import org.ort.app.ui.components.MARKER_TITLE_SIZE
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
import org.ort.app.ui.data.LogItemsMapper
import org.ort.app.ui.data.PassFailureViewState
import org.ort.app.ui.data.RankedCandidateViewState
import org.ort.app.ui.data.RejectedViewState
import org.ort.app.ui.data.TransmissionDetailViewState
import org.ort.app.ui.data.TriedStepViewState
import org.ort.app.ui.failures.FailureActionBarScaffold
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
    onRetryPass: () -> Unit = {},
    onKeepPartial: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var labelExpanded by remember(state.detail.id) { mutableStateOf(false) }
    // R-422: a `Column(weight(1f)) { scroll } + Column(bar)` sibling pair — this screen's own shape
    // before this fix — is the same "first-frame clipping" class `FailureActionBarScaffold.kt`'s
    // own doc comment already diagnosed at R-292/R-151: correct once settled, but at a large font
    // scale the weighted content slot can render blank (or behind the bar) for the first frame(s)
    // Robolectric cannot see at all. [FailureActionBarScaffold] is the real, already-tested fix — a
    // genuine `Scaffold`-style layout that measures the bar first and constrains the scrollable
    // content slot's own height to `viewport − bar`, so content physically cannot occupy the bar's
    // region at any scroll offset, first frame included — reused here rather than a second,
    // look-alike scaffold (constitution VII).
    FailureActionBarScaffold(
        modifier = modifier,
        actionBar = {
            BottomActionBar(
                state = state,
                onNotRight = onNotRight,
                onConfirm = onConfirm,
                onLeaveAmbiguous = onLeaveAmbiguous,
                onIKnowWhoThisIs = onIKnowWhoThisIs,
                onRecordLabel = { labelExpanded = true },
                onRetryPass = onRetryPass,
                onKeepPartial = onKeepPartial,
            )
        },
        content = {
            val passFailure = state.passFailure
            val rejected = state.rejected
            when {
                rejected != null -> RejectedHeaderSection(state.detail, rejected)
                passFailure != null -> FailedPassHeaderSection(state.detail, passFailure)
                else -> HeaderSection(state, onOpenTransmission)
            }
            PlaybackSection(state.detail, player)
            when {
                // R-242: a rejected segment's own text (if any survived rejection at all — most
                // hallucination rejections have one, a squelch-tail-with-no-speech rejection may
                // not) never gets [TranscriptSection]'s callsign highlight or confidence caption:
                // REJECTED means never attributed (constitution: `CONFIRMED` means heard *this*
                // transmission; a rejected one was never even a candidate for that).
                rejected != null -> RejectedTranscriptSection(state.detail)
                passFailure != null -> FailedPassPartialSection(state.detail)
                else -> TranscriptSection(state.detail, state.transcriptConfidence, state.transcriptCharSpan)
            }
            // R-564 (register): `Fail-Pass.dc.html`'s own visual order is header → meta → waveform
            // card → Live partial → divider → "What went wrong · N attempts" → closing health line
            // → action bar. Before this fix, [FailedPassHeaderSection] rendered the whole "What went
            // wrong" account (attempts, retry-limit line, guidance, session-health line) as part of
            // the header, ahead of [PlaybackSection]/[FailedPassPartialSection] — the exact ordering
            // gap round 4's device review found. [FailedPassWhatWentWrongSection] is that same
            // account, unchanged in content, placed here instead — after both.
            if (passFailure != null) {
                FailedPassWhatWentWrongSection(passFailure)
            }
            // R-196 (halt): a failed pass carries an UNKNOWN attribution (no pass ever finished to
            // resolve one), so `state.body` is genuinely `DetailBodyViewState.Unknown` — but the
            // over's problem is that a *pass* errored, not that the resolver came up empty. These
            // three sections all speak to the attribution-state branch (`AttributionState.UNKNOWN`'s
            // "what was tried"/"why this callsign") and must not render over
            // [FailedPassHeaderSection]'s own "what went wrong" account of the same over, nor over
            // [RejectedHeaderSection]'s (R-242): a rejected segment was never resolved either, and
            // never will be — the resolver's own "not attempted on a rejected segment" is a
            // different fact from "attempted and came up empty".
            if (passFailure == null && rejected == null) {
                AmbiguousChooserSection(state.body, onChooseCandidate, onNeither)
                UnknownTriedSection(state.body)
                WhySection(state, onOpenWhy)
            }
            RevisionsLinkSection(state.detail, onOpenRevisions)
            LabelSampleSection(
                detail = state.detail,
                expanded = labelExpanded,
                onToggle = { labelExpanded = !labelExpanded },
                onRecordLabel = onRecordLabel,
            )
        },
    )
}

@Composable
private fun HeaderSection(state: DetailViewState, onOpenTransmission: (String) -> Unit) {
    val detail = state.detail
    val ambiguous = state.body as? DetailBodyViewState.Ambiguous
    val alternate = ambiguous?.alternateCallsign
    // R-186: `Attribution.ambiguous()` carries no `stationId` at all (constitution I — nothing is
    // asserted for AMBIGUOUS), so `detail.attribution.stationId` is always null here; the primary
    // candidate `Detail-Ambiguous.dc.html`'s own header names ("KE7QRS or KE7QRF") comes from the
    // resolver's own top-ranked candidate instead — the same list `AmbiguousChooserSection` below
    // already renders, never a second, drifting source for the same fact.
    val primaryCallsign = ambiguous?.candidates?.firstOrNull()?.callsign ?: detail.attribution.stationId
    Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TitleAttributionRow(attribution = detail.attribution, callsign = primaryCallsign)
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
        val inferred = state.body as? DetailBodyViewState.Inferred
        if (inferred?.sourceTransmissionId != null) {
            InferredExplanationText(inferred, onOpenTransmission)
        } else {
            Text(
                text = state.body.explanation,
                style = OrtType.subtitle,
                color = OrtColors.textMuted,
                modifier = Modifier.padding(top = OrtSpacing.sm),
            )
        }
        MetaRow(detail)
    }
}

/** The tag [buildInferredExplanationText]'s own inline link is annotated with. */
internal const val SOURCE_OVER_LINK_TAG = "source-over"

/**
 * R-423 (design): `Detail.dc.html`'s own inline link — the source over's real timestamp is itself
 * the tappable text ("Matched by voice to `02:14:07`, where the callsign was heard clearly."),
 * styled the board's own accent green — never a separate "Open the source over" line beneath the
 * sentence, which is not what the board draws. `internal`, not `private` — the same
 * direct-testability precedent `highlightedTranscript` (R-182) already set in this file, so the
 * real span/tag/colour can be asserted without Compose.
 */
internal fun buildInferredExplanationText(
    sourceId: String,
    sourceOverTimeLabel: String?,
    linkColor: Color,
): AnnotatedString {
    val timeLabel = sourceOverTimeLabel ?: "the source over"
    return buildAnnotatedString {
        append("Not heard in this over. Matched by voice to ")
        pushStringAnnotation(tag = SOURCE_OVER_LINK_TAG, annotation = sourceId)
        withStyle(SpanStyle(color = linkColor)) { append(timeLabel) }
        pop()
        append(", where the callsign was heard clearly.")
    }
}

/** The real source id at [offset] in [text], if [offset] falls inside the inline link — `internal`
 * for the same reason [buildInferredExplanationText] is. */
internal fun sourceIdAtOffset(text: AnnotatedString, offset: Int): String? =
    text.getStringAnnotations(SOURCE_OVER_LINK_TAG, offset, offset).firstOrNull()?.item

@Composable
private fun InferredExplanationText(inferred: DetailBodyViewState.Inferred, onOpenTransmission: (String) -> Unit) {
    val sourceId = inferred.sourceTransmissionId ?: return
    val annotated = buildInferredExplanationText(sourceId, inferred.sourceOverTimeLabel, OrtColors.accentGreen)
    ClickableText(
        text = annotated,
        style = OrtType.subtitle.copy(color = OrtColors.textMuted),
        modifier = Modifier.padding(top = OrtSpacing.sm),
        onClick = { offset -> sourceIdAtOffset(annotated, offset)?.let(onOpenTransmission) },
    )
}

/** Shared by [HeaderSection] and [FailedPassHeaderSection] — time/frequency/duration/signal, mono, faint. */
@Composable
private fun MetaRow(detail: TransmissionDetailViewState) {
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

/**
 * R-153, F18 `Fail-Pass.dc.html`, FR-RUN-9: the failed-pass header — no callsign (none was ever
 * resolved; this over's own [DetailBodyViewState] would otherwise render a misleading "no callsign
 * heard, voice matched no one" UNKNOWN sentence, which is not what happened here — the pass never
 * finished, it errored). The marker adapts design-guide.md §6.13's "Resolving" ring (a 9px ring,
 * one quadrant open) — the closest real, already-specified shape for "the attribution never
 * finished" — in amber rather than `text/signal`, matching `Fail-Pass.dc.html`'s own halted-amber
 * reading; no shared composable for either ring exists in [org.ort.app.ui.components.AttributionMarker]
 * yet (only [TitleAttributionRow]'s four closed attribution states and [ScoreChip][org.ort.app.ui.components.ScoreChip]
 * do), so this is a minimal, local, documented one — not a look-alike of a component that exists.
 */
@Composable
private fun FailedPassHeaderSection(detail: TransmissionDetailViewState, passFailure: PassFailureViewState) {
    val attempts = passFailure.attempts
    // R-426: "errored N times" (the prose sentence) and "N attempts" (the section header) are two
    // different real phrasings of the same real number — `Fail-Pass.dc.html` itself uses both,
    // "errored 3 times on this over" in the sentence and "What went wrong · 3 attempts" in the
    // header — neither is a typo for the other.
    val attemptsTimesWord = if (attempts == 1) "1 time" else "$attempts times"
    Column(
        modifier = Modifier
            .padding(horizontal = OrtSpacing.lg)
            .testTag("pass-failure-section"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(modifier = Modifier.size(MARKER_TITLE_SIZE)) {
                // design-guide.md §6.13's "Resolving" ring — a 9px ring, one quadrant open — drawn
                // as a 270° arc (open the last 90°) rather than the artboard's CSS
                // transparent-two-sides trick, since Compose's `Modifier.border` has no per-side
                // colour; the visual result (an open quadrant) is the same real shape.
                drawArc(
                    color = OrtColors.accentAmber,
                    startAngle = -45f,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = Stroke(width = size.minDimension * 0.16f),
                )
            }
            Spacer(modifier = Modifier.width(OrtSpacing.sm))
            Text(
                text = "not transcribed",
                style = OrtType.callsignTitle.copy(fontStyle = FontStyle.Italic),
                color = OrtColors.textBody,
            )
        }
        Text(
            text = "${passFailure.passLabel} errored $attemptsTimesWord on this over and stopped trying. " +
                "The audio is here; the queue moved on without it.",
            style = OrtType.subtitle,
            color = OrtColors.textMuted,
            modifier = Modifier.padding(top = OrtSpacing.sm),
        )
        MetaRow(detail)
    }
}

/**
 * R-153/R-195/R-426/R-470, `Fail-Pass.dc.html`: the board's own "What went wrong · N attempts"
 * block plus its closing "Counted in tonight's health" paragraph — split out of
 * [FailedPassHeaderSection] by R-564 (register), whose device review found this whole account
 * rendering *before* [PlaybackSection]/[FailedPassPartialSection] where the board places it
 * *after* both. Content is unchanged from the pre-split version; only its position in
 * [TransmissionDetailContent]'s composition moved.
 */
@Composable
private fun FailedPassWhatWentWrongSection(passFailure: PassFailureViewState) {
    val attempts = passFailure.attempts
    val attemptsCountWord = if (attempts == 1) "1 attempt" else "$attempts attempts"
    Column(
        modifier = Modifier
            .padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)
            .testTag("pass-failure-what-went-wrong"),
    ) {
        SectionHeader(label = "What went wrong · $attemptsCountWord")
        // R-426 (round 12): the real per-attempt history from `:data` schema v6's
        // `WorkAttemptEntity`, oldest first, one line per attempt — `Fail-Pass.dc.html`'s own
        // "HH:MM:SS · reason" shape. A `FAILED` item whose attempts predate schema v6 has no
        // `work_attempt` rows at all (`attemptLog` is honestly empty), so it falls back to the one
        // real fact still available — the aggregate `lastError` — never a fabricated per-attempt
        // list for a record that has none.
        if (passFailure.attemptLog.isNotEmpty()) {
            passFailure.attemptLog.forEachIndexed { index, attempt ->
                Text(
                    text = "${attempt.timeLabel} · ${attempt.reasonLabel}",
                    style = OrtType.cardBody,
                    color = OrtColors.textFaint,
                    modifier = Modifier.padding(top = OrtSpacing.xs).testTag("pass-failure-attempt-$index"),
                )
            }
        } else {
            Text(
                text = passFailure.lastError.replaceFirstChar { it.titlecase() },
                style = OrtType.cardBody,
                color = OrtColors.textFaint,
                modifier = Modifier.padding(top = OrtSpacing.xs).testTag("pass-failure-last-error"),
            )
        }
        // R-426: the board's own closing "retry limit reached" line — real, not fabricated: every
        // terminally-FAILED `WorkQueueItemEntity` reached this state *because*
        // `WorkQueue.failPass`'s own `attempts >= maxAttempts` check tripped (`WorkQueue.kt`,
        // `:data`) — a structural fact about how the pipeline decides "stop retrying", not a
        // per-instance guess, and [attempts] is that exact real count.
        Text(
            text = "Retry limit reached after $attemptsCountWord. Marked failed; the queue continued " +
                "without it.",
            style = OrtType.cardBody,
            color = OrtColors.textFaint,
            modifier = Modifier.padding(top = OrtSpacing.xs).testTag("pass-failure-retry-limit"),
        )
        // R-195: `Fail-Pass.dc.html`'s own retry-guidance sentence — real (a manual retry is exactly
        // what `Retry now`/`CorrectionPolling.retryFailedPass` does: requeues this one item alone),
        // not the board's per-attempt list this schema cannot back (see this function's own note
        // above the last-error line).
        Text(
            text = "Retrying by hand runs it alone. If it fails again the error is recorded again, " +
                "and the over stays exactly as it is.",
            style = OrtType.cardBody,
            color = OrtColors.textFaint,
            modifier = Modifier.padding(top = OrtSpacing.xs),
        )
        // R-470 (design): `Fail-Pass.dc.html`'s own closing paragraph — the real count of `FAILED`
        // transmissions in this over's own session ([passFailure.sessionFailedCount], the same fact
        // `Capture-Status.dc.html`'s own "N failed" already counts), worded exactly.
        Text(
            text = "Counted in tonight's health: ${passFailure.sessionFailedCount} failed. A failed " +
                "pass never blocks the queue and never loses the audio — it just waits for you.",
            style = OrtType.cardBody,
            color = OrtColors.textFaint,
            modifier = Modifier.padding(top = OrtSpacing.md).testTag("pass-failure-session-health"),
        )
    }
}

/**
 * R-195, `Fail-Pass.dc.html`: "Live partial, Pass A" — the failed-pass state's own transcript
 * section, distinct from [TranscriptSection] (no confidence caption, no callsign highlight — this
 * text was never attributed and never will be, unlike a normal transcript). [detail.transcriptText]
 * already carries the real Pass A partial when one was recorded, or the honest
 * `"(transcription failed)"` fallback when it was not — see
 * [org.ort.app.ui.data.ReaderTransmissionViewStateMapper.transcriptLabel]; this section only adds
 * the board's own label and caption around whichever real text that already is.
 */
@Composable
private fun FailedPassPartialSection(detail: TransmissionDetailViewState) {
    Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)) {
        SectionHeader(label = "Live partial, Pass A")
        Text(
            text = detail.transcriptText,
            style = OrtType.bodyProse.copy(fontStyle = FontStyle.Italic),
            color = OrtColors.textDim,
            modifier = Modifier.padding(top = OrtSpacing.xs),
        )
        Text(
            text = "Shown in the log in place of a final transcript. Not attributed — partials never are.",
            style = OrtType.subLine,
            color = OrtColors.textFaint,
            modifier = Modifier.padding(top = OrtSpacing.xs),
        )
    }
}

/**
 * R-242, F04 `Fail-Hallucination.dc.html`, FR-ASR-5: the rejected-segment header — no callsign
 * (a rejected segment was never resolved, never a candidate for one; see this file's own note at
 * the call site above). The uppercase "rejected · reason" wording matches
 * [org.ort.app.ui.components.RejectedRow]'s own row label exactly, so the log row and its detail
 * screen read as one fact rather than two independent tellings of it. Deliberately **not** the
 * board's own "4 of 6 controls fired" checklist: `:data` records only [rejected.reason] itself
 * (plus, on some rows, [org.ort.data.entity.TranscriptEntity.noSpeechProb] — not surfaced here on
 * its own, since one real figure out of six named controls would misrepresent a panel that mostly
 * does not exist) — see [RejectedViewState]'s own doc comment for the full accounting. Also
 * deliberately **not** a "restore — it was speech" action: that would need a `:pipeline` re-run of
 * Pass B with its phrase filter disabled, a write path this package has no access to build.
 */
@Composable
private fun RejectedHeaderSection(detail: TransmissionDetailViewState, rejected: RejectedViewState) {
    // R-331: `Fail-Hallucination.dc.html`'s own title is "REJECTED" alone — never the raw
    // `"$rule: $detail"` record (`org.ort.pipeline.passb.DataPassBResultSink`'s own write shape).
    // The human sentence beneath it reuses `LogItemsMapper.whyFor` — the identical mapping WP5's own
    // `Log-Rejected.dc.html` why-line already uses for the same raw record, rather than a second,
    // drifting translation of the same six rule tokens. `whyFor` returns `null` for a `null` reason
    // or one it does not recognise (an unrecognised rule token never gets a guessed category) —
    // both cases fall back to honest, reason-free prose, never the raw enum in any form.
    val why = LogItemsMapper.whyFor(rejected.reason)
    val explanation = when {
        why != null -> "The segment was kept, marked, and never attributed. $why."
        rejected.reason == null -> "The segment was kept, marked, and never attributed. No reason was recorded."
        else -> "The segment was kept, marked, and never attributed."
    }
    Column(
        modifier = Modifier
            .padding(horizontal = OrtSpacing.lg)
            .testTag("rejected-section"),
    ) {
        Text(
            text = "rejected".uppercase(),
            style = OrtType.callsignTitle.copy(fontStyle = FontStyle.Italic),
            color = OrtColors.textBody,
        )
        Text(
            text = explanation,
            style = OrtType.subtitle,
            color = OrtColors.textMuted,
            modifier = Modifier.padding(top = OrtSpacing.sm),
        )
        MetaRow(detail)
    }
}

/**
 * R-242: "What the model said" — the real transcript text a rejected segment still carries, if
 * any (most hallucination rejections have one; a pure squelch-tail rejection may not). Never
 * highlighted, never captioned with a confidence figure — a rejected transcript was never
 * attributed and never scored for confidence the way a kept one is.
 *
 * [detail.transcriptText] is never `null` — for a rejected transmission with no real transcript
 * row, [org.ort.app.ui.data.ReaderTransmissionViewStateMapper.transcriptLabel] already falls back
 * to the generic `"(rejected: reason)"`/`"(rejected — no reason recorded)"` label (the same one
 * [RejectedHeaderSection]'s own explanation line states in full sentence form). Showing that
 * generic label a second time, framed as "what the model said", would misrepresent a placeholder
 * as real model output — so this section renders an honest "no transcript" line instead whenever
 * the text is that fallback, detected by its own literal `"(rejected"` prefix (the one string
 * [transcriptLabel] is guaranteed to only ever use for exactly this case).
 */
@Composable
private fun RejectedTranscriptSection(detail: TransmissionDetailViewState) {
    Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)) {
        SectionHeader(label = "What the model said")
        Text(
            text = if (detail.transcriptText.startsWith("(rejected")) {
                "No transcript text was recorded for this rejected segment."
            } else {
                detail.transcriptText
            },
            style = OrtType.bodyProse.copy(fontStyle = FontStyle.Italic),
            color = OrtColors.textDim,
            modifier = Modifier.padding(top = OrtSpacing.xs),
        )
    }
}

/**
 * R-054/R-181, `Detail-Playback.dc.html`: idle / playing (position, scrub, speed) / no-audio /
 * unavailable, via [WaveformCard]. [WaveformBar] bars are now [player]'s own real
 * [TransmissionAudioPlayer.waveformSummary] — [WaveformSummaryComputer]'s own doc comment names
 * exactly what "real" means (peak amplitude per bucket, normalised to the segment's own peak); a
 * `null` result (no retained audio, or a decode failure) renders an empty bar list, never a
 * fabricated flat shape standing in for one (constitution I). Scrubbing (WP2's `onScrub`) seeks the
 * real player directly — `positionFraction` is then read back from it on the next poll tick, the
 * same as any other player-driven position change; the cursor position is a continuous `0f..1f`
 * fraction of the bar row, which lands on whichever bucket that fraction's x-offset falls under —
 * no separate "bucket index" mapping is needed beyond the fraction [WaveformCard] already computes.
 * The spoken-word outline (no word-timing data exists anywhere) stays a named gap in this package's
 * CHANGELOG rather than faked.
 */
@Composable
private fun PlaybackSection(detail: TransmissionDetailViewState, player: TransmissionAudioPlayer) {
    val scope = rememberCoroutineScope()
    var unavailableReason by remember(detail.id) { mutableStateOf<String?>(null) }
    var playing by remember(detail.id) { mutableStateOf(false) }
    var positionFraction by remember(detail.id) { mutableStateOf(0f) }
    var rate by remember(detail.id) { mutableStateOf(PlaybackRate.NORMAL) }
    var waveform by remember(detail.id) { mutableStateOf<WaveformSummary?>(null) }

    LaunchedEffect(detail.id, playing) {
        while (playing) {
            positionFraction = player.positionFraction()
            playing = player.isPlaying()
            delay(POSITION_POLL_MILLIS)
        }
    }

    // R-181: a real decode+bucket pass, off the composition's own dispatch by way of
    // `TransmissionAudioPlayer.waveformSummary`'s own `Dispatchers.IO` — only attempted when audio
    // is actually retained, and cached by the player per transmission id so revisiting an over
    // never re-decodes it.
    LaunchedEffect(detail.id, detail.hasAudio) {
        waveform = if (detail.hasAudio) player.waveformSummary(detail.id) else null
    }

    val durationSeconds = detail.durationLabel.removeSuffix("s").toDoubleOrNull() ?: 0.0
    val bars = waveform?.bars.orEmpty()
    val waveformState = when {
        unavailableReason != null -> WaveformViewState.Unavailable(unavailableReason.orEmpty())
        !detail.hasAudio -> WaveformViewState.NoAudio
        playing -> WaveformViewState.Playing(
            bars = bars,
            cursorFraction = positionFraction,
            positionLabel = "%.1f".format(positionFraction * durationSeconds),
            durationLabel = detail.durationLabel,
            speedLabel = rate.label,
        )

        else -> WaveformViewState.Idle(bars = bars, durationLabel = detail.durationLabel)
    }

    Column(modifier = Modifier.padding(OrtSpacing.lg)) {
        WaveformCard(
            state = waveformState,
            onPlayPause = if (detail.hasAudio) {
                {
                    scope.launch {
                        togglePlayback(detail, player, playing, { playing = it }, { unavailableReason = it })
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
        if (!detail.hasAudio) {
            NoAudioNotice(detail)
        }
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
 * The waveform's own play/pause tap, pulled out of [PlaybackSection] to keep that composable under
 * detekt's `LongMethod` threshold — a plain data move, not a behaviour change.
 */
private suspend fun togglePlayback(
    detail: TransmissionDetailViewState,
    player: TransmissionAudioPlayer,
    playing: Boolean,
    onPlaying: (Boolean) -> Unit,
    onUnavailableReason: (String?) -> Unit,
) {
    if (playing) {
        player.pause()
        onPlaying(false)
        return
    }
    when (val outcome = player.play(detail.id)) {
        PlaybackOutcome.Played -> {
            onUnavailableReason(null)
            onPlaying(true)
        }

        is PlaybackOutcome.Unavailable -> {
            onUnavailableReason(outcome.reason)
            onPlaying(false)
        }
    }
}

/**
 * R-193, `Detail-Playback.dc.html`'s "No audio retained" card: distinguishes a real record that
 * was processed before its audio was removed ("deleted by retention... transcript, attribution
 * and lattice were kept") from one that never had audio at all. `:data` keeps no stored flag for
 * *which* — this reads the honest signal that does exist: `attribution.state != UNKNOWN` means a
 * real attribution was resolved, which only happens after real audio was processed, so its later
 * absence is retention's doing; `UNKNOWN` with nothing else recorded is consistent with audio
 * never having existed to process. A real inference from real data, not a stored fact — disclosed
 * as exactly that, never a fabricated date (the board's own "on 8 Aug" needs a retention-event
 * timestamp this package cannot honestly cite).
 */
@Composable
private fun NoAudioNotice(detail: TransmissionDetailViewState) {
    val everProcessed = detail.attribution.state != AttributionState.UNKNOWN || !detail.inspection.isEmpty
    Text(
        text = if (everProcessed) {
            "Audio deleted by retention. Transcript, attribution and lattice were kept."
        } else {
            "Audio was never retained for this over."
        },
        style = OrtType.cardBody,
        color = OrtColors.textFaint,
        modifier = Modifier.padding(top = OrtSpacing.xs),
    )
}

/**
 * The transcript, with the callsign highlighted when it appears verbatim in the text (a literal,
 * honest substring check — never a phonetic-word guess: no data anywhere maps a transcript's
 * spoken words back to the callsign that produced them, so this never claims a highlight it cannot
 * back). CONFIRMED highlights `highlightGreen`; every other carrying state highlights `highlightAmber`.
 *
 * R-188: [transcriptConfidence], when the current transcript row recorded one, renders as a plain
 * caption below the text ("Transcript confidence 0.61") — the real number alone, never the board's
 * own "· weak signal, cut off" qualitative clause, which this package has no honest way to derive
 * from a bare confidence value (constitution I).
 *
 * Register R-182 (schema v5): [transcriptCharSpan], when the winning candidate's lattice was
 * text-anchored, highlights that exact `[start, end)` range — precise even for a phonetically
 * spelled callsign ("kilo seven lima whiskey hotel"), which the literal substring fallback below
 * could never match. Falls back to the plain [String.indexOf] check only when no span exists (an
 * acoustic lattice, or a record from before schema v5) — see [highlightedTranscript]'s own doc.
 */
@Composable
private fun TranscriptSection(
    detail: TransmissionDetailViewState,
    transcriptConfidence: Double? = null,
    transcriptCharSpan: IntRange? = null,
) {
    val highlight = if (detail.attribution.state == AttributionState.CONFIRMED) {
        OrtColors.highlightGreen
    } else {
        OrtColors.highlightAmber
    }
    val text = highlightedTranscript(detail.transcriptText, detail.attribution.stationId, transcriptCharSpan, highlight)
    Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)) {
        Text(text = text, style = OrtType.bodyProse, color = OrtColors.textSecondary)
        transcriptConfidence?.let {
            Text(
                text = "Transcript confidence %.2f".format(it),
                style = OrtType.subLine,
                color = OrtColors.textFaint,
                modifier = Modifier.padding(top = OrtSpacing.xs),
            )
        }
    }
}

/**
 * Register R-182: the real span, when [charSpan] is non-null and lands inside [transcriptText]
 * (a defensive check, not an expected failure — a span computed against a different transcript
 * version than the one currently shown could in principle disagree). Otherwise the honest, weaker
 * fallback this package always used: a literal, case-insensitive substring match of [stationId] —
 * never a phonetic-word guess, since no data anywhere maps a transcript's spoken words back to the
 * callsign that produced them beyond what the span itself already gives when it exists.
 */
internal fun highlightedTranscript(
    transcriptText: String,
    stationId: String?,
    charSpan: IntRange?,
    highlight: Color,
): AnnotatedString {
    if (charSpan != null && charSpan.first in transcriptText.indices && charSpan.last < transcriptText.length) {
        return buildAnnotatedString {
            append(transcriptText.substring(0, charSpan.first))
            withStyle(SpanStyle(background = highlight)) {
                append(transcriptText.substring(charSpan.first, charSpan.last + 1))
            }
            append(transcriptText.substring(charSpan.last + 1))
        }
    }
    if (stationId != null) {
        val index = transcriptText.indexOf(stationId, ignoreCase = true)
        if (index >= 0) {
            return buildAnnotatedString {
                append(transcriptText.substring(0, index))
                withStyle(SpanStyle(background = highlight)) {
                    append(transcriptText.substring(index, index + stationId.length))
                }
                append(transcriptText.substring(index + stationId.length))
            }
        }
    }
    return buildAnnotatedString { append(transcriptText) }
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
            // R-187: one evidence-bearing, clickable row — not a display row plus a separate
            // "Choose X" link (a past correction-sheet regression in this same package: a
            // non-interactive display row beside an interactive one with no distinguishing marker
            // sends a real tap to the wrong node; see `MainTier`/`SearchTier`'s own history).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clickable(
                        role = Role.Button,
                        onClick = { onChooseCandidate(candidate.callsign) },
                    )
                    .padding(vertical = OrtSpacing.sm)
                    .semantics(mergeDescendants = true) {
                        contentDescription =
                            "Choose ${candidate.callsign}, ${candidate.evidence}, score ${candidate.scoreLabel}"
                    },
            ) {
                Text(text = candidate.callsign, style = OrtType.callsignRow, color = OrtColors.textHigh)
                Spacer(modifier = Modifier.width(OrtSpacing.md))
                Text(
                    text = candidate.evidence,
                    style = OrtType.cardBody,
                    color = OrtColors.textDim,
                    modifier = Modifier.weight(1f),
                )
                Text(text = candidate.scoreLabel, style = OrtType.signal, color = OrtColors.textDim)
            }
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

/**
 * R-051/R-180, FR-UI-8: the inline "why this callsign" preview + a link to the exhaustive
 * [DetailWhyScreen]. Real components throughout, not raw text lines: each candidate is a marker +
 * mono-callsign + score row (the same shape `DetailWhyScreen`'s own `CandidatesSection` uses, so
 * the preview and the exhaustive screen read as one system), and every [PriorBar] name is guide
 * §9 prose from [DetailViewStateMapper]'s own translation table, not the raw stored key.
 *
 * **Honest gap, unchanged from this package's earlier revision:** `Detail-Why.dc.html`'s per-slot
 * phonetic-lattice boxes (`K .96`, `7 .99`, ...) cannot be rendered — `:data`'s
 * [org.ort.data.entity.PhoneticLatticeEntity.unitsBlob] is an opaque blob with no defined per-unit
 * shape yet (that entity's own doc comment), so [WP2's LatticeSlot][org.ort.app.ui.components.LatticeSlot]
 * has no real per-letter score to draw from. [why.latticeSummary] (source + model) is shown instead,
 * with an explicit line saying per-slot detail is not recorded — never a fabricated slot.
 */
@Composable
private fun WhySection(state: DetailViewState, onOpenWhy: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)) {
        SectionHeader(
            label = "Why this callsign",
            trailingActionLabel = "Full lattice",
            onTrailingAction = onOpenWhy,
            modifier = Modifier.testTag("open-full-lattice"),
        )
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
                modifier = Modifier.padding(top = OrtSpacing.xs),
            )
            Text(
                text = "Per-slot detail (each unit's score and kept alternate) is not recorded yet.",
                style = OrtType.subLine,
                color = OrtColors.textFaint,
                modifier = Modifier.padding(top = 2.dp, bottom = OrtSpacing.sm),
            )
        }
        why.candidates.forEach { candidate -> WhyCandidateRow(candidate) }
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

/** One candidate row: a marker dot (filled for the chosen one, hollow otherwise) + mono callsign +
 * score, merged into one semantics node — the same shape `Detail-Why.dc.html`'s own rows use. */
@Composable
private fun WhyCandidateRow(candidate: RankedCandidateViewState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${candidate.callsign}, ${candidate.scoreLabel}" +
                    if (candidate.chosen) ", chosen" else ""
            },
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .then(
                    if (candidate.chosen) {
                        Modifier.background(OrtColors.accentGreen, CircleShape)
                    } else {
                        Modifier.border(1.5.dp, OrtColors.textFaint, CircleShape)
                    },
                ),
        )
        Spacer(modifier = Modifier.width(OrtSpacing.sm))
        Text(
            text = candidate.callsign,
            style = OrtType.callsignRow,
            color = if (candidate.chosen) OrtColors.textHigh else OrtColors.textBody,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = candidate.scoreLabel + if (candidate.chosen) " · chosen" else "",
            style = OrtType.cardBody,
            color = if (candidate.chosen) OrtColors.scoreGood else OrtColors.textDim,
        )
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

/**
 * R-153: a failed pass gets its own bottom bar — `Fail-Pass.dc.html`'s "Keep the partial" (there is
 * nothing to write; the over already reads honestly as it is) / "Retry now" (wired to
 * [org.ort.app.ui.data.CorrectionPolling.retryFailedPass] by the caller) — checked before the
 * normal attribution-state `when`, since [state]'s [DetailBodyViewState] still reflects whatever
 * (likely UNKNOWN) attribution the transmission carries, not the fact that a pass errored.
 */
@Composable
@Suppress("LongParameterList") // one callback per independently-testable interaction, precedent above.
private fun BottomActionBar(
    state: DetailViewState,
    onNotRight: () -> Unit,
    onConfirm: () -> Unit,
    onLeaveAmbiguous: () -> Unit,
    onIKnowWhoThisIs: () -> Unit,
    onRecordLabel: () -> Unit,
    onRetryPass: () -> Unit,
    onKeepPartial: () -> Unit,
) {
    // R-242: no real action exists for a rejected segment — see [RejectedHeaderSection]'s own
    // doc comment for why "restore — it was speech" is not wired here. An empty bottom bar is the
    // honest state, not a disabled-looking button standing in for a write path this package
    // cannot back.
    if (state.rejected != null) {
        return
    }
    if (state.passFailure != null) {
        ActionBar(
            secondaryLabel = "Keep the partial",
            onSecondary = onKeepPartial,
            primaryLabel = "Retry now",
            onPrimary = onRetryPass,
        )
        return
    }
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
