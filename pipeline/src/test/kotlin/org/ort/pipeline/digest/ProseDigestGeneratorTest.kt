package org.ort.pipeline.digest

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.llm.FakeLlmEngine

class ProseDigestGeneratorTest {

    private val transcripts = listOf(
        TimestampedTranscript(0L, "W1AW here, working on the antenna"),
        TimestampedTranscript(60_000L, "K9ZZZ, copy, 73"),
    )
    private val resolvedCallsigns = setOf("W1AW", "K9ZZZ")

    @Test
    fun `FR_DIG_11_stores_summary_attributed_to_its_source_transmission_ids`() = runTest {
        val engine = FakeLlmEngine().apply { scriptedText = "W1AW and K9ZZZ exchanged signal reports." }
        val store = FakeProseSummaryStore()
        val generator = ProseDigestGenerator(engine, store, modelId = "fake-model-1")

        val outcome = generator.generate("thread-1", resolvedCallsigns, transcripts, listOf("tx-1", "tx-2"))

        assertTrue(outcome is ProseDigestOutcome.Stored)
        val summary = (outcome as ProseDigestOutcome.Stored).summary
        assertEquals("thread-1", summary.threadId)
        assertEquals(listOf("tx-1", "tx-2"), summary.sourceTransmissionIds)
        assertEquals("fake-model-1", summary.modelId)
        assertEquals(summary, store.forThread("thread-1"))
    }

    @Test
    fun `E2_I05_a_second_generation_for_the_same_thread_replaces_the_stored_summary`() = runTest {
        val engine = FakeLlmEngine()
        val store = FakeProseSummaryStore()
        val generator = ProseDigestGenerator(engine, store, modelId = "fake-model-1")

        engine.scriptedText = "First draft."
        generator.generate("thread-1", resolvedCallsigns, transcripts, listOf("tx-1"))
        engine.scriptedText = "Revised draft."
        generator.generate("thread-1", resolvedCallsigns, transcripts, listOf("tx-1", "tx-2"))

        val summary = store.forThread("thread-1")
        assertEquals("Revised draft.", summary?.text)
        assertEquals(1, store.all().size)
    }

    @Test
    fun `FR_DIG_4_a_generation_naming_an_uninvented_callsign_is_never_stored`() = runTest {
        val engine = FakeLlmEngine().apply {
            generateBehavior = FakeLlmEngine.GenerateBehavior.INVENT_CALLSIGN
            inventedCallsign = "K1XYZ"
        }
        val store = FakeProseSummaryStore()
        val generator = ProseDigestGenerator(engine, store, modelId = "fake-model-1")

        val outcome = generator.generate("thread-1", resolvedCallsigns, transcripts, listOf("tx-1"))

        assertTrue(outcome is ProseDigestOutcome.Refused)
        assertTrue((outcome as ProseDigestOutcome.Refused).reason.contains("K1XYZ"))
        assertNull(store.forThread("thread-1"))
        assertEquals(0, store.stored.size)
    }

    @Test
    fun `an engine refusal is propagated and never stored`() = runTest {
        val engine = FakeLlmEngine().apply {
            generateBehavior = FakeLlmEngine.GenerateBehavior.REFUSE
            refusalReason = "prompt exceeded context window"
        }
        val store = FakeProseSummaryStore()
        val generator = ProseDigestGenerator(engine, store, modelId = "fake-model-1")

        val outcome = generator.generate("thread-1", resolvedCallsigns, transcripts, listOf("tx-1"))

        assertEquals(ProseDigestOutcome.Refused("prompt exceeded context window"), outcome)
        assertEquals(0, store.stored.size)
    }

    @Test
    fun `an engine failure is propagated and never stored`() = runTest {
        val engine = FakeLlmEngine().apply {
            generateBehavior = FakeLlmEngine.GenerateBehavior.FAIL
            generateFailureReason = "native runtime crashed"
        }
        val store = FakeProseSummaryStore()
        val generator = ProseDigestGenerator(engine, store, modelId = "fake-model-1")

        val outcome = generator.generate("thread-1", resolvedCallsigns, transcripts, listOf("tx-1"))

        assertEquals(ProseDigestOutcome.Failed("native runtime crashed"), outcome)
        assertEquals(0, store.stored.size)
    }

    @Test
    fun `the request supplies the resolved callsigns as the allowed set`() = runTest {
        val engine = FakeLlmEngine()
        val store = FakeProseSummaryStore()
        val generator = ProseDigestGenerator(engine, store, modelId = "fake-model-1")

        generator.generate("thread-1", resolvedCallsigns, transcripts, listOf("tx-1"))

        assertEquals(resolvedCallsigns, engine.lastRequest?.allowedCallsigns)
    }
}
