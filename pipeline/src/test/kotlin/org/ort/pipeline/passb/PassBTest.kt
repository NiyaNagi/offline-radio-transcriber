package org.ort.pipeline.passb

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.ort.asrapi.DecodeOptions
import org.ort.asrapi.PassBOutcome
import org.ort.asrapi.RejectionPipeline
import org.ort.asrapi.SegmentCandidate
import org.ort.asrapi.fake.FakeAsrEngine
import org.ort.core.AssetRef
import org.ort.core.AttributionState
import org.ort.core.PassFingerprint
import org.ort.core.PassId
import org.ort.core.Tier
import org.ort.core.TransmissionState
import org.ort.data.PassRunOutcome
import org.ort.data.entity.WorkQueueItemEntity
import org.ort.data.entity.WorkQueueState
import org.ort.lexicon.CallsignGrammar
import org.ort.lexicon.ConfusionCostMatrix
import org.ort.lexicon.ItuPrefixTable
import org.ort.lexicon.PriorCombiner
import org.ort.lexicon.VariantTable

/**
 * The real end-to-end M3 wiring (build-plan P11, technical design §9.2, AC-15): capture's output
 * (a closed segment, stood in for here by [FakeAsrEngine]) through Pass B's rejection pipeline,
 * the text-derived lattice, the callsign grammar, prior ranking and the resolver, to an
 * [org.ort.core.Attribution] — as one real [org.ort.pipeline.Pass], carrying its
 * [PassFingerprint] (constitution III). No device, no real model: every input here is a fake or
 * a fixture, exactly as build-plan P6/P7/P9's own sessions verified their hard-to-measure pieces.
 */
class PassBTest {

    private fun item(transmissionId: String = "TX1") = WorkQueueItemEntity(
        transmissionId = transmissionId,
        pass = PassId.B_OFFLINE,
        state = WorkQueueState.LEASED,
        priority = 0,
        enqueuedAt = 0L,
    )

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

    private fun resolutionChain(confirmThreshold: Float, separationThreshold: Float = 0.01f) = PassBResolutionChain(
        variants = VariantTable.bundled(),
        grammar = CallsignGrammar(ItuPrefixTable.bundled(), ConfusionCostMatrix.bundled()),
        combiner = PriorCombiner(emptyList()),
        resolver = CallsignResolver(separationThreshold = separationThreshold, confirmThreshold = confirmThreshold),
    )

    private fun passB(
        engineText: String,
        confirmThreshold: Float = -1f,
        audio: FloatArray = FloatArray(16_000),
        sink: RecordingSink = RecordingSink(),
    ): Pair<PassB, RecordingSink> {
        val engine = FakeAsrEngine(
            FakeAsrEngine.Behaviour.Returns(FakeAsrEngine.defaultResult(text = engineText)),
        )
        val passB = PassB(
            audioProvider = { SegmentAudio(audio, SegmentCandidate(durationMs = 3_000, vadDetectedSpeech = true)) },
            rejectionPipeline = RejectionPipeline(engine),
            resolution = resolutionChain(confirmThreshold),
            fingerprint = fingerprint(),
            sink = sink,
            decodeOptions = DecodeOptions(),
        )
        return passB to sink
    }

    class RecordingSink : PassBResultSink {
        var last: PassBResult? = null
        override suspend fun record(result: PassBResult) {
            last = result
        }
    }

    @Test
    fun `a spelled callsign resolves to CONFIRMED end to end and the outcome is COMPLETE`() = runTest {
        val (passB, sink) = passB(engineText = "kilo seven alpha bravo charlie")
        val outcome = passB.run(item())
        assertEquals(PassRunOutcome.Finished(TransmissionState.COMPLETE), outcome)
        assertNotNull(sink.last)
        assertEquals(AttributionState.CONFIRMED, sink.last!!.attribution.state)
        assertEquals("K7ABC", sink.last!!.attribution.stationId)
        assertEquals(PassId.B_OFFLINE, sink.last!!.fingerprint.passId)
    }

    @Test
    fun `ordinary speech with no callsign resolves to UNKNOWN, never invented`() = runTest {
        val (passB, sink) = passB(engineText = "test transmission received")
        val outcome = passB.run(item())
        assertEquals(PassRunOutcome.Finished(TransmissionState.COMPLETE), outcome)
        assertEquals(AttributionState.UNKNOWN, sink.last!!.attribution.state)
    }

    @Test
    fun `a rejected segment never reaches the resolver and is UNKNOWN`() = runTest {
        val engine = FakeAsrEngine(FakeAsrEngine.Behaviour.Returns(FakeAsrEngine.defaultResult()))
        val sink = RecordingSink()
        val shortSegment = SegmentCandidate(durationMs = 50, vadDetectedSpeech = true)
        val passB = PassB(
            audioProvider = { SegmentAudio(FloatArray(16_000), shortSegment) },
            rejectionPipeline = RejectionPipeline(engine),
            resolution = resolutionChain(confirmThreshold = -1f, separationThreshold = 0.5f),
            fingerprint = fingerprint(),
            sink = sink,
        )
        val outcome = passB.run(item())
        assertEquals(PassRunOutcome.Finished(TransmissionState.REJECTED), outcome)
        assertEquals(AttributionState.UNKNOWN, sink.last!!.attribution.state)
        assertNull(sink.last!!.lattice)
        check(sink.last!!.outcome is PassBOutcome.Rejected)
    }
}
