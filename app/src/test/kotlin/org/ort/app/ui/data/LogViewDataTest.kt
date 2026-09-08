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

    // -- R-040 the AMBIGUOUS "or QRF" alternate ------------------------------------------------

    @Test
    fun `R_040 an AMBIGUOUS row's alternate is the best-ranked non-selected candidate`() {
        val inspection = InspectionViewState(
            lattice = null,
            candidates = listOf(candidate("KE7QRS", rank = 1), candidate("QRF", rank = 2)),
        )
        val d = detail(attribution = Attribution.ambiguous(), inspection = inspection)

        assertEquals("KE7QRS", LogItemsMapper.alternateFor(d))
    }

    @Test
    fun `R_040 a non-AMBIGUOUS row never carries an alternate, even with candidates recorded`() {
        val selectedCandidate = candidate("W7NPC", rank = 1, selected = true)
        val inspection = InspectionViewState(lattice = null, candidates = listOf(selectedCandidate))
        val d = detail(attribution = Attribution.confirmed("W7NPC", 0.95), inspection = inspection)

        assertNull(LogItemsMapper.alternateFor(d))
    }

    // -- R-040 gap labelling (FR-UI-12/FR-RUN-12) ----------------------------------------------

    @Test
    fun `R_040 a finished gap names its duration and cause`() {
        val gap = gap(endedAt = 38_000L, cause = CaptureGapCause.CALL)

        assertEquals("not listening · 38s · incoming call", LogItemsMapper.gapLabel(gap))
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
}
