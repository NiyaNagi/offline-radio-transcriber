package org.ort.app.ui.digest

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
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
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.digest.SharedPreferencesProseDigestSettingsStore
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * R-092 (register, FR-DIG-1..6, FR-RUN-11/12/16): [DigestPolling] reads real sessions, gaps and
 * transmissions — every fact asserted here is computed from a row inserted in this test, never a
 * literal copied from an artboard.
 */
@RunWith(RobolectricTestRunner::class)
// This session's own R-824/R-825/R-833/R-834/R-844 cases pushed this file over detekt's LargeClass
// threshold — kept as one class deliberately (this file's own doc comment: every DigestPolling
// fact this package's tests own lives here), the same call FailureScreensTest.kt already makes.
@Suppress("LargeClass")
class DigestPollingTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: OrtDatabase

    @Before
    fun setUp() {
        db = OrtDatabase.create(context)
    }

    // R-824/R-833 (register): both new cases below set these process-wide holders — reset here so
    // no later test in this shared JVM worker ever reads a stale `capturing`/`Connected` state left
    // behind by this class's own tests (the same discipline `LogPollingTest`'s own `@After` already
    // follows for `RigStatus`).
    @After
    fun resetProcessWideHolders() {
        CaptureState.idle(clearSession = true)
        RigStatus.reset()
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
    // Mode/Input/Rig-link fact rows from the session's own v7 columns (checklist row E2-G03,
    // DG04, FR-CAP-13) — R-450's deviation is retired only for a session that actually carries
    // the facts.
    // -----------------------------------------------------------------------------------------

    @Test
    @Requirement("FR-CAP-13")
    fun `FR_CAP_13 a USB-radio session names its mode, input and rig link from the v7 columns`(): Unit = runTest {
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
        // E2-A07: a pre-v10-shaped row (rigDescriptorId/audioRouteVerified/audioNativeRateHz all
        // null, same as every fixture above v7 and below v10) must never fabricate "verified" or
        // a native rate it does not have, and the rig link stays the transport alone.
        assert(!detail.inputLabel.contains("verified")) { "got ${detail.inputLabel}" }
        assert(!detail.inputLabel.contains("kHz")) { "got ${detail.inputLabel}" }
    }

    @Test
    @Requirement("FR-CAP-13")
    fun `FR_CAP_13 a session with rig descriptor id, route-verified and native rate names all three`() = runTest {
        db.sessionDao().insert(
            session("S2", startedAt = 0L, endedAt = 3_600_000L).copy(
                captureMode = "USB_RADIO",
                audioRouteKind = "USB",
                audioRouteLabel = "USB Audio Device",
                rigTransport = "USB_SERIAL",
                rigDescriptorId = "kenwood-thd75a",
                audioRouteVerified = true,
                audioNativeRateHz = 48_000,
            ),
        )
        db.transmissionDao().insert(transmission("TX1", "S2"))

        val detail = DigestPolling.sessionDetail(context, "S2")!!

        assert(detail.inputLabel.contains("verified")) { "got ${detail.inputLabel}" }
        assert(detail.inputLabel.contains("48 kHz")) { "got ${detail.inputLabel}" }
        assert(detail.inputLabel.contains("radio audio")) { "got ${detail.inputLabel}" }
        assert(detail.rigLinkLabel == "Kenwood TH-D75A · USB serial") { "got ${detail.rigLinkLabel}" }
    }

    @Test
    @Requirement("FR-CAP-13")
    fun `FR_CAP_13 audioRouteVerified false reads not verified, never silently dropped`(): Unit = runTest {
        db.sessionDao().insert(
            session("S3", startedAt = 0L, endedAt = 3_600_000L).copy(
                captureMode = "USB_RADIO",
                audioRouteKind = "USB",
                audioRouteLabel = "USB Audio Device",
                rigTransport = "USB_SERIAL",
                audioRouteVerified = false,
            ),
        )
        db.transmissionDao().insert(transmission("TX1", "S3"))

        val detail = DigestPolling.sessionDetail(context, "S3")!!

        assert(detail.inputLabel.contains("not verified")) { "got ${detail.inputLabel}" }
    }

    @Test
    @Requirement("FR-CAP-11")
    fun `FR_CAP_11 a Bluetooth-audio session names its profile on the Input row`(): Unit = runTest {
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
        // R-834 (register): the real HFP codec name, not the retired "(wideband)" parenthetical.
        assert(detail.inputLabel.contains("HFP mSBC")) { "got ${detail.inputLabel}" }
        assert(detail.rigLinkLabel == "Bluetooth SPP") { "got ${detail.rigLinkLabel}" }
    }

    // R-825/R-834 (register, design/spec): the Input row's own device/type restatement bug —
    // "Built-in microphone · built-in mic · room audio" and "Bluetooth headset · Bluetooth ·
    // radio audio · Bluetooth (wideband)", the exact captures the register cites — repeated the
    // same fact two or three times over. Reproduced here with the register's own exact seeded
    // labels, asserting the *complete* resulting string, not a substring, so a partial fix (only
    // one of the two/three repeats removed) would still fail this.

    @Test
    @Requirement("FR-CAP-10")
    fun `R_825 a built-in-mic session never restates the label as a redundant type clause`(): Unit = runTest {
        db.sessionDao().insert(
            session("ROOM-1", startedAt = 0L, endedAt = 3_600_000L).copy(
                captureMode = "LOCAL_MICROPHONE",
                audioRouteKind = "BUILT_IN_MIC",
                audioRouteLabel = "Built-in microphone",
            ),
        )
        db.transmissionDao().insert(transmission("TX1", "ROOM-1"))

        val detail = DigestPolling.sessionDetail(context, "ROOM-1")!!

        assert(detail.inputLabel == "Built-in microphone · room audio") { "got ${detail.inputLabel}" }
    }

    @Test
    @Requirement("FR-CAP-11")
    fun `R_834 a Bluetooth-audio session never repeats the word Bluetooth three times`(): Unit = runTest {
        db.sessionDao().insert(
            session("BT-2", startedAt = 0L, endedAt = 3_600_000L).copy(
                captureMode = "BLUETOOTH_RADIO",
                audioRouteKind = "BLUETOOTH_SCO",
                audioRouteLabel = "Bluetooth headset",
                bluetoothProfile = "HFP_MSBC",
            ),
        )
        db.transmissionDao().insert(transmission("TX1", "BT-2"))

        val detail = DigestPolling.sessionDetail(context, "BT-2")!!

        assert(detail.inputLabel == "Bluetooth headset · radio audio · HFP mSBC") { "got ${detail.inputLabel}" }
        val bluetoothOccurrences = Regex("Bluetooth", RegexOption.IGNORE_CASE).findAll(detail.inputLabel).count()
        assert(bluetoothOccurrences == 1) {
            "expected exactly one 'Bluetooth', got $bluetoothOccurrences in ${detail.inputLabel}"
        }
    }

    @Test
    @Requirement("FR-CAP-11")
    fun `R_834 HFP CVSD and an unrecognised profile read their own real names`(): Unit = runTest {
        db.sessionDao().insert(
            session("BT-3", startedAt = 0L, endedAt = 3_600_000L).copy(
                captureMode = "BLUETOOTH_RADIO",
                audioRouteKind = "BLUETOOTH_SCO",
                audioRouteLabel = "Handheld BT",
                bluetoothProfile = "HFP_CVSD",
            ),
        )
        db.transmissionDao().insert(transmission("TX1", "BT-3"))
        db.sessionDao().insert(
            session("BT-4", startedAt = 0L, endedAt = 3_600_000L).copy(
                captureMode = "BLUETOOTH_RADIO",
                audioRouteKind = "BLUETOOTH_SCO",
                audioRouteLabel = "Handheld BT",
                bluetoothProfile = "UNKNOWN",
            ),
        )
        db.transmissionDao().insert(transmission("TX2", "BT-4"))

        val cvsd = DigestPolling.sessionDetail(context, "BT-3")!!
        val unknown = DigestPolling.sessionDetail(context, "BT-4")!!

        assert(cvsd.inputLabel.endsWith("HFP CVSD")) { "got ${cvsd.inputLabel}" }
        assert(unknown.inputLabel.endsWith("profile not reported")) { "got ${unknown.inputLabel}" }
    }

    @Test
    @Requirement("FR-CAP-13")
    fun `R_825 a device label that does not name its own kind keeps the type clause`(): Unit = runTest {
        // "Handheld BT" names neither "USB" nor "Bluetooth" literally -- confirms the dedup rule
        // only ever drops the type when the label genuinely already says it, never unconditionally.
        db.sessionDao().insert(
            session("BT-5", startedAt = 0L, endedAt = 3_600_000L).copy(
                captureMode = "BLUETOOTH_RADIO",
                audioRouteKind = "BLUETOOTH_SCO",
                audioRouteLabel = "Handheld BT",
            ),
        )
        db.transmissionDao().insert(transmission("TX1", "BT-5"))

        val detail = DigestPolling.sessionDetail(context, "BT-5")!!

        assert(detail.inputLabel == "Handheld BT · Bluetooth · radio audio") { "got ${detail.inputLabel}" }
    }

    @Test
    @Requirement("FR-CAP-10")
    fun `FR_CAP_10 a local-microphone session names room audio and no rig, never a fabricated transport`(): Unit =
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
    @Requirement("FR-CAP-13", "R-450")
    fun `FR_CAP_13 a pre-v7 session with no tracked columns keeps R-450's honest not-tracked line`(): Unit = runTest {
        db.sessionDao().insert(session("S1", startedAt = 0L, endedAt = 3_600_000L))
        db.transmissionDao().insert(transmission("TX1", "S1"))

        val detail = DigestPolling.sessionDetail(context, "S1")!!

        assert(detail.modeLabel == "not tracked per session in this build") { "got ${detail.modeLabel}" }
        assert(detail.rigLinkLabel == "not tracked per session in this build") { "got ${detail.rigLinkLabel}" }
    }

    // -----------------------------------------------------------------------------------------
    // R-824 (register, halt): a session CaptureState itself reports as currently capturing must
    // carry live = true — the header's own "never claim an end/clean-termination" fix rests on it.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `R_824 the currently capturing session reads live`(): Unit = runTest {
        db.sessionDao().insert(session("LIVE-1", startedAt = 0L, endedAt = null))
        db.transmissionDao().insert(transmission("TX1", "LIVE-1"))
        CaptureState.capturing("LIVE-1")

        val detail = DigestPolling.sessionDetail(context, "LIVE-1")!!

        assert(detail.live) { "expected live=true for the session CaptureState reports as capturing" }
    }

    @Test
    fun `R_824 an ended session, or one CaptureState is not capturing, reads not live`(): Unit = runTest {
        db.sessionDao().insert(session("ENDED-1", startedAt = 0L, endedAt = 3_600_000L))
        db.transmissionDao().insert(transmission("TX1", "ENDED-1"))
        // CaptureState is idle by default (this class's own @After resets it after every test).

        val detail = DigestPolling.sessionDetail(context, "ENDED-1")!!

        assert(!detail.live) { "expected live=false for an ended session with nothing capturing" }
    }

    @Test
    fun `R_824 a session CaptureState reports live for a different id reads not live`(): Unit = runTest {
        db.sessionDao().insert(session("OTHER-1", startedAt = 0L, endedAt = null))
        db.transmissionDao().insert(transmission("TX1", "OTHER-1"))
        CaptureState.capturing("SOME-OTHER-SESSION")

        val detail = DigestPolling.sessionDetail(context, "OTHER-1")!!

        assert(!detail.live) { "expected live=false when a different session id is the one actually capturing" }
    }

    // -----------------------------------------------------------------------------------------
    // R-833 (register, spec): the Rig link row leads with the rig's own real name for a currently
    // live session, read off RigStatus — the same "prefer live" pattern N04/F9 already use. A past
    // session has no persisted rig-descriptor id to fall back to, so it stays the transport alone.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `R_833 a live Bluetooth-rig session leads the rig-link row with the rig's real name`(): Unit = runTest {
        db.sessionDao().insert(
            session("BT-LIVE", startedAt = 0L, endedAt = null).copy(
                captureMode = "BLUETOOTH_RADIO",
                audioRouteKind = "BLUETOOTH_SCO",
                audioRouteLabel = "Handheld BT",
                rigTransport = "BLUETOOTH_SPP",
            ),
        )
        db.transmissionDao().insert(transmission("TX1", "BT-LIVE"))
        CaptureState.capturing("BT-LIVE")
        RigStatus.connected(
            descriptor = "TH-D75A",
            bands = emptyList(),
            transportKind = org.ort.rig.RigTransportKind.BLUETOOTH_SPP,
        )

        val detail = DigestPolling.sessionDetail(context, "BT-LIVE")!!

        assert(detail.rigLinkLabel == "TH-D75A · Bluetooth SPP") { "got ${detail.rigLinkLabel}" }
    }

    @Test
    fun `R_833 an ended session never carries a rig name, only the transport`(): Unit = runTest {
        db.sessionDao().insert(
            session("BT-ENDED", startedAt = 0L, endedAt = 3_600_000L).copy(
                captureMode = "BLUETOOTH_RADIO",
                audioRouteKind = "BLUETOOTH_SCO",
                audioRouteLabel = "Handheld BT",
                rigTransport = "BLUETOOTH_SPP",
            ),
        )
        db.transmissionDao().insert(transmission("TX1", "BT-ENDED"))
        // A live RigStatus reading, even if present, must never leak into a past session's row —
        // this is a different, unrelated session's own review.
        RigStatus.connected(
            descriptor = "TH-D75A",
            bands = emptyList(),
            transportKind = org.ort.rig.RigTransportKind.BLUETOOTH_SPP,
        )

        val detail = DigestPolling.sessionDetail(context, "BT-ENDED")!!

        assert(detail.rigLinkLabel == "Bluetooth SPP") { "got ${detail.rigLinkLabel}" }
    }

    @Test
    fun `R_833 a live session whose RigStatus moved to a different transport never mismatches`(): Unit = runTest {
        db.sessionDao().insert(
            session("USB-LIVE", startedAt = 0L, endedAt = null).copy(
                captureMode = "USB_RADIO",
                audioRouteKind = "USB",
                audioRouteLabel = "USB Audio Device",
                rigTransport = "USB_SERIAL",
            ),
        )
        db.transmissionDao().insert(transmission("TX1", "USB-LIVE"))
        CaptureState.capturing("USB-LIVE")
        // RigStatus itself now reports a Bluetooth rig -- a reconfiguration mid-review, or a
        // second session's own signal bleeding through a stale holder -- never this session's
        // own USB_SERIAL record.
        RigStatus.connected(
            descriptor = "TH-D75A",
            bands = emptyList(),
            transportKind = org.ort.rig.RigTransportKind.BLUETOOTH_SPP,
        )

        val detail = DigestPolling.sessionDetail(context, "USB-LIVE")!!

        assert(detail.rigLinkLabel == "USB serial") { "got ${detail.rigLinkLabel}, expected no rig name" }
    }

    // R-844 (register, polish, guide §8): the coverage chart's own real start/end mono clock
    // labels, computed straight from the session's own real span — never left `null` (which would
    // keep `ActivityPatternChart` from drawing an axis row at all whenever no not-listening hour
    // also exists to legend it, guide §8's own hard rule that every activity chart names an axis).
    @Test
    fun `R_844 the coverage axis carries the session's own real start and end clock times`(): Unit = runTest {
        db.sessionDao().insert(session("S1", startedAt = 0L, endedAt = 3_600_000L))
        db.transmissionDao().insert(transmission("TX1", "S1"))

        val detail = DigestPolling.sessionDetail(context, "S1")!!

        assert(detail.coverageStartLabel == "00:00") { "got ${detail.coverageStartLabel}" }
        assert(detail.coverageEndLabel == "01:00") { "got ${detail.coverageEndLabel}" }
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
    // The "In their words" prose section (checklist row E2-G07, DG05, FR-DIG-3/6/11).
    // -----------------------------------------------------------------------------------------

    private fun proseSettings() = SharedPreferencesProseDigestSettingsStore(context)

    @Test
    @Requirement("FR-DIG-6")
    fun `FR_DIG_6 a stored summary for this session's thread renders as a prose card`(): Unit = runTest {
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
    @Requirement("FR-DIG-3a")
    fun `FR_DIG_3a the prose section is absent entirely when disabled, the rest of the digest is unchanged`(): Unit =
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
    @Requirement("FR-DIG-3a")
    fun `FR_DIG_3a the prose section is absent when enabled but nothing has been generated yet`(): Unit = runTest {
        proseSettings().setEnabled(true)
        db.sessionDao().insert(session("S1", startedAt = 0L, endedAt = 3_600_000L))
        db.transmissionDao().insert(transmission("TX1", "S1", stationId = "WA7HJR", threadId = "T1"))

        val digest = DigestPolling.digest(context, "S1")!!

        assert(digest.prose == null)
    }

    @Test
    @Requirement("FR-DIG-11")
    fun `FR_DIG_11 a summary belonging to a different session's thread never leaks in`(): Unit = runTest {
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
