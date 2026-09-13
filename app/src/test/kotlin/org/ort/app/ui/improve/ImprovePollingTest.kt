package org.ort.app.ui.improve

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.AttributionState
import org.ort.core.Tier
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TransmissionEntity
import org.ort.pipeline.capture.ShedStatus
import org.robolectric.RobolectricTestRunner

/**
 * R-091/R-107 (register): [ImprovePolling] reads [SessionEntity.deviceTier] — written and, before
 * this package, read nowhere (R-107) — and groups qualifying sessions honestly; [FakeImproveRunner]
 * performs its one real, honest side effect and invents no content.
 */
@RunWith(RobolectricTestRunner::class)
class ImprovePollingTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: OrtDatabase

    @Before
    fun setUp() {
        db = OrtDatabase.create(context)
        ShedStatus.reset()
    }

    @After
    fun tearDown() {
        ShedStatus.reset()
    }

    private fun session(id: String, tier: String?) = SessionEntity(
        id = id,
        startedAt = 0L,
        endedAt = null,
        profileId = null,
        deviceTier = tier,
        appVersion = "test",
        terminationReason = null,
        sourceId = null,
        schemaVersion = OrtDatabase.SCHEMA_VERSION,
    )

    private fun transmission(id: String, sessionId: String) = TransmissionEntity(
        id = id,
        sessionId = sessionId,
        threadId = null,
        startedAtUtc = 0L,
        endedAtUtc = 1_000L,
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
        attributionState = AttributionState.UNKNOWN,
        stationId = null,
        attributionConfidence = null,
        attributionSourceTransmissionId = null,
        processingState = TransmissionState.CAPTURED,
        rejectionReason = null,
        samplePosition = 0L,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )

    @Test
    fun `R_107 a session with no deviceTier is not a reprocessing candidate`(): Unit = runTest {
        db.sessionDao().insert(session("S1", tier = null))
        db.transmissionDao().insert(transmission("TX1", "S1"))

        val result = ImprovePolling.root(context)

        assert(result.totalOverCount == 0) { "expected no candidates, got ${result.totalOverCount}" }
        assert(result.groups.isEmpty())
    }

    @Test
    fun `R_107 a session captured below the current tier groups by its tier, with a real over count`(): Unit = runTest {
        ShedStatus.update(level = 0, backlog = 0) // current tier = T3 (MAX)
        db.sessionDao().insert(session("S1", tier = "T1"))
        db.transmissionDao().insert(transmission("TX1", "S1"))
        db.transmissionDao().insert(transmission("TX2", "S1"))
        db.sessionDao().insert(session("S2", tier = null))
        db.transmissionDao().insert(transmission("TX3", "S2"))

        val result = ImprovePolling.root(context)

        assert(result.totalOverCount == 2) { "expected 2 qualifying overs, got ${result.totalOverCount}" }
        assert(result.groups.size == 1)
        assert(result.groups.first().overCount == 2)
        assert(result.everythingElseCount == 1) {
            "expected 1 over ('TX3') at current capability, got ${result.everythingElseCount}"
        }
    }

    // -------------------------------------------------------------------------------------------
    // R-1064 (coordinator round, WPIMPROVE, FR-REP-2/9): a session's own `deviceTier` never
    // changes, so it alone cannot say whether any particular over in it still needs improving --
    // only `TransmissionEntity.processedTier` (stamped by the real engine on every completed or
    // rejected outcome, live capture included per its own doc comment) can. These two discriminate
    // against the old behaviour, which counted every transmission in a qualifying session
    // regardless of `processedTier` and so kept claiming "N overs can get better" forever.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `R_1064 a completed run leaves the root showing zero remaining candidates`(): Unit = runTest {
        ShedStatus.update(level = 0, backlog = 0) // current tier = T3 (MAX)
        db.sessionDao().insert(session("S1", tier = "T1"))
        db.transmissionDao().insert(transmission("TX1", "S1"))
        db.transmissionDao().insert(transmission("TX2", "S1"))
        // The real engine's own completion write (`ReprocessRunner.setProcessedTier`) -- both
        // overs already brought current; neither is a genuine candidate any more.
        db.transmissionDao().setProcessedTier("TX1", Tier.T3)
        db.transmissionDao().setProcessedTier("TX2", Tier.T3)

        val result = ImprovePolling.root(context)

        assert(result.totalOverCount == 0) { "expected no remaining candidates, got ${result.totalOverCount}" }
        assert(result.groups.isEmpty()) { "expected no group for a session with nothing left to improve" }
    }

    @Test
    fun `R_1064 a mixed set counts only the overs not yet brought current`(): Unit = runTest {
        ShedStatus.update(level = 0, backlog = 0) // current tier = T3 (MAX)
        db.sessionDao().insert(session("S1", tier = "T1"))
        db.transmissionDao().insert(transmission("TX1", "S1"))
        db.transmissionDao().insert(transmission("TX2", "S1"))
        db.transmissionDao().insert(transmission("TX3", "S1"))
        // TX1 already reprocessed at the current tier (no longer a candidate). TX2 is still at its
        // own capture tier (real live-capture write, per `TransmissionEntity.processedTier`'s own
        // doc comment). TX3 has never been processed at all (`processedTier` stays `null` until a
        // pass first finishes for it) -- both TX2 and TX3 are real, outstanding candidates.
        db.transmissionDao().setProcessedTier("TX1", Tier.T3)
        db.transmissionDao().setProcessedTier("TX2", Tier.T1)

        val result = ImprovePolling.root(context)

        assert(result.totalOverCount == 2) { "expected 2 remaining candidates, got ${result.totalOverCount}" }
        assert(result.groups.size == 1)
        assert(result.groups.single().transmissionIds.toSet() == setOf("TX2", "TX3")) {
            "expected exactly the two not-yet-current overs, got ${result.groups.single().transmissionIds}"
        }
    }

    @Test
    fun `R_091 select computes a real corrections-to-reapply count, never fabricated`(): Unit = runTest {
        db.sessionDao().insert(session("S1", tier = "T1"))
        db.transmissionDao().insert(transmission("TX1", "S1"))
        val group = ImproveGroupViewState("g", "headline", "sub", 1, listOf("TX1"), tierOrdinal = 1)

        val select = ImprovePolling.select(context, group)

        assert(select.correctionsToReapply == 0)
        assert(select.estimatedSeconds == null) { "no ThermalStatus RTF sampled — must not fabricate a time" }
    }

    @Test
    fun `R_091 FakeImproveRunner clears the reprocess-candidate flag and invents no other content`(): Unit = runTest {
        db.sessionDao().insert(session("S1", tier = "T1"))
        db.transmissionDao().insert(transmission("TX1", "S1"))
        db.transmissionDao().setReprocessCandidate("TX1", true)

        val runner = FakeImproveRunner(context, perItemDelayMillis = 0L)
        val progressEvents = runner.run(listOf("TX1")).toList()

        assert(progressEvents.last() == ImproveRunProgress(done = 1, total = 1))
        val after = db.transmissionDao().getById("TX1")!!
        assert(!after.isReprocessCandidate)
        // The transcript/attribution fields the runner never touches:
        assert(after.attributionState == AttributionState.UNKNOWN)
        assert(after.stationId == null)
    }
}
