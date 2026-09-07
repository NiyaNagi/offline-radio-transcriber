package org.ort.asrapi

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.asrapi.fake.FakeAsrEngine
import org.ort.asrapi.rules.RejectionRuleId
import org.ort.core.TransmissionId
import org.ort.testing.Requirement

class RejectedSegmentLogTest {

    private fun highNoSpeechProbEngine() =
        FakeAsrEngine(FakeAsrEngine.Behaviour.Returns(FakeAsrEngine.defaultResult(text = "", noSpeechProb = 0.99f)))

    @Test
    @Requirement("AC-8", "FR-ASR-6")
    fun `AC_8 a rejected segment is retained with its audio and reachable behind a rule filter`() = runTest {
        val engine = highNoSpeechProbEngine()
        val pipeline = RejectionPipeline(engine)
        val log = RejectedSegmentLog()
        val txId = TransmissionId.new()
        val audio = FloatArray(1600) { 0.001f }

        val outcome = pipeline.process(SegmentCandidate(2000, vadDetectedSpeech = true), audio, DecodeOptions())
        log.record(txId, outcome as PassBOutcome.Rejected, audio)

        assertEquals(1, log.all.size, "nothing is deleted or hidden (P9, AC-8)")
        assertTrue(log.byRule(RejectionRuleId.NO_SPEECH_PROB).any { it.transmissionId == txId })
        assertEquals(1600, log.all.single().audio.size, "audio is retained in full")
    }

    @Test
    @Requirement("AC-8")
    fun `filtering by an unrelated rule returns nothing but the record still exists`() = runTest {
        val engine = highNoSpeechProbEngine()
        val pipeline = RejectionPipeline(engine)
        val log = RejectedSegmentLog()
        val txId = TransmissionId.new()

        val candidate = SegmentCandidate(2000, vadDetectedSpeech = true)
        val outcome = pipeline.process(candidate, FloatArray(16), DecodeOptions())
        log.record(txId, outcome as PassBOutcome.Rejected, FloatArray(16))

        assertTrue(log.byRule(RejectionRuleId.BLOCKLIST).isEmpty())
        assertEquals(1, log.all.size, "the record is not hidden even when it doesn't match the current filter")
    }
}
