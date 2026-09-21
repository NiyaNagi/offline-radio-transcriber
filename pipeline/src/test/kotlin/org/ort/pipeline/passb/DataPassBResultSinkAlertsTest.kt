package org.ort.pipeline.passb

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
import org.ort.lexicon.CallsignCandidate
import org.ort.lexicon.ItuAllocation
import org.ort.lexicon.ParsedCallsign
import org.ort.lexicon.RankedCandidate
import org.ort.pipeline.PipelineTestFixtures
import org.ort.pipeline.alerts.AlertEvaluationTrigger
import org.ort.pipeline.alerts.AlertMatchInput
import org.robolectric.RobolectricTestRunner

/**
 * Build-plan P31 (FR-ALR-3, FR-ALR-4, AC-194, AC-195): the second, additive integration point in
 * [DataPassBResultSink] — kept in its own file, the same way P24's own
 * `org.ort.pipeline.threading.DataPassBResultSinkThreadingTest` sits beside
 * [DataPassBResultSinkTest] rather than editing it.
 */
@RunWith(RobolectricTestRunner::class)
class DataPassBResultSinkAlertsTest {

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

    private suspend fun freshDb(frequencyHz: Long? = 146_520_000L): OrtDatabase {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session())
        db.transmissionDao().insert(PipelineTestFixtures.transmission("TX1").copy(frequencyHz = frequencyHz))
        return db
    }

    private class RecordingAlertTrigger : AlertEvaluationTrigger {
        val calls = mutableListOf<AlertMatchInput>()
        override fun fireAndForget(input: AlertMatchInput) {
            calls += input
        }
    }

    /** The same shape [CallsignResolverTest.fakeRanked] builds. */
    private fun fakeRanked(text: String): RankedCandidate {
        val parsed = ParsedCallsign(
            prefix = text.dropLast(4),
            areaDigit = text[text.length - 4],
            suffix = text.takeLast(3),
        )
        val candidate = CallsignCandidate(
            parsed = parsed,
            allocation = ItuAllocation(parsed.prefix, "Test Entity", "TT"),
            acousticLogProb = 1.0f,
            editPenalty = 0f,
            slotSpan = 0..0,
        )
        return RankedCandidate(candidate, emptyList())
    }

    /**
     * Register R-1125: [AlertMatchInput.resolvedCallsign] must come from Pass B's own top-ranked
     * candidate, not from [Attribution.stationId] — the latter is `null` for `AMBIGUOUS` (register
     * R-1110: every attribution today without a calibrator), which is exactly the production shape
     * this fixture recreates. Before this fix, a callsign watch reading `stationId` here would see
     * `null` and could never fire.
     */
    @Test
    fun `R_1125 an AMBIGUOUS attribution still carries its top-ranked candidate as resolvedCallsign`() = runTest {
        val db = freshDb()
        val trigger = RecordingAlertTrigger()
        val sink = DataPassBResultSink(db, alertTrigger = trigger)

        sink.record(
            PassBResult(
                transmissionId = "TX1",
                outcome = PassBOutcome.Accepted(
                    FakeAsrEngine.defaultResult(text = "kilo seven alpha bravo charlie"),
                ),
                lattice = null,
                ranked = listOf(fakeRanked("K7ABC")),
                attribution = Attribution.ambiguous(),
                fingerprint = fingerprint(),
            ),
        )

        val input = trigger.calls.single()
        assertEquals(
            "AMBIGUOUS never carries a stationId (constitution I) -- this is the defect R-1125 fixes",
            null,
            input.stationId,
        )
        assertEquals("K7ABC", input.resolvedCallsign)
    }

    @Test
    fun `FR_ALR_3 the alert trigger receives the resolved Pass B attribution, with the transmission's frequency`() =
        runTest {
            val db = freshDb(frequencyHz = 146_520_000L)
            val trigger = RecordingAlertTrigger()
            val sink = DataPassBResultSink(db, alertTrigger = trigger)

            sink.record(
                PassBResult(
                    transmissionId = "TX1",
                    outcome = PassBOutcome.Accepted(
                        FakeAsrEngine.defaultResult(text = "kilo seven alpha bravo charlie"),
                    ),
                    lattice = null,
                    ranked = emptyList(),
                    attribution = Attribution.confirmed("K7ABC", 0.9),
                    fingerprint = fingerprint(),
                ),
            )

            assertEquals(1, trigger.calls.size)
            val input = trigger.calls.single()
            assertEquals("K7ABC", input.stationId)
            assertEquals("kilo seven alpha bravo charlie", input.transcriptText)
            assertEquals(146_520_000L, input.frequencyHz)
        }

    @Test
    fun `AC_194 a transmission Pass B rejects never carries a station or transcript to the alert path`() = runTest {
        val db = freshDb()
        val trigger = RecordingAlertTrigger()
        val sink = DataPassBResultSink(db, alertTrigger = trigger)

        sink.record(
            PassBResult(
                transmissionId = "TX1",
                outcome = PassBOutcome.Rejected(
                    rule = RejectionRuleId.TOO_SHORT,
                    detail = "too short",
                    partialResult = null,
                ),
                lattice = null,
                ranked = emptyList(),
                attribution = Attribution.unknown(),
                fingerprint = fingerprint(),
            ),
        )

        val input = trigger.calls.single()
        assertEquals(null, input.stationId)
        assertEquals(null, input.transcriptText)
    }

    @Test
    fun `AC_194 a retry only reaches the alert path with Pass B's final result, never an earlier guess`() = runTest {
        // Simulates a first Pass B attempt that could not resolve anything (the shape a Pass A
        // partial would have been superseded by), followed by a retry that does resolve --
        // the alert path must see only the final, resolved call, never the earlier one as if
        // it had matched.
        val db = freshDb()
        val trigger = RecordingAlertTrigger()
        val sink = DataPassBResultSink(db, alertTrigger = trigger)

        sink.record(
            PassBResult(
                transmissionId = "TX1",
                outcome = PassBOutcome.Failed(reason = "engine threw", cause = null),
                lattice = null,
                ranked = emptyList(),
                attribution = Attribution.unknown(),
                fingerprint = fingerprint(),
            ),
        )
        sink.record(
            PassBResult(
                transmissionId = "TX1",
                outcome = PassBOutcome.Accepted(
                    FakeAsrEngine.defaultResult(text = "kilo seven alpha bravo charlie"),
                ),
                lattice = null,
                ranked = emptyList(),
                attribution = Attribution.confirmed("K7ABC", 0.9),
                fingerprint = fingerprint(),
            ),
        )

        assertEquals(2, trigger.calls.size)
        assertEquals(
            "the failed attempt must not carry a fabricated station",
            null,
            trigger.calls[0].stationId,
        )
        assertEquals("K7ABC", trigger.calls[1].stationId)
    }

    @Test
    fun `a throwing alert trigger never prevents the transmission's attribution from being persisted`() = runTest {
        val db = freshDb()
        val throwingTrigger = AlertEvaluationTrigger { error("boom") }
        val sink = DataPassBResultSink(db, alertTrigger = throwingTrigger)

        sink.record(
            PassBResult(
                transmissionId = "TX1",
                outcome = PassBOutcome.Accepted(FakeAsrEngine.defaultResult(text = "kilo seven alpha bravo charlie")),
                lattice = null,
                ranked = emptyList(),
                attribution = Attribution.confirmed("K7ABC", 0.9),
                fingerprint = fingerprint(),
            ),
        )

        val stored = db.transmissionDao().getById("TX1")!!
        assertEquals("K7ABC", stored.stationId)
        assertEquals(org.ort.core.AttributionState.CONFIRMED, stored.attributionState)
    }
}
