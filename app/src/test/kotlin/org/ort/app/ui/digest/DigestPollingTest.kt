package org.ort.app.ui.digest

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.HourActivityState
import org.ort.core.AttributionState
import org.ort.core.Tier
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.CaptureGapCause
import org.ort.data.entity.CaptureGapEntity
import org.ort.data.entity.ProseSummaryEntity
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TerminationReason
import org.ort.data.entity.TransmissionEntity
import org.ort.pipeline.digest.SharedPreferencesProseDigestSettingsStore
import org.ort.testing.Requirement
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
    @Requirement("R-449")
    fun `R_449 a continuously run session with no real gap at all is never hatched not-listening`(): Unit = runTest {
        // A 3-hour session, one over per hour, no recorded gap anywhere — every bucket genuinely
        // covered, none of them ever hatched.
        db.sessionDao().insert(session("S1", startedAt = 0L, endedAt = 3 * 3_600_000L))
        db.transmissionDao().insert(transmission("TX1", "S1", startedAtUtc = 100_000L))
        db.transmissionDao().insert(transmission("TX2", "S1", startedAtUtc = 3_700_000L))
        db.transmissionDao().insert(transmission("TX3", "S1", startedAtUtc = 7_300_000L))

        val detail = DigestPolling.sessionDetail(context, "S1")!!

        // Old (R-449 register bug): `ActivityPatternMapper.buildPattern`'s hour-of-day folding
        // hatched nearly every one of the 24 fixed buckets NOT_LISTENING, for a session that ran
        // continuously — this session's own real span is 3 hours, never 24, and none of them were
        // genuinely un-listened.
        assert(detail.coverage.size == 3) {
            "expected exactly 3 elapsed-hour buckets, got ${detail.coverage.size}"
        }
        assert(detail.coverage.all { it.state == HourActivityState.HEARD }) {
            "every bucket here had a real over land in it and no gap at all, expected all HEARD, got " +
                "${detail.coverage.map { it.state }}"
        }
    }

    @Test
    @Requirement("R-540")
    fun `R_540 a real recorded gap hatches its own hour, one span, even with real overs elsewhere in it`(): Unit =
        runTest {
            // R-540 (register, Reviewer D, tour run 3): the real bug — a 3 h session, real overs in
            // every hour (so every bucket previously read solid HEARD, hiding the gap entirely), plus
            // one real, recorded 22-minute gap inside the first hour. The board hatches every real gap
            // inside its hour unconditionally — real overs elsewhere in that same hour never hide it.
            db.sessionDao().insert(session("S1", startedAt = 0L, endedAt = 3 * 3_600_000L))
            db.transmissionDao().insert(transmission("TX1", "S1", startedAtUtc = 100_000L))
            db.transmissionDao().insert(transmission("TX2", "S1", startedAtUtc = 3_700_000L))
            db.transmissionDao().insert(transmission("TX3", "S1", startedAtUtc = 7_300_000L))
            val gapMillis = 22 * 60_000L
            db.captureGapDao().insert(
                CaptureGapEntity(
                    id = "G1",
                    sessionId = "S1",
                    startedAt = 1_500_000L,
                    endedAt = 1_500_000L + gapMillis,
                    cause = CaptureGapCause.ROUTE_CHANGE,
                    recoveredAutomatically = true,
                ),
            )

            val detail = DigestPolling.sessionDetail(context, "S1")!!

            assert(detail.coverage.size == 3) {
                "expected exactly 3 elapsed-hour buckets, got ${detail.coverage.size}"
            }
            // One hatched span, at the right fraction: the gap falls entirely inside elapsed hour 0
            // (index 0 of 3) — that bucket alone reads NOT_LISTENING, the other two stay HEARD.
            val states = detail.coverage.map { it.state }
            val expected = listOf(HourActivityState.NOT_LISTENING, HourActivityState.HEARD, HourActivityState.HEARD)
            assert(states == expected) {
                "expected one hatched span at bucket 0 of 3 (where the real gap falls), got $states"
            }
        }

    @Test
    @Requirement("R-449")
    fun `R_449 any real recorded gap overlap, however small, hatches its own hour`(): Unit = runTest {
        db.sessionDao().insert(session("S1", startedAt = 0L, endedAt = 3_600_000L))
        // No over at all this hour, and a real gap covering only 1 of the 60 minutes — still a real,
        // recorded gap, so R-540's own unconditional rule still hatches it honestly.
        db.captureGapDao().insert(
            CaptureGapEntity(
                id = "G1",
                sessionId = "S1",
                startedAt = 0L,
                endedAt = 60_000L,
                cause = CaptureGapCause.CALL,
                recoveredAutomatically = true,
            ),
        )

        val detail = DigestPolling.sessionDetail(context, "S1")!!

        assert(detail.coverage.size == 1)
        assert(detail.coverage.single().state == HourActivityState.NOT_LISTENING) {
            "expected the one bucket, overlapping a real gap at all, to hatch, got ${detail.coverage.single().state}"
        }
    }

    @Test
    @Requirement("R-450")
    fun `R_450 tier reads the real processedTier, never the always-null deviceTier`(): Unit = runTest {
        db.sessionDao().insert(session("S1", startedAt = 0L, endedAt = 3_600_000L))
        db.transmissionDao().insert(transmission("TX1", "S1").copy(processedTier = Tier.T2))
        db.transmissionDao().insert(
            transmission("TX2", "S1", startedAtUtc = 1_000L).copy(processedTier = Tier.T2),
        )

        val detail = DigestPolling.sessionDetail(context, "S1")!!

        assert(detail.tierLabel == "tier 2") { "expected the real processedTier, got ${detail.tierLabel}" }
        assert(detail.tierLabel != "current") { "current was the old, always-fake placeholder" }
    }

    @Test
    @Requirement("R-450")
    fun `R_450 nothing reprocessed yet honestly reads not yet reprocessed, never a fabricated tier`(): Unit = runTest {
        db.sessionDao().insert(session("S1", startedAt = 0L, endedAt = 3_600_000L))
        db.transmissionDao().insert(transmission("TX1", "S1"))

        val detail = DigestPolling.sessionDetail(context, "S1")!!

        assert(detail.tierLabel == "not yet reprocessed") { "got ${detail.tierLabel}" }
    }

    @Test
    @Requirement("R-450")
    fun `R_450 mixed processedTier names the mix, never picks one tier arbitrarily`(): Unit = runTest {
        db.sessionDao().insert(session("S1", startedAt = 0L, endedAt = 3_600_000L))
        db.transmissionDao().insert(transmission("TX1", "S1").copy(processedTier = Tier.T1))
        db.transmissionDao().insert(
            transmission("TX2", "S1", startedAtUtc = 1_000L).copy(processedTier = Tier.T3),
        )

        val detail = DigestPolling.sessionDetail(context, "S1")!!

        assert(detail.tierLabel == "mixed · up to tier 3") { "got ${detail.tierLabel}" }
    }

    @Test
    @Requirement("R-450")
    fun `R_450 Input honestly names no per-session route is tracked, never the old Settings redirect`(): Unit =
        runTest {
            db.sessionDao().insert(session("S1", startedAt = 0L, endedAt = 3_600_000L))
            db.transmissionDao().insert(transmission("TX1", "S1"))

            val detail = DigestPolling.sessionDetail(context, "S1")!!

            assert(detail.inputLabel == "not tracked per session in this build") { "got ${detail.inputLabel}" }
        }

    // -----------------------------------------------------------------------------------------
    // E2-G03 (DG04, FR-CAP-13): Mode/Input/Rig-link fact rows from the session's own v7 columns —
    // R-450's deviation is retired only for a session that actually carries the facts.
    // -----------------------------------------------------------------------------------------

    @Test
    @Requirement("E2-G03", "FR-CAP-13")
    fun `E2_G03 a USB-radio session names its mode, input and rig link from the v7 columns`(): Unit = runTest {
        db.sessionDao().insert(
            session("S1", startedAt = 0L, endedAt = 3_600_000L).copy(
                captureMode = "USB_RADIO",
                audioRouteKind = "USB",
                audioRouteLabel = "USB Audio Device",
                rigTransport = "USB_SERIAL",
            ),
        )
        db.transmissionDao().insert(transmission("TX1", "S1"))

        val detail = DigestPolling.sessionDetail(context, "S1")!!

        assert(detail.modeLabel == "USB-connected radio · audio by cable") { "got ${detail.modeLabel}" }
        assert(detail.inputLabel.contains("USB Audio Device")) { "got ${detail.inputLabel}" }
        assert(detail.inputLabel.contains("radio audio")) { "got ${detail.inputLabel}" }
        assert(!detail.inputLabel.contains("room audio")) { "got ${detail.inputLabel}" }
        assert(detail.rigLinkLabel == "USB serial") { "got ${detail.rigLinkLabel}" }
    }

    @Test
    @Requirement("E2-G03", "FR-CAP-11")
    fun `E2_G03 a Bluetooth-audio session names its profile on the Input row`(): Unit = runTest {
        db.sessionDao().insert(
            session("BT-1", startedAt = 0L, endedAt = 3_600_000L).copy(
                captureMode = "BLUETOOTH_RADIO",
                audioRouteKind = "BLUETOOTH_SCO",
                audioRouteLabel = "Handheld BT",
                bluetoothProfile = "HFP_MSBC",
                rigTransport = "BLUETOOTH_SPP",
            ),
        )
        db.transmissionDao().insert(transmission("TX1", "BT-1"))

        val detail = DigestPolling.sessionDetail(context, "BT-1")!!

        assert(detail.inputLabel.contains("Handheld BT")) { "got ${detail.inputLabel}" }
        assert(detail.inputLabel.contains("wideband")) { "got ${detail.inputLabel}" }
        assert(detail.rigLinkLabel == "Bluetooth SPP") { "got ${detail.rigLinkLabel}" }
    }

    @Test
    @Requirement("E2-G03", "FR-CAP-10")
    fun `E2_G03 a local-microphone session names room audio and no rig, never a fabricated transport`(): Unit =
        runTest {
            db.sessionDao().insert(
                session("ROOM-1", startedAt = 0L, endedAt = 3_600_000L).copy(
                    captureMode = "LOCAL_MICROPHONE",
                    audioRouteKind = "BUILT_IN_MIC",
                    audioRouteLabel = "Built-in Microphone",
                ),
            )
            db.transmissionDao().insert(transmission("TX1", "ROOM-1"))

            val detail = DigestPolling.sessionDetail(context, "ROOM-1")!!

            assert(detail.inputLabel.contains("room audio")) { "got ${detail.inputLabel}" }
            assert(detail.rigLinkLabel == "no rig this session") { "got ${detail.rigLinkLabel}" }
        }

    @Test
    @Requirement("E2-G03", "R-450")
    fun `E2_G03 a pre-v7 session with no tracked columns keeps R-450's honest not-tracked line`(): Unit = runTest {
        db.sessionDao().insert(session("S1", startedAt = 0L, endedAt = 3_600_000L))
        db.transmissionDao().insert(transmission("TX1", "S1"))

        val detail = DigestPolling.sessionDetail(context, "S1")!!

        assert(detail.modeLabel == "not tracked per session in this build") { "got ${detail.modeLabel}" }
        assert(detail.rigLinkLabel == "not tracked per session in this build") { "got ${detail.rigLinkLabel}" }
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

    // -----------------------------------------------------------------------------------------
    // E2-G07 (DG05, FR-DIG-3/6/11): the "In their words" prose section.
    // -----------------------------------------------------------------------------------------

    private fun proseSettings() = SharedPreferencesProseDigestSettingsStore(context)

    @Test
    @Requirement("E2-G07", "FR-DIG-6")
    fun `E2_G07 a stored summary for this session's thread renders as a prose card`(): Unit = runTest {
        proseSettings().setEnabled(true)
        db.sessionDao().insert(session("S1", startedAt = 0L, endedAt = 3_600_000L))
        val overOneAt = 2 * 3_600_000L + 17 * 60_000L // 02:17 UTC
        val overTwoAt = 2 * 3_600_000L + 41 * 60_000L // 02:41 UTC
        db.transmissionDao().insert(
            transmission("TX1", "S1", startedAtUtc = overOneAt, stationId = "WA7HJR", threadId = "T1"),
        )
        db.transmissionDao().insert(
            transmission("TX2", "S1", startedAtUtc = overTwoAt, stationId = "WA7HJR", threadId = "T1"),
        )
        db.proseSummaryDao().upsert(
            ProseSummaryEntity(
                threadId = "T1",
                text = "Reported running low power from the park.",
                sourceTransmissionIds = listOf("TX1", "TX2"),
                generatedAtMillis = 3_600_000L * 3,
                modelId = "gemma3-1b-it-int4",
            ),
        )

        val digest = DigestPolling.digest(context, "S1")!!

        val prose = digest.prose
        assert(prose != null) { "expected a prose section" }
        assert(prose!!.cards.size == 1) { "got ${prose.cards.size}" }
        val card = prose.cards.single()
        assert(card.text == "Reported running low power from the park.")
        assert(card.oversRangeLabel.contains("02:17")) { "got ${card.oversRangeLabel}" }
        assert(card.oversRangeLabel.contains("02:41")) { "got ${card.oversRangeLabel}" }
        assert(card.fromMillis == overOneAt)
        assert(card.toMillis == overTwoAt)
    }

    @Test
    @Requirement("E2-G07", "FR-DIG-3a")
    fun `E2_G07 the prose section is absent entirely when disabled, the rest of the digest is unchanged`(): Unit =
        runTest {
            db.sessionDao().insert(session("S1", startedAt = 0L, endedAt = 3_600_000L))
            db.transmissionDao().insert(transmission("TX1", "S1", stationId = "WA7HJR", threadId = "T1"))
            db.proseSummaryDao().upsert(
                ProseSummaryEntity(
                    threadId = "T1",
                    text = "prose",
                    sourceTransmissionIds = listOf("TX1"),
                    generatedAtMillis = 0L,
                    modelId = "gemma3-1b-it-int4",
                ),
            )

            proseSettings().setEnabled(true)
            val withProse = DigestPolling.digest(context, "S1")!!
            proseSettings().setEnabled(false)
            val withoutProse = DigestPolling.digest(context, "S1")!!

            assert(withProse.prose != null)
            assert(withoutProse.prose == null)
            // FR-DIG-3a: the deterministic digest is untouched either way.
            assert(withProse.copy(prose = null) == withoutProse)
        }

    @Test
    @Requirement("E2-G07", "FR-DIG-3a")
    fun `E2_G07 the prose section is absent when enabled but nothing has been generated yet`(): Unit = runTest {
        proseSettings().setEnabled(true)
        db.sessionDao().insert(session("S1", startedAt = 0L, endedAt = 3_600_000L))
        db.transmissionDao().insert(transmission("TX1", "S1", stationId = "WA7HJR", threadId = "T1"))

        val digest = DigestPolling.digest(context, "S1")!!

        assert(digest.prose == null)
    }

    @Test
    @Requirement("E2-G07")
    fun `E2_G07 a summary belonging to a different session's thread never leaks in`(): Unit = runTest {
        proseSettings().setEnabled(true)
        db.sessionDao().insert(session("S1", startedAt = 0L, endedAt = 3_600_000L))
        db.transmissionDao().insert(transmission("TX1", "S1", stationId = "WA7HJR", threadId = null))
        db.proseSummaryDao().upsert(
            ProseSummaryEntity(
                threadId = "T-OTHER",
                text = "prose from an unrelated thread",
                sourceTransmissionIds = listOf("TX-OTHER"),
                generatedAtMillis = 0L,
                modelId = "gemma3-1b-it-int4",
            ),
        )

        val digest = DigestPolling.digest(context, "S1")!!

        assert(digest.prose == null)
    }
}
