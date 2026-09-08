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
        threadId: String? = null,
    ) = TransmissionEntity(
        id = id,
        sessionId = sessionId,
        threadId = threadId,
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
    fun `R_092 digest never invents a long-thread or absent-station item with insufficient history`(): Unit = runTest {
        db.sessionDao().insert(session("S1", startedAt = 0L, endedAt = 3_600_000L))
        db.transmissionDao().insert(transmission("TX1", "S1"))

        val digest = DigestPolling.digest(context, "S1")!!

        assert(digest.items.none { it.reason.contains("thread") })
        assert(digest.items.none { it.reason.contains("absent") })
    }

    @Test
    fun `FR_DIG_2a a thread well above the historical average is flagged, with a real usual-length sub-line`(): Unit =
        runTest {
            // Three short historical threads (1 over each) elsewhere, to establish a real average.
            db.sessionDao().insert(session("S-hist", startedAt = -1_000L, endedAt = -500L))
            db.transmissionDao().insert(transmission("TX-h1", "S-hist", threadId = "T-other-1"))
            db.transmissionDao().insert(transmission("TX-h2", "S-hist", threadId = "T-other-2"))
            db.transmissionDao().insert(transmission("TX-h3", "S-hist", threadId = "T-other-3"))

            db.sessionDao().insert(session("S1", startedAt = 0L, endedAt = 3_600_000L))
            (1..8).forEach { n ->
                db.transmissionDao().insert(
                    transmission(
                        "TX-long-$n",
                        "S1",
                        startedAtUtc = n * 1_000L,
                        stationId = "W7NPC",
                        threadId = "T-long",
                    ),
                )
            }

            val digest = DigestPolling.digest(context, "S1")!!

            val item = digest.items.firstOrNull { it.id == "thread-T-long" }
            assert(item != null) { "expected a long-thread item for T-long, got ${digest.items}" }
            assert(item!!.reason == "unusually long thread")
            // Real average (1+1+1+8)/4 = 2.75, rounded for display — the point is it is computed,
            // never a literal copied from an artboard.
            assert(item.subLine.contains("usual is 3 over")) {
                "expected the real rounded average, got ${item.subLine}"
            }
            assert(item.transmissionIds.size == 8)
        }

    @Test
    fun `FR_DIG_2a a short thread below the average is never flagged as long`(): Unit = runTest {
        db.sessionDao().insert(session("S1", startedAt = 0L, endedAt = 3_600_000L))
        db.transmissionDao().insert(transmission("TX1", "S1", threadId = "T-short"))
        db.transmissionDao().insert(transmission("TX2", "S1", startedAtUtc = 1_000L, threadId = "T-short"))

        val digest = DigestPolling.digest(context, "S1")!!

        assert(digest.items.none { it.id == "thread-T-short" })
    }

    @Test
    fun `FR_DIG_2a a station heard every same weekday but absent tonight is flagged, once history exists`(): Unit =
        runTest {
            val week = 604_800_000L
            // Three prior sessions on the exact same UTC weekday as tonight (epoch millis is a fixed
            // point in time, so a 7-day step never crosses a weekday boundary), each with W7REG heard.
            db.sessionDao().insert(session("S-w1", startedAt = -week, endedAt = -week + 1_000L))
            db.transmissionDao().insert(transmission("TX-w1", "S-w1", startedAtUtc = -week, stationId = "W7REG"))
            db.sessionDao().insert(session("S-w2", startedAt = -2 * week, endedAt = -2 * week + 1_000L))
            db.transmissionDao().insert(transmission("TX-w2", "S-w2", startedAtUtc = -2 * week, stationId = "W7REG"))
            db.sessionDao().insert(session("S-w3", startedAt = -3 * week, endedAt = -3 * week + 1_000L))
            db.transmissionDao().insert(transmission("TX-w3", "S-w3", startedAtUtc = -3 * week, stationId = "W7REG"))

            db.sessionDao().insert(session("S1", startedAt = 0L, endedAt = 3_600_000L))
            db.transmissionDao().insert(transmission("TX1", "S1", stationId = "W7NPC")) // W7REG absent tonight

            val digest = DigestPolling.digest(context, "S1")!!

            val item = digest.items.firstOrNull { it.id == "absent-W7REG" }
            assert(item != null) { "expected an absence item for W7REG, got ${digest.items}" }
            assert(item!!.reason == "a regular station absent")
            assert(item.headline.contains("3 weeks"))
        }

    @Test
    fun `FR_DIG_2a a station heard tonight is never flagged absent, even with matching weekday history`(): Unit =
        runTest {
            val week = 604_800_000L
            db.sessionDao().insert(session("S-w1", startedAt = -week, endedAt = -week + 1_000L))
            db.transmissionDao().insert(transmission("TX-w1", "S-w1", startedAtUtc = -week, stationId = "W7REG"))
            db.sessionDao().insert(session("S-w2", startedAt = -2 * week, endedAt = -2 * week + 1_000L))
            db.transmissionDao().insert(transmission("TX-w2", "S-w2", startedAtUtc = -2 * week, stationId = "W7REG"))
            db.sessionDao().insert(session("S-w3", startedAt = -3 * week, endedAt = -3 * week + 1_000L))
            db.transmissionDao().insert(transmission("TX-w3", "S-w3", startedAtUtc = -3 * week, stationId = "W7REG"))

            db.sessionDao().insert(session("S1", startedAt = 0L, endedAt = 3_600_000L))
            db.transmissionDao().insert(transmission("TX1", "S1", stationId = "W7REG"))

            val digest = DigestPolling.digest(context, "S1")!!

            assert(digest.items.none { it.id == "absent-W7REG" })
        }
}
