package org.ort.app.ui.digest

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.CaptureGapCause
import org.ort.data.entity.CaptureGapEntity
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TerminationReason
import org.ort.data.entity.TransmissionEntity
import org.robolectric.RobolectricTestRunner

/**
 * R-092 (register, FR-DIG-1..6, FR-RUN-11/12/16): [DigestPolling] reads real sessions, gaps and
 * transmissions — every fact asserted here is computed from a row inserted in this test, never a
 * literal copied from an artboard.
 */
@RunWith(RobolectricTestRunner::class)
class DigestPollingTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: OrtDatabase

    @Before
    fun setUp() {
        db = OrtDatabase.create(context)
    }

    private fun session(id: String, startedAt: Long, endedAt: Long?, termination: TerminationReason? = null) =
        SessionEntity(
            id = id,
            startedAt = startedAt,
            endedAt = endedAt,
            profileId = null,
            deviceTier = null,
            appVersion = "test",
            terminationReason = termination,
            sourceId = null,
            schemaVersion = OrtDatabase.SCHEMA_VERSION,
        )

    private fun transmission(
        id: String,
        sessionId: String,
        startedAtUtc: Long = 0L,
        frequencyHz: Long? = 146_960_000L,
        stationId: String? = null,
        state: AttributionState = AttributionState.UNKNOWN,
        processingState: TransmissionState = TransmissionState.COMPLETE,
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
        signalStrength = 7.0,
        channelName = null,
        voiceprintId = null,
        attributionState = state,
        stationId = stationId,
        attributionConfidence = null,
        attributionSourceTransmissionId = null,
        processingState = processingState,
        rejectionReason = null,
        samplePosition = 0L,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )

    @Test
    fun `R_092 sessions reports real over and station counts`(): Unit = runTest {
        db.sessionDao().insert(session("S1", startedAt = 0L, endedAt = 3_600_000L))
        db.transmissionDao().insert(transmission("TX1", "S1", stationId = "W7NPC"))
        db.transmissionDao().insert(transmission("TX2", "S1", stationId = "W7NPC"))

        val result = DigestPolling.sessions(context)

        assert(result.sessions.size == 1)
        val row = result.sessions.single()
        assert(row.overCount == 2) { "expected 2 overs, got ${row.overCount}" }
        assert(row.stationCount == 1) { "expected 1 distinct station, got ${row.stationCount}" }
    }

    @Test
    fun `R_092 an unclean ended session names the real termination reason, a clean one names none`(): Unit = runTest {
        db.sessionDao().insert(session("S1", startedAt = 0L, endedAt = 1_000L, termination = TerminationReason.KILLED))
        db.sessionDao().insert(session("S2", startedAt = 0L, endedAt = 1_000L, termination = TerminationReason.USER))

        val result = DigestPolling.sessions(context)

        val unclean = result.sessions.first { it.id == "S1" }
        val clean = result.sessions.first { it.id == "S2" }
        assert(unclean.uncleanEndLabel != null)
        assert(clean.uncleanEndLabel == null)
    }

    @Test
    fun `R_092 session detail counts real gaps and rejected overs`(): Unit = runTest {
        db.sessionDao().insert(session("S1", startedAt = 0L, endedAt = 3_600_000L))
        db.transmissionDao().insert(transmission("TX1", "S1", processingState = TransmissionState.REJECTED))
        db.transmissionDao().insert(transmission("TX2", "S1"))
        db.captureGapDao().insert(
            CaptureGapEntity(
                id = "G1",
                sessionId = "S1",
                startedAt = 60_000L,
                endedAt = 90_000L,
                cause = CaptureGapCause.CALL,
                recoveredAutomatically = true,
            ),
        )

        val detail = DigestPolling.sessionDetail(context, "S1")!!

        assert(detail.overCount == 2)
        assert(detail.rejectedCount == 1)
        assert(detail.gaps.size == 1)
        assert(detail.gaps.single().causeLabel == "incoming call took the microphone")
    }

    @Test
    fun `R_092 digest surfaces a station heard for the first time this session, from real history`(): Unit = runTest {
        db.sessionDao().insert(session("S0", startedAt = -10_000L, endedAt = -5_000L))
        db.transmissionDao().insert(transmission("TX0", "S0", startedAtUtc = -10_000L, stationId = "N7XYZ"))
        db.sessionDao().insert(session("S1", startedAt = 0L, endedAt = 3_600_000L))
        db.transmissionDao().insert(transmission("TX1", "S1", startedAtUtc = 100L, stationId = "W7NEW"))

        val digest = DigestPolling.digest(context, "S1")!!

        assert(digest.items.any { it.id == "first-W7NEW" }) {
            "expected a first-heard item for W7NEW, got ${digest.items}"
        }
        assert(digest.items.none { it.id == "first-N7XYZ" }) {
            "N7XYZ was heard in an earlier session, must not be first-heard"
        }
    }

    @Test
    fun `R_092 digest never invents a long-thread or absent-station item`(): Unit = runTest {
        db.sessionDao().insert(session("S1", startedAt = 0L, endedAt = 3_600_000L))
        db.transmissionDao().insert(transmission("TX1", "S1"))

        val digest = DigestPolling.digest(context, "S1")!!

        assert(digest.items.none { it.reason.contains("thread") })
        assert(digest.items.none { it.reason.contains("absent") })
    }
}
