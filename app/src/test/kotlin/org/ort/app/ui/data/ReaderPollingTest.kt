package org.ort.app.ui.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.AttributionState
import org.ort.core.SystemClock
import org.ort.core.Tier
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TranscriptPass
import org.ort.data.entity.TransmissionEntity
import org.ort.pipeline.capture.AsrAvailability
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.ShedStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus
import org.ort.pipeline.capture.VadAvailability
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * FR-UI-1, real `:data` read path (build-plan P14): a transmission appears newest first, a
 * transmission with no transcript row is shown honestly rather than as empty or hidden, and a
 * superseded partial stays visible rather than being silently replaced by whatever pass ran next.
 * `ReaderTransmissionViewStateMapperTest` covers the pure mapping; this covers that the real DAOs
 * feed it correctly, against a real (in-memory) Room database — no fake stands in for `:data`
 * here, since `:data`'s own P5 test suite already proves the entities/DAOs themselves.
 */
@RunWith(RobolectricTestRunner::class)
class ReaderPollingTest {

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun openDatabase() {
        // Deliberately *not* in-memory: `ReaderPolling` itself opens `OrtDatabase.create(context)`
        // with its default (file-backed) storage on every call, and a fresh `create()` call does
        // not share state with an in-memory instance held elsewhere. Using the same on-disk
        // database here is what makes this test exercise the real read path rather than a second,
        // disconnected database.
        db = OrtDatabase.create(context)
    }

    @After
    fun resetProcessWideAvailability() {
        // AsrAvailability/VadAvailability/CaptureState/ShedStatus are all process-wide holders
        // (not persisted, by design — see their own doc comments); reset so this test's fixtures
        // never leak into another test.
        AsrAvailability.reset()
        VadAvailability.reset()
        CaptureState.idle(clearSession = true)
        ShedStatus.reset()
        ThermalStatus.reset()
        RigStatus.reset()
        StorageForecast.reset()
    }

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 currentStatus reads the real ASR and VAD availability, not a healthy default`(): Unit = runTest {
        db.sessionDao().insert(session())
        AsrAvailability.unavailable("no model at /data/models/asr")
        VadAvailability.stub("Silero model not installed")

        val view = ReaderPolling.currentStatus(context, "S1", startedAtWallMillis = 0L)

        assertTrue(view.asrStatusLabel.contains("no model at /data/models/asr"))
        assertTrue(view.vadStatusLabel.contains("Silero model not installed"))
        assertEquals(
            "No transcription model installed — transcripts will not appear",
            view.transcriptionUnavailableMessage,
        )
    }

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 currentStatus reports not started when the service has not set availability yet`(): Unit = runTest {
        db.sessionDao().insert(session())

        val view = ReaderPolling.currentStatus(context, "S1", startedAtWallMillis = 0L)

        assertTrue(view.asrStatusLabel.contains("not started"))
        assertFalse(view.asrStatusLabel.contains("available ("))
    }

    @Test
    @Requirement("FR-RUN-5")
    fun `FR_RUN_5 currentStatus reads the real shed level and backlog once capture has started`(): Unit = runTest {
        db.sessionDao().insert(session())
        CaptureState.capturing("S1")
        ShedStatus.update(level = 2, backlog = 7)

        val view = ReaderPolling.currentStatus(context, "S1", startedAtWallMillis = 0L)

        assertEquals(2, view.shedLevel)
        assertEquals("Level 2 — speaker identity paused", view.shedLevelLabel)
        assertEquals(7, view.backlog)
        assertTrue(view.backlogLabel.contains("7"))
    }

    @Test
    @Requirement("FR-RUN-5")
    fun `FR_RUN_5 currentStatus reports not measured before capture has ever started, never a fabricated zero`(): Unit =
        runTest {
            db.sessionDao().insert(session())

            val view = ReaderPolling.currentStatus(context, "S1", startedAtWallMillis = 0L)

            assertNull(view.shedLevel)
            assertEquals("Not measured", view.shedLevelLabel)
            assertNull(view.backlog)
            assertEquals("Not measured", view.backlogLabel)
        }

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 drawerBadges shows nothing at all while there is no active session`(): Unit = runTest {
        val badges = ReaderPolling.drawerBadges(context, sessionId = null)

        assertNull(badges.logCount)
        assertNull(badges.threadsCount)
        assertNull(badges.captureElapsedLabel)
    }

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 drawerBadges Log count is real, Threads is never a fabricated grouping`(): Unit = runTest {
        db.sessionDao().insert(session("S1"))
        db.transmissionDao().insert(transmission("TX1", samplePosition = 1L))
        db.transmissionDao().insert(transmission("TX2", samplePosition = 2L))

        val badges = ReaderPolling.drawerBadges(context, "S1")

        assertEquals(2, badges.logCount)
        // threadId is never populated by any prompt yet — a numeric count here would claim a
        // grouping that does not exist (constitution I).
        assertNull(badges.threadsCount)
    }

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 drawerBadges reports the running session's elapsed time from its real start`(): Unit = runTest {
        val realStart = SystemClock.wallMillis() - 402_000L // ~6m42s ago
        db.sessionDao().insert(session("S1").copy(startedAt = realStart))
        CaptureState.capturing("S1")

        val badges = ReaderPolling.drawerBadges(context, "S1")

        assertTrue(badges.captureElapsedLabel != null)
        assertTrue(badges.captureElapsedLabel!!.matches(Regex("""\d+:\d{2}(:\d{2})?""")))
    }

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 drawerBadges shows no elapsed time when nothing is capturing`(): Unit = runTest {
        db.sessionDao().insert(session("S1"))

        val badges = ReaderPolling.drawerBadges(context, "S1")

        assertNull(badges.captureElapsedLabel)
    }

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 drawerBadges shows no elapsed time when a different session is capturing`(): Unit = runTest {
        db.sessionDao().insert(session("S1"))
        CaptureState.capturing("S2")

        val badges = ReaderPolling.drawerBadges(context, "S1")

        assertNull(badges.captureElapsedLabel)
    }

    @Test
    @Requirement("R-172")
    fun `R_172 effectiveSessionId prefers the real live CaptureState session over the host's own`() {
        CaptureState.capturing("S2")
        assertEquals("S2", ReaderPolling.effectiveSessionId("S1"))
        assertEquals("S2", ReaderPolling.effectiveSessionId(null))
    }

    @Test
    @Requirement("R-172")
    fun `R_172 effectiveSessionId falls back to the host's own sessionId when nothing is capturing`() {
        assertEquals("S1", ReaderPolling.effectiveSessionId("S1"))
        assertNull(ReaderPolling.effectiveSessionId(null))
    }

    @Test
    @Requirement("R-175")
    fun `R_175 weakestOverLabel is the lowest recorded signal strength this session, honestly null otherwise`(): Unit =
        runTest {
            db.sessionDao().insert(session("S1"))
            db.transmissionDao().insert(transmission("S1-tx1", samplePosition = 1L, signalStrength = 7.0))
            db.transmissionDao().insert(transmission("S1-tx2", samplePosition = 2L, signalStrength = 2.0))
            db.transmissionDao().insert(transmission("S1-tx3", samplePosition = 3L, signalStrength = 5.0))

            assertEquals("S2", ReaderPolling.weakestOverLabel(context, "S1"))
        }

    @Test
    @Requirement("R-175")
    fun `R_175 weakestOverLabel is honestly null when no over this session recorded a signal strength`(): Unit =
        runTest {
            db.sessionDao().insert(session("S1"))
            db.transmissionDao().insert(transmission("S1-tx1", samplePosition = 1L, signalStrength = null))

            assertNull(ReaderPolling.weakestOverLabel(context, "S1"))
        }

    private fun session(id: String = "S1") = SessionEntity(
        id = id,
        startedAt = 0L,
        endedAt = null,
        profileId = null,
        deviceTier = null,
        appVersion = "test",
        terminationReason = null,
        sourceId = null,
        schemaVersion = OrtDatabase.SCHEMA_VERSION,
    )

    /** Bundles the attribution columns tests care about, keeping [transmission]'s own parameter count down. */
    private data class FixtureAttribution(
        val state: AttributionState = AttributionState.UNKNOWN,
        val stationId: String? = null,
        val confidence: Double? = null,
    )

    private fun transmission(
        id: String,
        sessionId: String = "S1",
        samplePosition: Long,
        startedAtUtc: Long = samplePosition,
        frequencyHz: Long? = 146_960_000L,
        signalStrength: Double? = 7.0,
        attribution: FixtureAttribution = FixtureAttribution(),
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
        frequencyHz = frequencyHz,
        frequencyProvenance = "measured",
        mode = null,
        signalStrength = signalStrength,
        channelName = null,
        voiceprintId = null,
        attributionState = attribution.state,
        stationId = attribution.stationId,
        attributionConfidence = attribution.confidence,
        attributionSourceTransmissionId = null,
        processingState = TransmissionState.CAPTURED,
        rejectionReason = null,
        samplePosition = samplePosition,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )

    private fun transcript(id: String, transmissionId: String, text: String, current: Boolean, createdAt: Long) =
        TranscriptEntity(
            id = id,
            transmissionId = transmissionId,
            pass = TranscriptPass.B,
            text = text,
            modelId = "distil-small.en",
            modelVersion = "1",
            quantization = null,
            decodeParams = null,
            noSpeechProb = null,
            confidence = 0.9,
            isCurrent = current,
            createdAt = createdAt,
        )

    @Test
    fun `FR_UI_1 transmissions come back newest first`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX-OLD", samplePosition = 1L))
        db.transmissionDao().insert(transmission("TX-NEW", samplePosition = 2L))

        val details = ReaderPolling.currentTransmissionDetails(context, "S1")

        assertEquals(listOf("TX-NEW", "TX-OLD"), details.map { it.id })
    }

    @Test
    fun `FR_UI_1 a transmission with no transcript row is honestly reported, not hidden`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", samplePosition = 1L))

        val details = ReaderPolling.currentTransmissionDetails(context, "S1")

        assertEquals(1, details.size)
        assertNull(details.single().currentTranscriptText)
    }

    @Test
    fun `FR_UI_1 a superseded transcript stays visible alongside the current one`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", samplePosition = 1L))
        db.transcriptDao().supersede(transcript("T1", "TX1", "first partial", current = true, createdAt = 1L))
        db.transcriptDao().supersede(transcript("T2", "TX1", "final transcript", current = true, createdAt = 2L))

        val detail = ReaderPolling.currentTransmissionDetails(context, "S1").single()

        assertEquals("final transcript", detail.currentTranscriptText)
        assertEquals(listOf("first partial"), detail.supersededTranscriptTexts)
    }

    @Test
    fun `a confirmed attribution round trips its station and confidence`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(
            transmission(
                "TX1",
                samplePosition = 1L,
                attribution = FixtureAttribution(AttributionState.CONFIRMED, "W7NPC", 0.95),
            ),
        )

        val detail = ReaderPolling.currentTransmissionDetails(context, "S1").single()

        assertEquals(AttributionState.CONFIRMED, detail.attribution.state)
        assertEquals("W7NPC", detail.attribution.stationId)
        assertEquals(0.95, detail.attribution.confidence)
    }

    @Test
    fun `a transmission with no retained audio file reports hasAudio false rather than crashing`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", samplePosition = 1L))

        val detail = ReaderPolling.currentTransmissionDetails(context, "S1").single()

        assertFalse(detail.hasAudio)
    }

    @Test
    fun `transmissionDetail looks up a single transmission by id`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", samplePosition = 1L))

        val detail = ReaderPolling.transmissionDetail(context, "TX1")

        assertEquals("TX1", detail?.id)
    }

    // R-round-two coordinator note: the stationDetail/frequencyDetail/listStationSummaries/
    // listFrequencySummaries tests (and their shared `hourTx` fixture) moved with the functions
    // they proved — WP8's `ui/data/StationPolling.kt` and its own `StationPollingTest` now own
    // this coverage.

    // ---------------------------------------------------------------------------------------
    // Capture status (ui-conformance-plan WP4, R-031/R-032/R-034/R-035/R-038).
    // ---------------------------------------------------------------------------------------

    @Test
    @Requirement("FR-UI-7")
    fun `R_032 captureStatus reads every real process-wide signal at once`(): Unit = runTest {
        db.sessionDao().insert(session("S1").copy(startedAt = 0L))
        db.transmissionDao().insert(transmission("TX1", samplePosition = 1L))
        CaptureState.capturing("S1")
        ShedStatus.update(level = 1, backlog = 3)
        ThermalStatus.update(osThermalStatus = ThermalStatus.THERMAL_STATUS_MODERATE, realTimeFactor = 0.5)
        RigStatus.connected("TH-D75A", emptyList())
        AsrAvailability.available("whisper-small")
        VadAvailability.real()

        val view = ReaderPolling.captureStatus(context, "S1")

        assertEquals("Capturing, warm", view.stateLabel)
        assertEquals("2 of 3", view.tier.value)
        assertEquals("TH-D75A", view.radio.value)
        assertEquals("1 captured", view.overs.value)
    }

    @Test
    @Requirement("FR-RUN-5")
    fun `R_032 captureStatus reports not measured before any shed reading, never a fabricated zero`(): Unit = runTest {
        db.sessionDao().insert(session("S1"))

        val view = ReaderPolling.captureStatus(context, "S1")

        assertEquals("Not measured", view.backlog.value)
        assertEquals("Not measured", view.tier.value)
    }

    // ---------------------------------------------------------------------------------------
    // Now home (ui-conformance-plan WP4, R-030/R-033/R-036/R-037).
    // ---------------------------------------------------------------------------------------

    @Test
    @Requirement("FR-UI-9")
    fun `R_030 nowViewState is Idle when the session id is not the one CaptureState says is live`(): Unit = runTest {
        db.sessionDao().insert(session("S1"))

        val state = ReaderPolling.nowViewState(context, "S1")

        assertTrue(state is NowViewState.Idle)
    }

    @Test
    @Requirement("FR-UI-9")
    fun `R_030 nowViewState is Active with the real session's own facts once CaptureState confirms it is live`(): Unit =
        runTest {
            db.sessionDao().insert(session("S1").copy(startedAt = 0L))
            db.transmissionDao().insert(
                transmission(
                    "TX1",
                    samplePosition = 1L,
                    attribution = FixtureAttribution(AttributionState.CONFIRMED, "W7NPC", 0.9),
                ),
            )
            CaptureState.capturing("S1")

            val state = ReaderPolling.nowViewState(context, "S1")

            assertTrue(state is NowViewState.Active)
            assertEquals("Overnight", (state as NowViewState.Active).sessionTitle)
            assertEquals(1, state.stations.rows.size)
        }

    @Test
    @Requirement("R-036")
    fun `R_036 idleNowViewState lists earlier nights and the last session summary`(): Unit = runTest {
        db.sessionDao().insert(session("S1").copy(startedAt = 0L, endedAt = 3_661_000L))
        db.transmissionDao().insert(transmission("TX1", sessionId = "S1", samplePosition = 1L))

        val state = ReaderPolling.nowViewState(context, null)

        assertTrue(state is NowViewState.Idle)
        val idle = state as NowViewState.Idle
        assertEquals(1, idle.earlierNights.size)
        assertTrue(idle.lastSessionSummaryLabel!!.contains("1 overs"))
    }

    @Test
    @Requirement("R-036")
    fun `R_036 a session with a real deviceTier makes Can get better appear, one without does not`(): Unit = runTest {
        db.sessionDao().insert(session("S1").copy(deviceTier = Tier.T1.name))
        db.transmissionDao().insert(transmission("TX1", sessionId = "S1", samplePosition = 1L))

        val state = ReaderPolling.nowViewState(context, null) as NowViewState.Idle

        assertTrue(state.canGetBetter != null)
        assertTrue(state.canGetBetter!!.headline.contains("1 overs"))
    }

    // ---------------------------------------------------------------------------------------
    // Starting capture (ui-conformance-plan WP4, R-036).
    // ---------------------------------------------------------------------------------------

    @Test
    fun `R_036 startCapture returns the already-live session id rather than minting a second one`() {
        CaptureState.capturing("S1")

        val id = ReaderPolling.startCapture(context)

        assertEquals("S1", id)
    }

    @Test
    fun `R_036 startCapture mints a fresh session id when nothing is capturing`() {
        val id = ReaderPolling.startCapture(context)

        assertTrue(id.isNotBlank())
    }
}
