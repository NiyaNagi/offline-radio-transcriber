package org.ort.app.ui.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.entity.CaptureGapCause
import org.ort.data.entity.CaptureGapEntity
import org.ort.data.entity.TranscriptPass
import org.ort.data.entity.TransmissionEntity
import org.ort.testing.Requirement

/**
 * R-1007 (WPL, design-intent N07): [LiveMonitorOversMapper]'s pure rules — the seven states the
 * operator's own session had ("transcribing · done and confirmed · waiting behind three others · a
 * silence that was listened to · inferred with its score chip · not transcribed · unknown
 * station") — every one tested without Robolectric or a database.
 */
class LiveMonitorViewDataTest {

    @Suppress("LongParameterList")
    private fun entity(
        id: String = "TX1",
        startedAtUtc: Long = 10_000L,
        endedAtUtc: Long? = 12_000L,
        durationMs: Long = 2_000L,
        processingState: TransmissionState = TransmissionState.COMPLETE,
        attributionState: AttributionState = AttributionState.UNKNOWN,
        stationId: String? = null,
        attributionConfidence: Double? = null,
        attributionSourceTransmissionId: String? = null,
        frequencyHz: Long? = 145_230_000L,
    ) = TransmissionEntity(
        id = id,
        sessionId = "S1",
        threadId = null,
        startedAtUtc = startedAtUtc,
        endedAtUtc = endedAtUtc,
        durationMs = durationMs,
        audioFormat = "flac",
        preRollMs = 200,
        postRollMs = 200,
        frequencyHz = frequencyHz,
        frequencyProvenance = "RIG",
        mode = "FM",
        signalStrength = null,
        channelName = null,
        voiceprintId = null,
        attributionState = attributionState,
        stationId = stationId,
        attributionConfidence = attributionConfidence,
        attributionSourceTransmissionId = attributionSourceTransmissionId,
        processingState = processingState,
        rejectionReason = null,
        samplePosition = 0L,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )

    // -- rowFor: the seven real states --------------------------------------------------------

    @Test
    @Requirement("R-1007")
    fun `a PROCESSING entity with a current Pass B transcript renders Transcribing`() {
        val row = LiveMonitorOversMapper.rowFor(
            entity = entity(processingState = TransmissionState.PROCESSING),
            currentPass = TranscriptPass.B,
            currentText = "and we're clear",
            aheadCount = 0,
            failedAttemptCount = null,
            tierLabel = "tier 3",
            sourceTimeLabel = null,
        )

        val transcribing = row as? LiveMonitorOverRow.Transcribing
        assertTrue("expected Transcribing, got $row", transcribing != null)
        assertEquals("Pass B running", transcribing!!.passLabel)
        assertEquals("tier 3", transcribing.tierLabel)
    }

    @Test
    @Requirement("R-1007")
    fun `a CAPTURED entity with no transcript yet renders Waiting, never Transcribing`() {
        val row = LiveMonitorOversMapper.rowFor(
            entity = entity(processingState = TransmissionState.CAPTURED),
            currentPass = null,
            currentText = null,
            aheadCount = 3,
            failedAttemptCount = null,
            tierLabel = null,
            sourceTimeLabel = null,
        )

        val waiting = row as? LiveMonitorOverRow.Waiting
        assertTrue("expected Waiting, got $row", waiting != null)
        assertEquals(3, waiting!!.aheadCount)
    }

    @Test
    @Requirement("R-1007")
    fun `a still-CAPTURED entity carrying a Pass A partial is not a list row at all`() {
        // The "Hearing now" card's own fact, not a `Logged tonight` row — the two must never
        // double-count the same transmission.
        val row = LiveMonitorOversMapper.rowFor(
            entity = entity(processingState = TransmissionState.CAPTURED),
            currentPass = TranscriptPass.A,
            currentText = "and we're clear on the repeater",
            aheadCount = 0,
            failedAttemptCount = null,
            tierLabel = null,
            sourceTimeLabel = null,
        )

        assertNull(row)
    }

    @Test
    @Requirement("R-1007")
    fun `a REJECTED entity is not a list row at all`() {
        val row = LiveMonitorOversMapper.rowFor(
            entity = entity(processingState = TransmissionState.REJECTED),
            currentPass = null,
            currentText = null,
            aheadCount = 0,
            failedAttemptCount = null,
            tierLabel = null,
            sourceTimeLabel = null,
        )

        assertNull(row)
    }

    @Test
    @Requirement("R-1007")
    fun `a COMPLETE CONFIRMED entity renders Resolved carrying the confirmed marker and callsign`() {
        val row = LiveMonitorOversMapper.rowFor(
            entity = entity(
                attributionState = AttributionState.CONFIRMED,
                stationId = "W1ABC",
                attributionConfidence = 0.95,
            ),
            currentPass = TranscriptPass.B,
            currentText = "good copy on that",
            aheadCount = 0,
            failedAttemptCount = null,
            tierLabel = null,
            sourceTimeLabel = null,
        )

        val resolved = row as? LiveMonitorOverRow.Resolved
        assertTrue("expected Resolved, got $row", resolved != null)
        assertEquals(AttributionState.CONFIRMED, resolved!!.attribution.state)
        assertEquals("W1ABC", resolved.callsign)
        assertNull("CONFIRMED never carries an inferred-from caption", resolved.inferredFromLabel)
    }

    @Test
    @Requirement("R-1007")
    fun `a COMPLETE INFERRED entity names the transmission it was inferred from, by time`() {
        val row = LiveMonitorOversMapper.rowFor(
            entity = entity(
                id = "TX2",
                attributionState = AttributionState.INFERRED,
                stationId = "W1ABC",
                attributionConfidence = 0.71,
                attributionSourceTransmissionId = "TX1",
            ),
            currentPass = TranscriptPass.B,
            currentText = "back to you on the two metre machine",
            aheadCount = 0,
            failedAttemptCount = null,
            tierLabel = null,
            sourceTimeLabel = "04:58:31",
        )

        val resolved = row as? LiveMonitorOverRow.Resolved
        assertEquals("04:58:31", resolved?.inferredFromLabel)
    }

    @Test
    @Requirement("R-1007")
    fun `a COMPLETE UNKNOWN entity renders Resolved with no callsign, never a fabricated one`() {
        val row = LiveMonitorOversMapper.rowFor(
            entity = entity(attributionState = AttributionState.UNKNOWN),
            currentPass = TranscriptPass.B,
            currentText = "roger that, QSY to the simplex frequency",
            aheadCount = 0,
            failedAttemptCount = null,
            tierLabel = null,
            sourceTimeLabel = null,
        )

        val resolved = row as? LiveMonitorOverRow.Resolved
        assertEquals(AttributionState.UNKNOWN, resolved?.attribution?.state)
        assertNull(resolved?.callsign)
    }

    @Test
    @Requirement("R-1007")
    fun `a FAILED entity renders not-transcribed naming the real attempt count, never a guessed one`() {
        val row = LiveMonitorOversMapper.rowFor(
            entity = entity(processingState = TransmissionState.FAILED),
            currentPass = null,
            currentText = null,
            aheadCount = 0,
            failedAttemptCount = 5,
            tierLabel = null,
            sourceTimeLabel = null,
        )

        val failed = row as? LiveMonitorOverRow.NotTranscribed
        assertEquals("Pass B errored 5 times", failed?.attemptsLabel)
    }

    @Test
    @Requirement("R-1007")
    fun `a FAILED entity with no queue row to read reads an honest absence, never a fabricated count`() {
        val row = LiveMonitorOversMapper.rowFor(
            entity = entity(processingState = TransmissionState.FAILED),
            currentPass = null,
            currentText = null,
            aheadCount = 0,
            failedAttemptCount = null,
            tierLabel = null,
            sourceTimeLabel = null,
        )

        assertNull((row as? LiveMonitorOverRow.NotTranscribed)?.attemptsLabel)
    }

    // -- FR-RUN-12: a silence that was listened to --------------------------------------------

    @Test
    @Requirement("FR-RUN-12")
    fun `a long gap between overs with no capture gap overlapping it is a listened silence row`() {
        val sorted = listOf(
            entity(id = "TX1", startedAtUtc = 0L, endedAtUtc = 1_000L),
            entity(id = "TX2", startedAtUtc = 300_000L, endedAtUtc = 302_000L),
        )

        val rows = LiveMonitorOversMapper.listenedSilenceRows(sorted, emptyList(), floorMillis = 60_000L)

        assertEquals(1, rows.size)
        assertEquals("4 m 59 s", rows.first().second.durationLabel)
    }

    @Test
    @Requirement("FR-RUN-12")
    fun `a span shorter than the floor is not a listened silence row`() {
        val sorted = listOf(
            entity(id = "TX1", startedAtUtc = 0L, endedAtUtc = 1_000L),
            entity(id = "TX2", startedAtUtc = 5_000L, endedAtUtc = 6_000L),
        )

        val rows = LiveMonitorOversMapper.listenedSilenceRows(sorted, emptyList(), floorMillis = 60_000L)

        assertEquals(0, rows.size)
    }

    @Test
    @Requirement("FR-RUN-12")
    fun `a span a real capture gap overlaps is not listened, it is the Log's own not-listening fact`() {
        val sorted = listOf(
            entity(id = "TX1", startedAtUtc = 0L, endedAtUtc = 1_000L),
            entity(id = "TX2", startedAtUtc = 300_000L, endedAtUtc = 302_000L),
        )
        val gaps = listOf(
            CaptureGapEntity(
                id = "G1",
                sessionId = "S1",
                startedAt = 2_000L,
                endedAt = 200_000L,
                cause = CaptureGapCause.INTERRUPTION,
                recoveredAutomatically = true,
            ),
        )

        val rows = LiveMonitorOversMapper.listenedSilenceRows(sorted, gaps, floorMillis = 60_000L)

        assertEquals(0, rows.size)
    }

    @Test
    @Requirement("R-1007")
    fun `summaryLabel names the real total and waiting count, with correct pluralisation`() {
        assertEquals("1 over · 0 waiting", LiveMonitorOversMapper.summaryLabel(1, 0))
        assertEquals("19 overs · 3 waiting", LiveMonitorOversMapper.summaryLabel(19, 3))
    }
}
