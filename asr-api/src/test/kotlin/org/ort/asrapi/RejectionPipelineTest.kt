package org.ort.asrapi

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.asrapi.fake.FakeAsrEngine
import org.ort.asrapi.rules.RejectionRuleId
import org.ort.testing.Requirement

class RejectionPipelineTest {

    @Test
    @Requirement("AC-7", "FR-ASR-5")
    fun `a segment below the duration floor is rejected without ever invoking the engine`() = runTest {
        val engine = FakeAsrEngine()
        val pipeline = RejectionPipeline(engine)

        val outcome = pipeline.process(
            SegmentCandidate(durationMs = 100, vadDetectedSpeech = true),
            FloatArray(16),
            DecodeOptions(),
        )

        assertEquals(0, engine.callCount, "the cheapest rule must reject before any model runs")
        assertEquals(RejectionRuleId.TOO_SHORT, (outcome as PassBOutcome.Rejected).rule)
    }

    @Test
    @Requirement("AC-8", "FR-ASR-6")
    fun `a rejected segment's outcome carries the rule and does not delete or touch the source audio`() = runTest {
        val repeated = FakeAsrEngine.defaultResult(text = "go go go go go go go go go go go go")
        val engine = FakeAsrEngine(FakeAsrEngine.Behaviour.Returns(repeated))
        val pipeline = RejectionPipeline(engine)
        val audio = FloatArray(1600) { it.toFloat() }

        val outcome = pipeline.process(
            SegmentCandidate(durationMs = 2000, vadDetectedSpeech = true),
            audio,
            DecodeOptions(),
        )

        val rejected = outcome as PassBOutcome.Rejected
        assertEquals(RejectionRuleId.REPETITION, rejected.rule)
        assertTrue(rejected.detail.isNotBlank(), "the reason must be recorded, not just the fact of rejection")
        // the pipeline never mutates or discards the caller's audio array (AC-8: audio retained)
        assertEquals(1600, audio.size)
        assertEquals(0f, audio[0])
    }

    @Test
    @Requirement("F13")
    fun `an engine that hangs past the timeout produces Failed rather than blocking forever`() = runTest {
        val engine = FakeAsrEngine(FakeAsrEngine.Behaviour.HangsFor(millis = 60_000))
        val pipeline = RejectionPipeline(engine, timeoutMs = 50)

        val outcome = pipeline.process(
            SegmentCandidate(durationMs = 2000, vadDetectedSpeech = true),
            FloatArray(16),
            DecodeOptions(),
        )

        assertTrue(outcome is PassBOutcome.Failed, "a hang must surface as Failed, never as a hang or a silent accept")
    }

    @Test
    @Requirement("F13")
    fun `an engine that throws produces Failed with the cause preserved`() = runTest {
        val boom = IllegalStateException("native crash")
        val engine = FakeAsrEngine(FakeAsrEngine.Behaviour.Throws(boom))
        val pipeline = RejectionPipeline(engine)

        val outcome = pipeline.process(
            SegmentCandidate(durationMs = 2000, vadDetectedSpeech = true),
            FloatArray(16),
            DecodeOptions(),
        )

        val failed = outcome as PassBOutcome.Failed
        assertEquals(boom.message, failed.cause?.message)
    }

    @Test
    @Requirement("AC-7")
    fun `clean audio that passes every control is Accepted`() = runTest {
        val engine = FakeAsrEngine(
            FakeAsrEngine.Behaviour.Returns(
                FakeAsrEngine.defaultResult(text = "kilo seven able baker this is whiskey seven x-ray yankee zulu"),
            ),
        )
        val pipeline = RejectionPipeline(engine)

        val outcome = pipeline.process(
            SegmentCandidate(durationMs = 2000, vadDetectedSpeech = true),
            FloatArray(16),
            DecodeOptions(),
        )

        assertTrue(outcome is PassBOutcome.Accepted)
        assertNull((outcome as? PassBOutcome.Rejected)?.rule)
    }

    @Test
    fun `the pipeline refuses to be built without exactly the six named controls`() {
        val engine = FakeAsrEngine()
        val ex = runCatching {
            RejectionPipeline(engine, preRules = emptyList(), postRules = emptyList())
        }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException)
    }
}
