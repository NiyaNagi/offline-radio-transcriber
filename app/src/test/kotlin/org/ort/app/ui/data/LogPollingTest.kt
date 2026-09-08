package org.ort.app.ui.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.components.LogRowBadge
import org.ort.app.ui.components.LogRowPartial
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.CaptureGapCause
import org.ort.data.entity.CaptureGapEntity
import org.ort.data.entity.CorrectionEntity
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TranscriptPass
import org.ort.data.entity.TransmissionEntity
import org.robolectric.RobolectricTestRunner

/**
 * R-040/R-041/R-042/R-043/R-045 (ui-conformance WP5): [LogPolling]'s own `:data`-direct read
 * path, against a real (file-backed, matching [LogPolling]'s own `OrtDatabase.create(context)`
 * call) Room database — deliberately not `ui/data/ReaderPolling.kt`, per this package's own row.
 */
@RunWith(RobolectricTestRunner::class)
class LogPollingTest {

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
    }

    @Test
    fun `R_040 a station heard in an earlier session does not carry the NEW badge on a later over`(): Unit = runTest {
        db.sessionDao().insert(session("S0"))
        db.sessionDao().insert(session("S1"))
        db.transmissionDao().insert(
            transmission("OLD", sessionId = "S0", samplePosition = 1L, startedAtUtc = 0L, stationId = "W7NPC"),
        )
        db.transmissionDao().insert(
            transmission(
                "NEW-SESSION",
                sessionId = "S1",
                samplePosition = 1L,
                startedAtUtc = 10_000L,
                stationId = "W7NPC",
            ),
        )

        val state = LogPolling.screenState(context, "S1", LogFilterSelection(), LogQuickFilterId.All)

        val row = (state.items.single() as LogListItem.Row).state
        assertEquals(null, row.badge) // heard before, in S0 — not this session's first ever.
    }

    @Test
    fun `R_040 a station never heard before this transmission carries the NEW badge`(): Unit = runTest {
        db.sessionDao().insert(session("S1"))
        db.transmissionDao().insert(transmission("TX1", sessionId = "S1", samplePosition = 1L, stationId = "K7LWH"))

        val state = LogPolling.screenState(context, "S1", LogFilterSelection(), LogQuickFilterId.All)

        val row = (state.items.single() as LogListItem.Row).state
        assertEquals(LogRowBadge.NEW, row.badge)
    }

    @Test
    fun `R_041 a Pass A transcript still processing shows as the hearing partial`(): Unit = runTest {
        db.sessionDao().insert(session("S1"))
        db.transmissionDao().insert(
            transmission("TX1", sessionId = "S1", samplePosition = 1L, processingState = TransmissionState.PROCESSING),
        )
        db.transcriptDao().insert(transcript("T1", "TX1", "and we're clear on the rep", pass = TranscriptPass.A))

        val state = LogPolling.screenState(context, "S1", LogFilterSelection(), LogQuickFilterId.All)

        val row = (state.items.single() as LogListItem.Row).state
        assertEquals(LogRowPartial.HEARING, row.partial)
        assertEquals("and we're clear on the rep", row.transcript)
    }

    @Test
    fun `R_043 a rejected transmission is reachable via Rejected, not the normal list`(): Unit = runTest {
        db.sessionDao().insert(session("S1"))
        db.transmissionDao().insert(
            transmission(
                "TX1",
                sessionId = "S1",
                samplePosition = 1L,
                processingState = TransmissionState.REJECTED,
                rejectionReason = "squelch tail",
            ),
        )

        val normal = LogPolling.screenState(context, "S1", LogFilterSelection(), LogQuickFilterId.All)
        val rejectedFocus = LogPolling.screenState(context, "S1", LogFilterSelection(), LogQuickFilterId.Rejected)

        assertTrue(normal.items.isEmpty())
        assertEquals(1, rejectedFocus.items.size)
        assertTrue(rejectedFocus.rejectedFocus)
        val item = rejectedFocus.items.single() as LogListItem.RejectedItem
        assertEquals("squelch tail", item.reason)
    }

    @Test
    fun `R_040 a not-listening gap interleaves into the log with its real cause`(): Unit = runTest {
        db.sessionDao().insert(session("S1"))
        db.captureGapDao().insert(
            CaptureGapEntity(
                id = "G1",
                sessionId = "S1",
                startedAt = 0L,
                endedAt = 38_000L,
                cause = CaptureGapCause.CALL,
                recoveredAutomatically = true,
            ),
        )

        val state = LogPolling.screenState(context, "S1", LogFilterSelection(), LogQuickFilterId.All)

        val gap = state.items.single() as LogListItem.Gap
        assertTrue(gap.label.contains("incoming call"))
    }

    @Test
    fun `R_040 a corrected transmission's CORRECTED flag is real, from the correction dao`(): Unit = runTest {
        db.sessionDao().insert(session("S1"))
        db.transmissionDao().insert(transmission("TX1", sessionId = "S1", samplePosition = 1L, stationId = "W7NPC"))
        db.correctionDao().recordCorrection(
            CorrectionEntity(
                id = "C1",
                transmissionId = "TX1",
                field = "stationId",
                previousValue = null,
                newValue = "K7LWH",
                correctedAt = 0L,
            ),
        )

        val state = LogPolling.screenState(context, "S1", LogFilterSelection(), LogQuickFilterId.All)

        val row = (state.items.single() as LogListItem.Row).state
        assertEquals(LogRowBadge.CORRECTED, row.badge)
    }

    @Test
    fun `R_045 an empty session says listening since the real session start`(): Unit = runTest {
        db.sessionDao().insert(session("S1", startedAt = 84_720_000L))

        val state = LogPolling.screenState(context, "S1", LogFilterSelection(), LogQuickFilterId.All)

        assertTrue(state.items.isEmpty())
        assertEquals("No overs yet.", state.emptyState?.message)
        assertTrue(state.emptyState!!.subMessage.contains("23:32"))
    }

    private fun session(id: String, startedAt: Long = 0L) = SessionEntity(
        id = id,
        startedAt = startedAt,
        endedAt = null,
        profileId = null,
        deviceTier = null,
        appVersion = "test",
        terminationReason = null,
        sourceId = null,
        schemaVersion = OrtDatabase.SCHEMA_VERSION,
    )

    private fun transmission(
        id: String,
        sessionId: String,
        samplePosition: Long,
        startedAtUtc: Long = samplePosition,
        stationId: String? = null,
        processingState: TransmissionState = TransmissionState.COMPLETE,
        rejectionReason: String? = null,
    ) = TransmissionEntity(
        id = id,
        sessionId = sessionId,
        threadId = null,
        startedAtUtc = startedAtUtc,
        endedAtUtc = startedAtUtc + 1_000L,
        durationMs = 4_200L,
        audioFormat = "flac/16k/mono",
        preRollMs = 200,
        postRollMs = 200,
        frequencyHz = 146_960_000L,
        frequencyProvenance = "measured",
        mode = null,
        signalStrength = 7.0,
        channelName = null,
        voiceprintId = null,
        attributionState = if (stationId != null) AttributionState.CONFIRMED else AttributionState.UNKNOWN,
        stationId = stationId,
        attributionConfidence = if (stationId != null) 0.9 else null,
        attributionSourceTransmissionId = null,
        processingState = processingState,
        rejectionReason = rejectionReason,
        samplePosition = samplePosition,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )

    private fun transcript(id: String, transmissionId: String, text: String, pass: TranscriptPass) = TranscriptEntity(
        id = id,
        transmissionId = transmissionId,
        pass = pass,
        text = text,
        modelId = "distil-small.en",
        modelVersion = "1",
        quantization = null,
        decodeParams = null,
        noSpeechProb = null,
        confidence = null,
        isCurrent = true,
        createdAt = 0L,
    )
}
