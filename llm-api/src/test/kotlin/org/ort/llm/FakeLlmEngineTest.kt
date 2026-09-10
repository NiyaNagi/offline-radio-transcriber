package org.ort.llm

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * E2-I01 (`results/e2e-audit/checklist.md`): [FakeLlmEngine] can be told to hang, fail to load,
 * exceed its token budget, and invent a callsign outside the allowed set — one test per failure
 * mode (constitution II). [CallsignShapeFilterTest] is what actually catches the invented
 * callsign; this file only proves the fake can genuinely misbehave in each of the four ways.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeLlmEngineTest {

    @Test
    fun `E2_I01 load can be scripted to fail without throwing`() = runTest {
        val engine = FakeLlmEngine().apply {
            loadBehavior = FakeLlmEngine.LoadBehavior.FAIL
            loadFailureReason = "no model on disk"
        }

        val result = engine.load()

        assertEquals(LlmLoadResult.Failed("no model on disk"), result)
        assertEquals(LlmState.Failed("no model on disk"), engine.state.value)
    }

    @Test
    fun `E2_I01 load can be scripted to hang until cancelled`() = runTest {
        val engine = FakeLlmEngine().apply { loadBehavior = FakeLlmEngine.LoadBehavior.HANG }

        val job = launch { engine.load() }
        advanceUntilIdle()
        assertTrue(job.isActive, "a hung load must never complete on its own")
        assertEquals(LlmState.Loading, engine.state.value)

        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test
    fun `E2_I01 generate can be scripted to hang until cancelled`() = runTest {
        val engine = FakeLlmEngine().apply { generateBehavior = FakeLlmEngine.GenerateBehavior.HANG }

        val job = launch { engine.generate(LlmRequest("prompt", 32, emptySet())) }
        advanceUntilIdle()
        assertTrue(job.isActive, "a hung generate must never complete on its own")

        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test
    fun `E2_I01 generate can be scripted to exceed the requested token budget`() = runTest {
        val engine = FakeLlmEngine().apply { generateBehavior = FakeLlmEngine.GenerateBehavior.EXCEED_TOKEN_BUDGET }
        val request = LlmRequest("prompt", maxTokens = 5, allowedCallsigns = emptySet())

        val result = engine.generate(request) as LlmResult.Text
        val wordCount = result.text.trim().split(Regex("\\s+")).size

        assertTrue(wordCount > request.maxTokens, "expected more than ${request.maxTokens} words, got $wordCount")
    }

    @Test
    fun `E2_I01 generate can be scripted to invent a callsign outside the allowed set`() = runTest {
        val engine = FakeLlmEngine().apply {
            generateBehavior = FakeLlmEngine.GenerateBehavior.INVENT_CALLSIGN
            inventedCallsign = "K9ZZZ"
        }
        val allowed = setOf("W1AW")

        val result = engine.generate(LlmRequest("prompt", 64, allowed)) as LlmResult.Text

        assertTrue(result.text.contains("K9ZZZ"))
        assertFalse(allowed.contains("K9ZZZ"), "the invented callsign must genuinely be outside the allowed set")
    }

    @Test
    fun `release moves state to Unloaded and counts the call`() {
        val engine = FakeLlmEngine()

        engine.release()

        assertEquals(LlmState.Unloaded, engine.state.value)
        assertEquals(1, engine.releaseCallCount)
    }
}
