package org.ort.app.ui.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ort.app.ui.components.LogRowBadge
import org.ort.app.ui.components.LogRowPartial
import org.ort.core.Attribution
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.entity.CaptureGapCause
import org.ort.data.entity.CaptureGapEntity
import org.ort.data.entity.TranscriptPass

/**
 * R-040/R-041/R-042/R-043/R-045 (ui-conformance WP5): [LogItemsMapper]'s pure rules — partial
 * detection (FR-UI-1, P5), badge precedence (guide §6.14), the AMBIGUOUS "or QRF" alternate
 * (guide §6.1), gap labelling (FR-UI-12/FR-RUN-12), QSO grouping, filtering (FR-UI-3) and the
 * empty-state wording (R-045) — all tested without Robolectric or a database.
 */
class LogViewDataTest {

    @Suppress("LongParameterList") // fixture builder — every param has a defaulted, honest value.
    private fun detail(
        id: String = "TX1",
        startedAtUtcMillis: Long = 0L,
        frequencyHz: Long? = 146_960_000L,
        attribution: Attribution = Attribution.unknown(),
        currentTranscriptText: String? = "roger that",
        supersededTranscriptTexts: List<String> = emptyList(),
        processingState: TransmissionState = TransmissionState.COMPLETE,
        currentTranscriptPass: TranscriptPass? = TranscriptPass.B,
        corrected: Boolean = false,
        threadId: String? = null,
        rejectionReason: String? = null,
        inspection: InspectionViewState = InspectionViewState.EMPTY,
    ) = TransmissionDetail(
        id = id,
        startedAtUtcMillis = startedAtUtcMillis,
        frequencyHz = frequencyHz,
        durationMs = 4_200L,
        signalStrength = 7.0,
        attribution = attribution,
        currentTranscriptText = currentTranscriptText,
        supersededTranscriptTexts = supersededTranscriptTexts,
        hasAudio = true,
        threadId = threadId,
        processingState = processingState,
        rejectionReason = rejectionReason,
        currentTranscriptPass = currentTranscriptPass,
        corrected = corrected,
        inspection = inspection,
    )

    private fun candidate(callsign: String, rank: Int, selected: Boolean = false) = CandidateInspectionViewState(
        callsign = callsign,
        rank = rank,
        score = 0.5,
        grammarValid = true,
        databaseHit = true,
        selected = selected,
        priorContributions = emptyList(),
    )

    private fun gap(startedAt: Long = 0L, endedAt: Long?, cause: CaptureGapCause) = CaptureGapEntity(
        id = "G1",
        sessionId = "S1",
        startedAt = startedAt,
        endedAt = endedAt,
        cause = cause,
        recoveredAutomatically = true,
    )

    // -- R-041 partials (FR-UI-1, P5) --------------------------------------------------------

    @Test
    fun `R_041 a Pass A transcript still capturing renders as the hearing partial, no marker`() {
        val d = detail(processingState = TransmissionState.PROCESSING, currentTranscriptPass = TranscriptPass.A)

        assertEquals(LogRowPartial.HEARING, LogItemsMapper.partialFor(d))
    }

    @Test
    fun `R_041 a Pass B text still processing renders as the resolving partial, no marker`() {
        val d = detail(processingState = TransmissionState.PROCESSING, currentTranscriptPass = TranscriptPass.B)

        assertEquals(LogRowPartial.RESOLVING, LogItemsMapper.partialFor(d))
    }

    @Test
    fun `R_041 a completed transmission is never a partial regardless of transcript pass`() {
        val d = detail(processingState = TransmissionState.COMPLETE, currentTranscriptPass = TranscriptPass.B)

        assertNull(LogItemsMapper.partialFor(d))
    }

    @Test
    fun `R_041 no transcript at all is not a partial -- there is no text to stream`() {
        val d = detail(
            processingState = TransmissionState.CAPTURED,
            currentTranscriptText = null,
            currentTranscriptPass = null,
        )

        assertNull(LogItemsMapper.partialFor(d))
    }

    @Test
    fun `R_041 a partial row carries no badge even if it would otherwise be REVISED or NEW`() {
        val d = detail(
            processingState = TransmissionState.PROCESSING,
            currentTranscriptPass = TranscriptPass.A,
            supersededTranscriptTexts = listOf("earlier"),
        )

        assertNull(LogItemsMapper.badgeFor(d, isFirstHeard = true, isPartial = true))
    }

    // -- R-040 badges ------------------------------------------------------------------------

    @Test
    fun `R_040 a corrected row shows CORRECTED even if it is also revised and first heard`() {
        val d = detail(corrected = true, supersededTranscriptTexts = listOf("earlier"))

        assertEquals(LogRowBadge.CORRECTED, LogItemsMapper.badgeFor(d, isFirstHeard = true, isPartial = false))
    }

    @Test
    fun `R_040 a superseded transcript shows REVISED`() {
        val d = detail(supersededTranscriptTexts = listOf("earlier"))

        assertEquals(LogRowBadge.REVISED, LogItemsMapper.badgeFor(d, isFirstHeard = false, isPartial = false))
    }

    @Test
    fun `R_040 the first-ever over from a station shows NEW`() {
        val d = detail()

        assertEquals(LogRowBadge.NEW, LogItemsMapper.badgeFor(d, isFirstHeard = true, isPartial = false))
    }

    @Test
    fun `R_040 an ordinary row carries no badge`() {
        val d = detail()

        assertNull(LogItemsMapper.badgeFor(d, isFirstHeard = false, isPartial = false))
    }

    // -- R-322 (reopened R-240): the AMBIGUOUS row's kept (primary) candidate and its "or QRF" ---
    // alternate — both rank-based now, matching `DetailViewStateMapper.ambiguousBody`'s own
    // `candidates.sortedBy { it.rank }.take(2)`, never `selected` (real AMBIGUOUS candidates are
    // never `selected`, confirmed by `AmbiguousCandidatesFixtureTest`'s own assertion against the
    // real `overnight` fixture — the exact fixture shape these tests now use too).

    @Test
    fun `R_322 the kept candidate is the best-ranked of an AMBIGUOUS over's real, unselected candidates`() {
        val inspection = InspectionViewState(
            lattice = null,
            candidates = listOf(
                candidate("KE7QRS", rank = 0),
                candidate("KE7QRF", rank = 1),
                candidate("KE7QRZ", rank = 2),
            ),
        )
        val d = detail(attribution = Attribution.ambiguous(), inspection = inspection)

        assertEquals("KE7QRS", LogItemsMapper.keptCandidateFor(d))
        assertEquals("KE7QRF", LogItemsMapper.alternateFor(d)) // the runner-up, not the third candidate.
    }

    @Test
    fun `R_322 candidate order in the list never matters, only rank`() {
        val inspection = InspectionViewState(
            lattice = null,
            candidates = listOf(candidate("KE7QRF", rank = 1), candidate("KE7QRS", rank = 0)),
        )
        val d = detail(attribution = Attribution.ambiguous(), inspection = inspection)

        assertEquals("KE7QRS", LogItemsMapper.keptCandidateFor(d))
        assertEquals("KE7QRF", LogItemsMapper.alternateFor(d))
    }

    @Test
    fun `R_322 a single recorded candidate is the kept one with no alternate`() {
        val inspection = InspectionViewState(lattice = null, candidates = listOf(candidate("KE7QRS", rank = 0)))
        val d = detail(attribution = Attribution.ambiguous(), inspection = inspection)

        assertEquals("KE7QRS", LogItemsMapper.keptCandidateFor(d))
        assertNull(LogItemsMapper.alternateFor(d))
    }

    @Test
    fun `R_322 no candidates recorded at all never fabricates a kept candidate or an alternate`() {
        val d = detail(attribution = Attribution.ambiguous(), inspection = InspectionViewState.EMPTY)

        assertNull(LogItemsMapper.keptCandidateFor(d))
        assertNull(LogItemsMapper.alternateFor(d))
    }

    @Test
    fun `R_322 a non-AMBIGUOUS row never carries a kept candidate or an alternate, even with candidates recorded`() {
        val selectedCandidate = candidate("W7NPC", rank = 0, selected = true)
        val inspection = InspectionViewState(lattice = null, candidates = listOf(selectedCandidate))
        val d = detail(attribution = Attribution.confirmed("W7NPC", 0.95), inspection = inspection)

        assertNull(LogItemsMapper.keptCandidateFor(d))
        assertNull(LogItemsMapper.alternateFor(d))
    }

    // -- R-040 gap labelling (FR-UI-12/FR-RUN-12) ----------------------------------------------

    @Test
    fun `R_040 a finished gap names its duration and cause`() {
        val gap = gap(endedAt = 38_000L, cause = CaptureGapCause.CALL)

        // R-249 (V3 pass 2): a space before the unit — "38 s", never "38s" — through the one
        // shared duration formatter every "how long" label in the reader now uses.
        assertEquals("not listening · 38 s · incoming call", LogItemsMapper.gapLabel(gap))
    }

    @Test
    fun `R_249 a gap over a minute reads minutes and seconds, both spaced`() {
        val g = gap(endedAt = 115_000L, cause = CaptureGapCause.INTERRUPTION)

        assertEquals("not listening · 1 m 55 s · interruption", LogItemsMapper.gapLabel(g))
    }

    @Test
    fun `R_040 a gap still open reads ongoing, never a fabricated duration`() {
        val g = gap(endedAt = null, cause = CaptureGapCause.UNKNOWN)

        assertTrue(LogItemsMapper.gapLabel(g).startsWith("not listening · ongoing"))
    }

    // -- R-040 grouping ------------------------------------------------------------------------

    @Test
    fun `R_040 consecutive same-thread rows get a QSO group header naming the over and station counts`() {
        val details = listOf(
            detail(id = "TX1", threadId = "T1", attribution = Attribution.confirmed("W7NPC", 0.9)),
            detail(
                id = "TX2",
                startedAtUtcMillis = 1_000L,
                threadId = "T1",
                attribution = Attribution.confirmed("K7LWH", 0.9),
            ),
        )

        val items = LogItemsMapper.buildItems(details, emptyList(), LogFilterSelection(), emptySet())

        val header = items.first() as LogListItem.Group
        assertEquals("QSO · 2 overs · 2 stations", header.label)
        assertEquals(3, items.size)
    }

    @Test
    fun `R_040 gaps and rejected segments interleave chronologically with normal rows`() {
        val details = listOf(
            detail(id = "TX1"),
            detail(
                id = "TX2",
                startedAtUtcMillis = 20_000L,
                processingState = TransmissionState.REJECTED,
                rejectionReason = "squelch tail",
            ),
        )
        val gaps = listOf(gap(startedAt = 10_000L, endedAt = 15_000L, cause = CaptureGapCause.CALL))
        val selection = LogFilterSelection(showRejected = true, showGaps = true)

        val items = LogItemsMapper.buildItems(details, gaps, selection, firstHeardIds = emptySet())

        assertEquals(3, items.size)
        assertTrue(items[0] is LogListItem.Row)
        assertTrue(items[1] is LogListItem.Gap)
        assertTrue(items[2] is LogListItem.RejectedItem)
    }

    @Test
    fun `R_043 rejected segments are excluded from the normal list unless showRejected is on`() {
        val details = listOf(
            detail(id = "TX1", processingState = TransmissionState.REJECTED, rejectionReason = "squelch tail"),
        )

        val hidden = LogItemsMapper.buildItems(details, emptyList(), LogFilterSelection(), emptySet())
        val shown = LogItemsMapper.buildItems(details, emptyList(), LogFilterSelection(showRejected = true), emptySet())

        assertTrue(hidden.isEmpty())
        assertEquals(1, shown.size)
    }

    @Test
    fun `R_043 the dedicated rejected-focus view lists every rejected segment regardless of other filters`() {
        val details = listOf(
            detail(id = "TX1", processingState = TransmissionState.REJECTED, rejectionReason = "squelch tail"),
            detail(id = "TX2", processingState = TransmissionState.REJECTED, rejectionReason = "too short"),
            detail(id = "TX3", attribution = Attribution.confirmed("W7NPC", 0.9)),
        )

        val focus = LogItemsMapper.buildRejectedFocus(details)

        assertEquals(2, focus.size)
        assertEquals("squelch tail", focus[0].reason)
    }

    @Test
    fun `R_043_rejected_rows_explain_why_when_the_record_says`() {
        // The real write path (DataPassBResultSink) stores "$rule: $detail" — never the raw rule
        // token alone, and never guessed for a rule this mapping does not recognise.
        assertEquals(
            "Too short, segment is 120 ms, below the 250 ms floor",
            LogItemsMapper.whyFor("TOO_SHORT: segment is 120 ms, below the 250 ms floor"),
        )
        assertEquals(
            "No speech detected, VAD did not detect speech in this segment",
            LogItemsMapper.whyFor("VAD_NO_SPEECH: VAD did not detect speech in this segment"),
        )
        assertEquals(
            "Low speech confidence, no-speech score 0.94, above the 0.6 ceiling",
            LogItemsMapper.whyFor("NO_SPEECH_PROB: no_speech_prob=0.94 exceeds ceiling=0.6"),
        )
        assertEquals(
            "Unusual compression ratio, compression ratio 2.65, above the 2.40 ceiling",
            LogItemsMapper.whyFor("COMPRESSION_RATIO: compression ratio=2.65 exceeds ceiling=2.40"),
        )

        // Nothing beyond the short reason -- no ": " separator at all -- is honestly null, not a
        // repeat of the short reason.
        assertNull(LogItemsMapper.whyFor("squelch tail"))
        assertNull(LogItemsMapper.whyFor(null))

        // An unrecognised rule token never gets a guessed category.
        assertNull(LogItemsMapper.whyFor("SOME_FUTURE_RULE: a detail this mapping has never seen"))

        // Wired through to the dedicated Rejected view's rows, never a raw enum name in the result.
        val focusWithDetail = LogItemsMapper.buildRejectedFocus(
            listOf(
                detail(
                    id = "TX9",
                    processingState = TransmissionState.REJECTED,
                    rejectionReason = "TOO_SHORT: segment is 120 ms, below the 250 ms floor",
                ),
            ),
        )
        val why = focusWithDetail.single().why
        assertEquals("Too short, segment is 120 ms, below the 250 ms floor", why)
        assertTrue(why != null && !why.contains("TOO_SHORT"))
    }

    @Test
    fun `R_331_reason the list REASON column reads the human short reason, never the raw rule id`() {
        // `Log-Rejected.dc.html`'s own six example rows, read verbatim -- never the raw
        // `"$rule: $detail"` record `whyFor`'s own elaboration reads from.
        assertEquals("too short", LogItemsMapper.shortReasonFor("TOO_SHORT: segment is 120 ms, below the 250 ms floor"))
        assertEquals("squelch tail", LogItemsMapper.shortReasonFor("VAD_NO_SPEECH: squelch tail, 0.4 s"))
        assertEquals(
            "no-speech probability",
            LogItemsMapper.shortReasonFor("NO_SPEECH_PROB: no_speech_prob=0.94 exceeds ceiling=0.6"),
        )
        assertEquals("repeated text", LogItemsMapper.shortReasonFor("REPETITION: verbatim repeat of the prior over"))
        assertEquals("hallucination", LogItemsMapper.shortReasonFor("BLOCKLIST: thank you for watching"))
        assertEquals(
            "compression ratio",
            LogItemsMapper.shortReasonFor("COMPRESSION_RATIO: compression ratio=2.65 exceeds ceiling=2.40"),
        )

        // A bare short reason with no "$rule: " prefix at all is already the short reason.
        assertEquals("squelch tail", LogItemsMapper.shortReasonFor("squelch tail"))
        // Nothing recorded at all is honest, not a guessed reason.
        assertEquals("no reason recorded", LogItemsMapper.shortReasonFor(null))
        // An unrecognised rule token falls back to the raw record rather than a guessed short reason.
        assertEquals(
            "SOME_FUTURE_RULE: a detail this mapping has never seen",
            LogItemsMapper.shortReasonFor("SOME_FUTURE_RULE: a detail this mapping has never seen"),
        )

        // Wired through to the dedicated Rejected view's rows.
        val focus = LogItemsMapper.buildRejectedFocus(
            listOf(
                detail(
                    id = "TX9",
                    processingState = TransmissionState.REJECTED,
                    rejectionReason = "VAD_NO_SPEECH: squelch tail, 0.4 s",
                ),
            ),
        )
        assertEquals("squelch tail", focus.single().reason)
    }

    @Test
    fun `R_331_duration a sub-second rejected segment never rounds down to 0 s`() {
        assertEquals("0.4 s", ReaderTransmissionViewStateMapper.durationLabel(400L))
        assertEquals("0.9 s", ReaderTransmissionViewStateMapper.durationLabel(900L))
        assertEquals("0.0 s", ReaderTransmissionViewStateMapper.durationLabel(0L))
        // At or beyond a second, unchanged whole-second behaviour.
        assertEquals("1 s", ReaderTransmissionViewStateMapper.durationLabel(1_000L))
        assertEquals("4 s", ReaderTransmissionViewStateMapper.durationLabel(4_200L))

        val focus = LogItemsMapper.buildRejectedFocus(
            listOf(
                detail(id = "TX9", processingState = TransmissionState.REJECTED, rejectionReason = "squelch tail")
                    .copy(durationMs = 400L),
            ),
        )
        assertEquals("0.4 s", focus.single().durationLabel)
    }

    // -- R-042 filtering ------------------------------------------------------------------------

    @Test
    fun `R_042 attribution filter excludes states not selected`() {
        val details = listOf(
            detail(id = "TX1", attribution = Attribution.confirmed("W7NPC", 0.9)),
            detail(id = "TX2", attribution = Attribution.unknown()),
        )
        val selection = LogFilterSelection(attributionStates = setOf(AttributionState.CONFIRMED))

        val items = LogItemsMapper.buildItems(details, emptyList(), selection, emptySet())

        assertEquals(1, items.size)
        assertEquals("TX1", (items[0] as LogListItem.Row).state.id)
    }

    @Test
    fun `R_042 frequency filter narrows to the selected frequency only`() {
        val details = listOf(
            detail(id = "TX1", frequencyHz = 145_230_000L),
            detail(id = "TX2", frequencyHz = 146_960_000L),
        )
        val selection = LogFilterSelection(frequencyHz = 146_960_000L)

        val items = LogItemsMapper.buildItems(details, emptyList(), selection, emptySet())

        assertEquals(1, items.size)
        assertEquals("TX2", (items[0] as LogListItem.Row).state.id)
    }

    @Test
    fun `R_042 the Named quick filter keeps only CONFIRMED and INFERRED rows`() {
        val sheet = LogFilterSelection()
        val named = LogItemsMapper.selectionFor(LogQuickFilterId.Named, sheet)

        assertEquals(setOf(AttributionState.CONFIRMED, AttributionState.INFERRED), named.attributionStates)
    }

    // -- R-045 empty state -----------------------------------------------------------------------

    @Test
    fun `R_045 a session with no transmissions at all says listening since, never a bare blank`() {
        val empty = LogItemsMapper.emptyStateFor(hasAnyTransmission = false, sessionStartedAtUtcMillis = 0L)

        assertEquals("No overs yet.", empty.message)
        assertTrue(empty.subMessage.contains("Listening since"))
    }

    @Test
    fun `R_045 a filter that matches nothing says so distinctly from true emptiness`() {
        val empty = LogItemsMapper.emptyStateFor(hasAnyTransmission = true, sessionStartedAtUtcMillis = 0L)

        assertEquals("No overs match this filter.", empty.message)
    }

    @Test
    fun `R_248 known frequencies are named in the listening-since sentence`() {
        val empty = LogItemsMapper.emptyStateFor(
            hasAnyTransmission = false,
            sessionStartedAtUtcMillis = 0L,
            frequencies = listOf(145_230_000L, 146_960_000L),
        )

        assertEquals(
            "Listening since 00:00 on 145.230 and 146.960. The first one appears here the moment squelch opens.",
            empty.subMessage,
        )
    }

    @Test
    fun `R_247 no known frequencies at all omits the on-clause entirely, never a fabricated one`() {
        val empty = LogItemsMapper.emptyStateFor(hasAnyTransmission = false, sessionStartedAtUtcMillis = null)

        assertEquals(
            "Listening since —. The first one appears here the moment squelch opens.",
            empty.subMessage,
        )
    }
}
