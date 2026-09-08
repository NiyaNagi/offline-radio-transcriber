package org.ort.app.ui.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
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
import org.ort.pipeline.capture.RigStatus
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
        RigStatus.reset()
    }

    @After
    fun resetRigStatus() {
        RigStatus.reset()
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

    @Test
    fun `R_248 a first session with the rig connected but no overs yet names both bands`(): Unit = runTest {
        db.sessionDao().insert(session("S1", startedAt = 84_720_000L))
        RigStatus.connected(
            descriptor = "TH-D75A",
            bands = listOf(
                RigStatus.BandState(band = "A", frequencyHz = 145_230_000L, mode = "FM", squelchOpen = false),
                RigStatus.BandState(band = "B", frequencyHz = 146_960_000L, mode = "FM", squelchOpen = false),
            ),
        )

        val state = LogPolling.screenState(context, "S1", LogFilterSelection(), LogQuickFilterId.All)

        assertTrue(state.emptyState!!.subMessage.contains("on 145.230 and 146.960"))
        // R-248: the dimmed per-frequency chips render even though nothing has been heard on
        // either band yet — sourced from the rig, not from (zero) transmissions.
        val chipLabels = state.quickFilters.map { it.label }
        assertTrue(chipLabels.contains("145.230"))
        assertTrue(chipLabels.contains("146.960"))
    }

    @Test
    fun `R_247 no rig connected at all never fabricates an on-frequency clause`(): Unit = runTest {
        db.sessionDao().insert(session("S1", startedAt = 84_720_000L))

        val state = LogPolling.screenState(context, "S1", LogFilterSelection(), LogQuickFilterId.All)

        assertTrue(state.emptyState!!.subMessage.startsWith("Listening since 23:32. "))
    }

    @Test
    fun `R_247 no session at all is a real, fully drawn empty Log, not a blank body`() {
        val state = LogPolling.noSessionState()

        assertEquals("No overs yet.", state.emptyState?.message)
        assertTrue(state.emptyState!!.subMessage.startsWith("Listening since —. "))
        assertTrue(state.quickFilters.any { it.label == "All" && it.selected })
        assertTrue(state.quickFilters.any { it.label == "Named" })
        assertTrue(state.quickFilters.any { it.label == "Rejected" })
    }

    @Test
    fun `R_243 the filter sheet pre-fills the to bound from the data's own extent`(): Unit = runTest {
        db.sessionDao().insert(session("S1", startedAt = 84_720_000L))
        db.transmissionDao().insert(
            transmission("TX1", sessionId = "S1", samplePosition = 1L, startedAtUtc = 84_720_000L + 60_000L),
        )
        db.transmissionDao().insert(
            transmission("TX2", sessionId = "S1", samplePosition = 2L, startedAtUtc = 84_720_000L + 120_000L),
        )

        val sheet = LogPolling.filterSheetState(context, "S1", LogFilterSelection())

        assertEquals("23:32", sheet.fromLabel) // the session's own start (unchanged behaviour).
        assertEquals("23:34", sheet.toLabel) // TX2's start — the latest data point, session still open.
    }

    @Test
    fun `R_330 a gap is not an over — the Show N overs count excludes gaps even when checked`(): Unit = runTest {
        db.sessionDao().insert(session("S1"))
        db.transmissionDao().insert(transmission("TX1", sessionId = "S1", samplePosition = 1L))
        db.transmissionDao().insert(transmission("TX2", sessionId = "S1", samplePosition = 2L, startedAtUtc = 1_000L))
        db.captureGapDao().insert(
            CaptureGapEntity(
                id = "G1",
                sessionId = "S1",
                startedAt = 2_000L,
                endedAt = 40_000L,
                cause = CaptureGapCause.INTERRUPTION,
                recoveredAutomatically = true,
            ),
        )

        // "Not-listening gaps" checked (the default) — before this fix the CTA counted the gap
        // into its own total (2 overs + 1 gap read "Show 3 overs"); a gap is not an over.
        val gapsChecked = LogPolling.filterSheetState(context, "S1", LogFilterSelection(showGaps = true))
        assertEquals(2, gapsChecked.matchingCount)

        val gapsUnchecked = LogPolling.filterSheetState(context, "S1", LogFilterSelection(showGaps = false))
        assertEquals(2, gapsUnchecked.matchingCount) // unchanged either way — a gap was never an over.
    }

    @Test
    fun `R_243 an explicit toMillis selection always wins over the data's extent`(): Unit = runTest {
        db.sessionDao().insert(session("S1", startedAt = 84_720_000L))
        db.transmissionDao().insert(
            transmission("TX1", sessionId = "S1", samplePosition = 1L, startedAtUtc = 84_720_000L + 60_000L),
        )

        val sheet = LogPolling.filterSheetState(
            context,
            "S1",
            LogFilterSelection(toMillis = 84_720_000L + 30_000L),
        )

        assertEquals("23:32", sheet.toLabel)
    }

    @Test
    fun `R_276 the sheet's matching count respects a seeded time window, not just frequency`(): Unit = runTest {
        db.sessionDao().insert(session("S1"))
        db.transmissionDao().insert(
            transmission("TX-in-window", sessionId = "S1", samplePosition = 1L, startedAtUtc = 1_000L),
        )
        db.transmissionDao().insert(
            transmission("TX-out-of-window", sessionId = "S1", samplePosition = 2L, startedAtUtc = 50_000L),
        )

        val sheet = LogPolling.filterSheetState(
            context,
            "S1",
            LogFilterSelection(fromMillis = 0L, toMillis = 10_000L),
        )

        // Before this fix, `matchingCount` ignored `fromMillis`/`toMillis` entirely and would have
        // reported 2 — a number larger than what `buildItems` (and thus the actual rendered list)
        // shows once "Show N overs" is tapped.
        assertEquals(1, sheet.matchingCount)
    }

    @Test
    fun `R_249 a gap duration renders with a space before its unit, through the shared formatter`(): Unit = runTest {
        db.sessionDao().insert(session("S1"))
        db.captureGapDao().insert(
            CaptureGapEntity(
                id = "G1",
                sessionId = "S1",
                startedAt = 0L,
                endedAt = 38_000L,
                cause = CaptureGapCause.INTERRUPTION,
                recoveredAutomatically = true,
            ),
        )

        val state = LogPolling.screenState(context, "S1", LogFilterSelection(), LogQuickFilterId.All)

        val gap = state.items.single() as LogListItem.Gap
        assertTrue(gap.label.contains("38 s"))
        assertTrue(!gap.label.contains("38s"))
    }

    @Test
    fun `R_242 a rejected row with a real rule token carries its why-line and duration`(): Unit = runTest {
        db.sessionDao().insert(session("S1"))
        db.transmissionDao().insert(
            transmission(
                "TX1",
                sessionId = "S1",
                samplePosition = 1L,
                processingState = TransmissionState.REJECTED,
                rejectionReason = "VAD_NO_SPEECH: squelch tail, 0.4 s",
            ),
        )

        val state = LogPolling.screenState(context, "S1", LogFilterSelection(), LogQuickFilterId.Rejected)

        val item = state.items.single() as LogListItem.RejectedItem
        assertEquals("No speech detected, squelch tail, 0.4 s", item.why)
        assertEquals("4 s", item.durationLabel) // the fixture's default 4_200ms transmission duration.
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
