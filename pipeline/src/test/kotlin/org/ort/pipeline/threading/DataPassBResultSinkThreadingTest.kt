package org.ort.pipeline.threading

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.asrapi.PassBOutcome
import org.ort.asrapi.fake.FakeAsrEngine
import org.ort.asrapi.rules.RejectionRuleId
import org.ort.core.AssetRef
import org.ort.core.Attribution
import org.ort.core.PassFingerprint
import org.ort.core.PassId
import org.ort.core.Tier
import org.ort.data.OrtDatabase
import org.ort.data.entity.ThreadJoinReason
import org.ort.data.entity.ThreadKind
import org.ort.pipeline.PipelineTestFixtures
import org.ort.pipeline.passb.DataPassBResultSink
import org.ort.pipeline.passb.PassBResult
import org.robolectric.RobolectricTestRunner

/**
 * Build-plan P24 (FR-SPK-5, AC-163..165): the real, end-to-end proof that automatic thread
 * grouping actually runs — against a real (in-memory) [OrtDatabase] and the real
 * [DataPassBResultSink]/[RoomThreadRepository] wiring, not a fake. Before this unit,
 * `RealCaptureService` inserted every over with `threadId = null` and nothing in production ever
 * assigned one (D45's own amendment note on FR-SPK-5); these tests are the tape.
 */
@RunWith(RobolectricTestRunner::class)
class DataPassBResultSinkThreadingTest {

    private fun fingerprint() = PassFingerprint(
        passId = PassId.B_OFFLINE,
        codeVersion = 1,
        modelIds = listOf(AssetRef("fake-asr-model", "1")),
        lexiconVersion = null,
        calibrationVersion = null,
        configHash = "test",
        provider = "cpu",
        tier = Tier.T0,
    )

    private fun accepted(transmissionId: String, callsign: String) = PassBResult(
        transmissionId = transmissionId,
        outcome = PassBOutcome.Accepted(FakeAsrEngine.defaultResult(text = "over from $callsign")),
        lattice = null,
        ranked = emptyList(),
        attribution = Attribution.confirmed(callsign, 0.9),
        fingerprint = fingerprint(),
    )

    private fun rejected(transmissionId: String) = PassBResult(
        transmissionId = transmissionId,
        outcome = PassBOutcome.Rejected(rule = RejectionRuleId.TOO_SHORT, detail = "too short", partialResult = null),
        lattice = null,
        ranked = emptyList(),
        attribution = Attribution.unknown(),
        fingerprint = fingerprint(),
    )

    private suspend fun freshDb(): OrtDatabase {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session())
        return db
    }

    private suspend fun seedTransmission(
        db: OrtDatabase,
        id: String,
        samplePosition: Long,
        startedAtUtc: Long,
        frequencyHz: Long?,
    ) {
        db.transmissionDao().insert(
            PipelineTestFixtures.transmission(id).copy(
                samplePosition = samplePosition,
                startedAtUtc = startedAtUtc,
                endedAtUtc = startedAtUtc + 2_000L,
                frequencyHz = frequencyHz,
            ),
        )
    }

    @Test
    fun `AC_163 an ordinary two-station QSO threads automatically into one Thread record`() = runTest {
        val db = freshDb()
        val sink = DataPassBResultSink(db)
        val freq = 146_520_000L
        seedTransmission(db, "TX1", 0, 0, freq)
        seedTransmission(db, "TX2", 1, 10_000, freq)
        seedTransmission(db, "TX3", 2, 20_000, freq)

        sink.record(accepted("TX1", "K7ABC"))
        sink.record(accepted("TX2", "W7XYZ"))
        sink.record(accepted("TX3", "K7ABC"))

        val threadId = db.transmissionDao().getById("TX1")!!.threadId
        assertNotNull(threadId)
        assertEquals(threadId, db.transmissionDao().getById("TX2")!!.threadId)
        assertEquals(threadId, db.transmissionDao().getById("TX3")!!.threadId)
        assertEquals(3, db.catalogDao().getThread(threadId!!)!!.transmissionCount)
        assertEquals(ThreadKind.QSO, db.catalogDao().getThread(threadId)!!.kind)
    }

    @Test
    fun `AC_164 a detected net's check-in sequence threads into one Thread record marked net`() = runTest {
        val db = freshDb()
        val sink = DataPassBResultSink(db)
        val freq = 146_940_000L
        // NET-CONTROL alternates with three distinct check-ins: dominant voice + 3 "others",
        // 6 transmissions total -- matches ThreadKindClassifierTest's own thresholds.
        val plan = listOf(
            "K7NET" to 0L,
            "AB1CD" to 10_000L,
            "K7NET" to 20_000L,
            "EF2GH" to 30_000L,
            "K7NET" to 40_000L,
            "IJ3KL" to 50_000L,
        )
        plan.forEachIndexed { index, (callsign, startedAt) ->
            val id = "TX${index + 1}"
            seedTransmission(db, id, index.toLong(), startedAt, freq)
            sink.record(accepted(id, callsign))
        }

        val threadId = db.transmissionDao().getById("TX1")!!.threadId
        assertNotNull(threadId)
        plan.indices.forEach { index ->
            val id = "TX${index + 1}"
            val actual = db.transmissionDao().getById(id)!!.threadId
            assertEquals("$id must share the net's single thread", threadId, actual)
        }
        assertEquals(ThreadKind.NET, db.catalogDao().getThread(threadId!!)!!.kind)
    }

    @Test
    fun `AC_164 the net thread exists whether or not the net marking is ever set or cleared`() = runTest {
        // FR-SPK-28: threading itself (whether TX1 and TX2 share a Thread) does not depend on
        // net detection at all -- two overs on one frequency within the gap thread together
        // whether or not they ever look like a net. This is the same guarantee AC-163 exercises
        // with a plain QSO; asserted here explicitly against a callsign pattern that also happens
        // to be net-shaped, to show the marking is decoration, never a precondition for joining.
        val db = freshDb()
        val sink = DataPassBResultSink(db)
        val freq = 146_940_000L
        seedTransmission(db, "TX1", 0, 0, freq)
        seedTransmission(db, "TX2", 1, 10_000, freq)

        sink.record(accepted("TX1", "K7NET"))
        sink.record(accepted("TX2", "AB1CD"))

        val threadId = db.transmissionDao().getById("TX1")!!.threadId
        assertNotNull(threadId)
        assertEquals(threadId, db.transmissionDao().getById("TX2")!!.threadId)
    }

    @Test
    fun `AC_165 scanner activity threads automatically and grouping runs on Pass B closure, not a transcript`() =
        runTest {
            val db = freshDb()
            val sink = DataPassBResultSink(db)
            val freq = 462_562_500L
            seedTransmission(db, "TX1", 0, 0, freq)
            seedTransmission(db, "TX2", 1, 10_000, freq)

            sink.record(accepted("TX1", "KE7AAA"))
            // TX2's Pass B outcome is Rejected -- no transcript, no attribution -- yet it still
            // closed, and threading must not wait on a transcript to join it (FR-SPK-5).
            sink.record(rejected("TX2"))

            val threadId = db.transmissionDao().getById("TX1")!!.threadId
            assertNotNull(threadId)
            assertEquals(threadId, db.transmissionDao().getById("TX2")!!.threadId)
        }

    @Test
    fun `two interleaved frequencies from a dual-receive rig produce two separate Thread records`() = runTest {
        val db = freshDb()
        val sink = DataPassBResultSink(db)
        val bandA = 146_520_000L
        val bandB = 446_000_000L
        seedTransmission(db, "A1", 0, 0, bandA)
        seedTransmission(db, "B1", 1, 500, bandB)
        seedTransmission(db, "A2", 2, 2_000, bandA)
        seedTransmission(db, "B2", 3, 2_500, bandB)

        sink.record(accepted("A1", "K7ABC"))
        sink.record(accepted("B1", "W7XYZ"))
        sink.record(accepted("A2", "K7DEF"))
        sink.record(accepted("B2", "W7GHI"))

        val threadA = db.transmissionDao().getById("A1")!!.threadId
        val threadB = db.transmissionDao().getById("B1")!!.threadId
        assertNotNull(threadA)
        assertNotNull(threadB)
        assertNotEquals(threadA, threadB)
        assertEquals(threadA, db.transmissionDao().getById("A2")!!.threadId)
        assertEquals(threadB, db.transmissionDao().getById("B2")!!.threadId)
    }

    @Test
    fun `a single over starts its own one-transmission Thread record`() = runTest {
        val db = freshDb()
        val sink = DataPassBResultSink(db)
        seedTransmission(db, "TX1", 0, 0, 146_520_000L)

        sink.record(accepted("TX1", "K7ABC"))

        val threadId = db.transmissionDao().getById("TX1")!!.threadId
        assertNotNull(threadId)
        assertEquals(1, db.catalogDao().getThread(threadId!!)!!.transmissionCount)
    }

    @Test
    fun `a gap beyond the default threshold starts a second Thread record on the same frequency`() = runTest {
        val db = freshDb()
        val sink = DataPassBResultSink(db)
        val freq = 146_520_000L
        seedTransmission(db, "TX1", 0, 0, freq)
        // 11 minutes later -- past the 10-minute default gap threshold.
        seedTransmission(db, "TX2", 1, 11 * 60 * 1000L, freq)

        sink.record(accepted("TX1", "K7ABC"))
        sink.record(accepted("TX2", "K7ABC"))

        assertNotEquals(db.transmissionDao().getById("TX1")!!.threadId, db.transmissionDao().getById("TX2")!!.threadId)
    }

    // ---- R-1098: the real join reason survives all the way onto the transmission's own row ----

    @Test
    fun `R_1098 the join reason is persisted on the real transmission row, not discarded`() = runTest {
        val db = freshDb()
        val sink = DataPassBResultSink(db)
        val freq = 146_520_000L
        seedTransmission(db, "TX1", 0, 0, freq)
        seedTransmission(db, "TX2", 1, 10_000, freq)

        sink.record(accepted("TX1", "K7ABC"))
        sink.record(accepted("TX2", "W7XYZ"))

        assertEquals(
            "the first transmission in a session must persist its own real start reason",
            ThreadJoinReason.FIRST_TRANSMISSION_IN_SESSION,
            db.transmissionDao().getById("TX1")!!.threadJoinReason,
        )
        assertEquals(
            "a transmission joining an existing thread by frequency must persist that real reason",
            ThreadJoinReason.SAME_FREQUENCY_WITHIN_GAP,
            db.transmissionDao().getById("TX2")!!.threadJoinReason,
        )
    }

    @Test
    fun `R_1098 a gap-exceeded new thread persists its own distinct reason on the real row`() = runTest {
        val db = freshDb()
        val sink = DataPassBResultSink(db)
        val freq = 146_520_000L
        seedTransmission(db, "TX1", 0, 0, freq)
        // 11 minutes later -- past the 10-minute default gap threshold.
        seedTransmission(db, "TX2", 1, 11 * 60 * 1000L, freq)

        sink.record(accepted("TX1", "K7ABC"))
        sink.record(accepted("TX2", "K7ABC"))

        assertEquals(
            ThreadJoinReason.NEW_THREAD_GAP_EXCEEDED,
            db.transmissionDao().getById("TX2")!!.threadJoinReason,
        )
    }
}
