package org.ort.app.ui.recordings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.core.capture.CaptureMode
import org.ort.data.entity.CaptureGapCause
import org.ort.data.entity.CaptureGapEntity
import org.ort.data.entity.LabelCertainty
import org.ort.data.entity.TransmissionEntity
import org.ort.data.entity.TransmissionLabelEntity
import org.ort.testing.Requirement

/**
 * `Recording-Session.dc.html` (RC02): [RecordingSessionViewStateMapper] is the pure decision
 * behind [RecordingSessionPolling.state] — every case here is a plain JVM test, no database or
 * Android context, the same discipline [RecordingsViewStateMapperTest] already establishes for
 * RC01.
 */
class RecordingSessionViewStateMapperTest {

    /** [over]'s own attribution facts, bundled so that function stays under detekt's
     * `LongParameterList` — the same "bundle the request" shape [RecordingsViewStateMapperTest
     * .Removal]/`.Counts` already use for the identical reason. */
    private data class OverAttribution(
        val stationId: String? = null,
        val state: AttributionState = if (stationId != null) AttributionState.CONFIRMED else AttributionState.UNKNOWN,
        val sourceTransmissionId: String? = null,
    )

    /** [over]'s own processing-outcome facts, bundled for the same reason as [OverAttribution]. */
    private data class OverProcessing(
        val state: TransmissionState = TransmissionState.COMPLETE,
        val rejectionReason: String? = null,
        val failedAttemptCount: Int? = null,
    )

    private fun transmission(
        id: String,
        startedAtUtc: Long,
        durationMs: Long = 3_000L,
        attribution: OverAttribution = OverAttribution(),
        processing: OverProcessing = OverProcessing(),
    ) = TransmissionEntity(
        id = id,
        sessionId = "S1",
        threadId = null,
        startedAtUtc = startedAtUtc,
        endedAtUtc = startedAtUtc + durationMs,
        durationMs = durationMs,
        audioFormat = "flac/16k/mono",
        preRollMs = 200,
        postRollMs = 200,
        frequencyHz = 146_520_000L,
        frequencyProvenance = "measured",
        mode = null,
        signalStrength = 7.0,
        channelName = null,
        voiceprintId = null,
        attributionState = attribution.state,
        stationId = attribution.stationId,
        attributionConfidence = if (attribution.stationId != null) 0.82 else null,
        attributionSourceTransmissionId = attribution.sourceTransmissionId,
        processingState = processing.state,
        rejectionReason = processing.rejectionReason,
        samplePosition = startedAtUtc,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )

    private fun over(
        id: String,
        startedAtUtc: Long,
        durationMs: Long = 3_000L,
        attribution: OverAttribution = OverAttribution(),
        processing: OverProcessing = OverProcessing(),
        transcriptText: String? = "this is a test",
        label: TransmissionLabelEntity? = null,
        hasAudioFile: Boolean = true,
    ) = RecordingSessionOverInput(
        transmission = transmission(id, startedAtUtc, durationMs, attribution, processing),
        transcriptText = transcriptText,
        callsign = attribution.stationId,
        label = label,
        failedAttemptCount = processing.failedAttemptCount,
        hasAudioFile = hasAudioFile,
    )

    /** [input]'s own session-span facts, bundled for the same reason as [OverAttribution]. */
    private data class SessionSpan(
        val startedAtMillis: Long = 0L,
        val endedAtMillis: Long? = 100_000L,
        val nowMillis: Long = 200_000L,
    )

    /** [input]'s own session-level facts, bundled for the same reason as [OverAttribution]. */
    private data class SessionFacts(
        val captureMode: CaptureMode? = null,
        val failedCount: Int = 0,
        val stationCount: Int = 0,
        val deleteFreesBytes: Long = 0L,
        val exportAvailable: Boolean = true,
    )

    private fun input(
        overs: List<RecordingSessionOverInput> = emptyList(),
        gaps: List<CaptureGapEntity> = emptyList(),
        span: SessionSpan = SessionSpan(),
        facts: SessionFacts = SessionFacts(),
        playingTransmissionId: String? = null,
    ) = RecordingSessionMapperInput(
        sessionId = "S1",
        startedAtMillis = span.startedAtMillis,
        endedAtMillis = span.endedAtMillis,
        captureMode = facts.captureMode,
        overs = overs,
        gaps = gaps,
        failedCount = facts.failedCount,
        stationCount = facts.stationCount,
        deleteFreesBytes = facts.deleteFreesBytes,
        exportAvailable = facts.exportAvailable,
        playingTransmissionId = playingTransmissionId,
        nowMillis = span.nowMillis,
    )

    @Test
    @Requirement("FR-RUN-12")
    fun `overs and gaps interleave in real chronological order, not appended in two blocks`() {
        val state = RecordingSessionViewStateMapper.map(
            input(
                overs = listOf(over("T1", startedAtUtc = 0L), over("T2", startedAtUtc = 20_000L)),
                gaps = listOf(gap("G1", startedAt = 10_000L, endedAt = 15_000L)),
            ),
        )
        assertEquals(listOf("T1", "G1", "T2"), state.rows.map { it.id })
    }

    @Test
    @Requirement("RC02")
    fun `a resolved over carries its real attribution, transcript and duration, never a fabricated placeholder`() {
        val state = RecordingSessionViewStateMapper.map(
            input(overs = listOf(over("T1", startedAtUtc = 0L, attribution = OverAttribution(stationId = "W7NPC")))),
        )
        val row = state.rows.single() as RecordingSessionRow.Over
        assertEquals(RecordingSessionOverStatus.RESOLVED, row.status)
        assertEquals("W7NPC", row.callsign)
        assertEquals("this is a test", row.transcript)
        assertEquals(AttributionState.CONFIRMED.name.lowercase(), row.attributionStateLabel)
    }

    @Test
    @Requirement("constitution I")
    fun `a failed over carries no transcript at all, never a stale or fabricated one`() {
        val state = RecordingSessionViewStateMapper.map(
            input(
                overs = listOf(
                    over(
                        "T1",
                        startedAtUtc = 0L,
                        processing = OverProcessing(state = TransmissionState.FAILED, failedAttemptCount = 5),
                        transcriptText = "should never surface",
                    ),
                ),
            ),
        )
        val row = state.rows.single() as RecordingSessionRow.Over
        assertEquals(RecordingSessionOverStatus.FAILED, row.status)
        assertNull(row.transcript)
        assertNull(row.attribution)
        assertTrue(row.canRetry)
        assertEquals("Pass B errored 5 times", row.statusReasonLabel)
    }

    @Test
    @Requirement("RC02")
    fun `a rejected over is not retryable and carries the real rejection reason`() {
        val state = RecordingSessionViewStateMapper.map(
            input(
                overs = listOf(
                    over(
                        "T1",
                        startedAtUtc = 0L,
                        processing = OverProcessing(
                            state = TransmissionState.REJECTED,
                            rejectionReason = "0.4 s of noise after the carrier dropped",
                        ),
                    ),
                ),
            ),
        )
        val row = state.rows.single() as RecordingSessionRow.Over
        assertEquals(RecordingSessionOverStatus.REJECTED, row.status)
        assertFalse(row.canRetry)
        assertEquals("0.4 s of noise after the carrier dropped", row.statusReasonLabel)
    }

    @Test
    @Requirement("RC02")
    fun `an inferred over names the real source over's own time, never a guessed one`() {
        // [org.ort.core.TransmissionId.parse] requires a real 26-char Crockford-base32 ULID —
        // a plain id like "T1" fails that parse (caught, not thrown) and would silently make this
        // test pass for the wrong reason (a null source, as the next test below asserts on
        // purpose). The source over's own [id] is this exact ULID so the lookup by parsed id
        // succeeds.
        val sourceId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        val state = RecordingSessionViewStateMapper.map(
            input(
                overs = listOf(
                    over(sourceId, startedAtUtc = 0L, attribution = OverAttribution(stationId = "W7NPC")),
                    over(
                        "T2",
                        startedAtUtc = 90_000L,
                        attribution = OverAttribution(
                            stationId = "W7NPC",
                            state = AttributionState.INFERRED,
                            sourceTransmissionId = sourceId,
                        ),
                    ),
                ),
            ),
        )
        val inferredRow = state.rows.map { it as RecordingSessionRow.Over }.single { it.id == "T2" }
        assertEquals(ReaderTimeLabel.of(0L), inferredRow.inferredFromLabel)
    }

    @Test
    @Requirement("constitution I")
    fun `an inferred over whose source is unresolvable carries no inferred-from label, never a guess`() {
        val state = RecordingSessionViewStateMapper.map(
            input(
                overs = listOf(
                    over(
                        "T2",
                        startedAtUtc = 0L,
                        attribution = OverAttribution(
                            stationId = "W7NPC",
                            state = AttributionState.INFERRED,
                            sourceTransmissionId = "does-not-exist",
                        ),
                    ),
                ),
            ),
        )
        val row = state.rows.single() as RecordingSessionRow.Over
        assertNull(row.inferredFromLabel)
    }

    @Test
    @Requirement("FR-OBS-4")
    fun `training label combines the real mark and rating, and is absent when not marked`() {
        val marked = RecordingSessionViewStateMapper.map(
            input(
                overs = listOf(
                    over(
                        "T1",
                        startedAtUtc = 0L,
                        label = TransmissionLabelEntity(
                            transmissionId = "T1",
                            markedForTraining = true,
                            rating = "good",
                            labelledAtMillis = 1L,
                        ),
                    ),
                ),
            ),
        )
        assertEquals("training · good", (marked.rows.single() as RecordingSessionRow.Over).trainingLabel)

        val unmarked = RecordingSessionViewStateMapper.map(
            input(
                overs = listOf(
                    over(
                        "T1",
                        startedAtUtc = 0L,
                        label = TransmissionLabelEntity(
                            transmissionId = "T1",
                            markedForTraining = false,
                            rating = "good",
                            labelledAtMillis = 1L,
                        ),
                    ),
                ),
            ),
        )
        assertNull((unmarked.rows.single() as RecordingSessionRow.Over).trainingLabel)
    }

    @Test
    @Requirement("constitution I")
    fun `never an attribution never confused with a label -- a label never changes what attribution reports`() {
        val state = RecordingSessionViewStateMapper.map(
            input(
                overs = listOf(
                    over(
                        "T1",
                        startedAtUtc = 0L,
                        // Not attributed at all (UNKNOWN) -- a training label on the same over must
                        // never promote it.
                        label = TransmissionLabelEntity(
                            transmissionId = "T1",
                            markedForTraining = true,
                            rating = "good",
                            truthCallsign = "W7NPC",
                            callsignCertainty = LabelCertainty.CERTAIN,
                            labelledAtMillis = 1L,
                        ),
                    ),
                ),
            ),
        )
        val row = state.rows.single() as RecordingSessionRow.Over
        assertEquals(AttributionState.UNKNOWN, row.attribution?.state)
        assertEquals("training · good", row.trainingLabel)
    }

    @Test
    @Requirement("RC02")
    fun `the coverage strip's playhead is real only when the loaded transmission belongs to this session`() {
        val span = SessionSpan(startedAtMillis = 0L, endedAtMillis = 100_000L)
        val withPlayhead = RecordingSessionViewStateMapper.map(
            input(overs = listOf(over("T1", startedAtUtc = 50_000L)), span = span, playingTransmissionId = "T1"),
        )
        assertEquals(0.5f, withPlayhead.coverage.playheadFraction!!, 0.001f)

        val withoutPlayhead = RecordingSessionViewStateMapper.map(
            input(overs = listOf(over("T1", startedAtUtc = 50_000L)), span = span, playingTransmissionId = "elsewhere"),
        )
        assertNull(withoutPlayhead.coverage.playheadFraction)
    }

    @Test
    @Requirement("FR-UI-12")
    fun `a coverage tick is amber only for a real failure, never for any other status`() {
        val state = RecordingSessionViewStateMapper.map(
            input(
                overs = listOf(
                    over("T1", startedAtUtc = 0L, processing = OverProcessing(state = TransmissionState.FAILED)),
                    over("T2", startedAtUtc = 50_000L),
                ),
                span = SessionSpan(startedAtMillis = 0L, endedAtMillis = 100_000L),
            ),
        )
        assertEquals(2, state.coverage.ticks.size)
        assertTrue(state.coverage.ticks[0].failed)
        assertFalse(state.coverage.ticks[1].failed)
    }

    @Test
    @Requirement("D40", "P9")
    fun `deleteFreesBytes and exportAvailable pass through untouched, never recomputed here`() {
        val state = RecordingSessionViewStateMapper.map(
            input(facts = SessionFacts(deleteFreesBytes = 655_000_000L, exportAvailable = false)),
        )
        assertEquals(655_000_000L, state.deleteFreesBytes)
        assertFalse(state.exportAvailable)
    }

    @Test
    @Requirement("FR-CAP-10", "AC-129")
    fun `the header names the real capture mode, with room audio disclosed for local microphone`() {
        val localFacts = SessionFacts(captureMode = CaptureMode.LOCAL_MICROPHONE)
        val local = RecordingSessionViewStateMapper.map(input(facts = localFacts))
        assertEquals("local microphone · room audio", local.header.modeLabel)

        val usb = RecordingSessionViewStateMapper.map(input(facts = SessionFacts(captureMode = CaptureMode.USB_RADIO)))
        assertEquals("usb-connected radio", usb.header.modeLabel)

        val untracked = RecordingSessionViewStateMapper.map(input())
        assertNull(untracked.header.modeLabel)
    }

    @Test
    @Requirement("RC02")
    fun `the counts label states overs, stations, gaps and failed, omitting a clause that is genuinely zero`() {
        val state = RecordingSessionViewStateMapper.map(input(overs = listOf(over("T1", startedAtUtc = 0L))))
        assertFalse(state.header.countsLabel.contains("gap"))
        assertFalse(state.header.countsLabel.contains("failed"))

        val withBoth = RecordingSessionViewStateMapper.map(
            input(
                overs = listOf(over("T1", startedAtUtc = 0L)),
                gaps = listOf(gap("G1", 10_000L, 20_000L)),
                facts = SessionFacts(failedCount = 2),
            ),
        )
        assertTrue(withBoth.header.countsLabel.contains("1 gap"))
        assertTrue(withBoth.header.countsLabel.contains("2 failed"))
    }

    @Test
    @Requirement("RC02", "constitution I")
    fun `a short session's axis carries five distinct, evenly-spaced minute-precision labels`() {
        // 1 h 14 m, the live-session span round 2's own coordinator review found reading as five
        // identical "10" ticks -- hour-only granularity cannot tell apart five points inside one
        // hour.
        val span = SessionSpan(startedAtMillis = 0L, endedAtMillis = 74 * 60_000L)
        val state = RecordingSessionViewStateMapper.map(input(span = span))
        assertEquals(5, state.coverage.axisLabels.size)
        assertEquals(5, state.coverage.axisLabels.toSet().size)
        assertEquals(listOf("00:00", "00:18", "00:37", "00:55", "01:14"), state.coverage.axisLabels)
    }

    @Test
    @Requirement("RC02")
    fun `a multi-hour session's axis shows a bare hour only when a tick truly lands on it`() {
        // 6 h 42 m, the real `overnight` scenario's own span, starting exactly on the hour.
        // Round 3 (coordinator review): the previous version of this test asserted
        // ["00","01","03","05","06"] -- truncating every tick to its own hour, which is exactly
        // the "05 07 08 10 12, uneven gaps" defect the coordinator's device evidence found: the
        // five real instants ARE evenly spaced (100.5 minutes apart), but four of the five land
        // between hours, so rounding every one down to "the hour" makes evenly-spaced instants
        // read as unevenly-spaced labels. Only a tick that truly lands on the hour omits minutes.
        val span = SessionSpan(startedAtMillis = 0L, endedAtMillis = (6 * 60 + 42) * 60_000L)
        val state = RecordingSessionViewStateMapper.map(input(span = span))
        assertEquals(5, state.coverage.axisLabels.size)
        assertEquals(5, state.coverage.axisLabels.toSet().size)
        assertEquals(listOf("00", "01:40", "03:21", "05:01", "06:42"), state.coverage.axisLabels)
    }

    @Test
    @Requirement("RC02", "constitution I")
    fun `a multi-hour session starting off the hour shows five genuinely evenly-spaced minute labels`() {
        // 05:12 - 12:04 UTC (6 h 52 m) -- round 3's own coordinator-cited span: no tick lands on
        // an exact hour, so this is the shape that most needs minutes to read honestly.
        val span = SessionSpan(startedAtMillis = 18_720_000L, endedAtMillis = 43_440_000L)
        val state = RecordingSessionViewStateMapper.map(input(span = span))
        assertEquals(5, state.coverage.axisLabels.size)
        assertEquals(5, state.coverage.axisLabels.toSet().size)
        assertEquals(
            listOf("05:12", "06:55", "08:38", "10:21", "12:04"),
            state.coverage.axisLabels,
        )
    }

    @Test
    @Requirement("RC02", "constitution VI")
    fun `the same session's span always maps to the same axis labels`() {
        val span = SessionSpan(startedAtMillis = 12_345L, endedAtMillis = 12_345L + 74 * 60_000L)
        val first = RecordingSessionViewStateMapper.map(input(span = span))
        val second = RecordingSessionViewStateMapper.map(input(span = span))
        assertEquals(first.coverage.axisLabels, second.coverage.axisLabels)
    }

    @Test
    @Requirement("constitution I")
    fun `a real, non-zero byte count never reads as a fabricated 0-point-0 GB`() {
        // Round 2 (coordinator review): the overnight session's own real over audio -- a few
        // kilobytes of 4-second FLAC clips -- rendered "frees 0.0 GB" verbatim on device
        // (`screen_delete.png`, `rc02_overnight.png`), an honest non-zero size read as nothing.
        assertEquals("0 B", recordingSessionByteLabel(0L))
        assertEquals("512 B", recordingSessionByteLabel(512L))
        assertEquals("2 KB", recordingSessionByteLabel(2_400L))
        assertEquals("1.5 MB", recordingSessionByteLabel(1_500_000L))
        assertEquals("1.1 GB", recordingSessionByteLabel(1_100_000_000L))
    }

    private fun gap(id: String, startedAt: Long, endedAt: Long?, cause: CaptureGapCause = CaptureGapCause.INPUT_LOST) =
        CaptureGapEntity(
            id = id,
            sessionId = "S1",
            startedAt = startedAt,
            endedAt = endedAt,
            cause = cause,
            recoveredAutomatically = true,
        )
}

/** Mirrors [org.ort.app.ui.data.ReaderTransmissionViewStateMapper.timeLabel] exactly, so this test
 * asserts the real function's own output rather than a copy-pasted literal that could quietly
 * drift from it. */
private object ReaderTimeLabel {
    fun of(startedAtUtcMillis: Long): String =
        org.ort.app.ui.data.ReaderTransmissionViewStateMapper.timeLabel(startedAtUtcMillis)
}
