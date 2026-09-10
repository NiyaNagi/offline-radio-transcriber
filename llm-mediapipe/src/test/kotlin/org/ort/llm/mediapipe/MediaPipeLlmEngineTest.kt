package org.ort.llm.mediapipe

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.llm.LlmLoadResult
import org.ort.llm.LlmRequest
import org.ort.llm.LlmResult
import org.ort.llm.LlmState
import org.robolectric.RobolectricTestRunner

/**
 * E2-I06 (`results/e2e-audit/checklist.md`) — the non-hardware half: a missing bundled model
 * yields [LlmState.Failed] without a crash. The real load of a real `.task` file and real
 * generation are hardware row H11 — nothing here exercises the actual MediaPipe native runtime,
 * only [MediaPipeLlmEngine]'s own guards ahead of it (constitution I: this contract never throws
 * into a caller).
 */
@RunWith(RobolectricTestRunner::class)
class MediaPipeLlmEngineTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `E2_I06 missing model path yields Failed state without throwing`() = runTest {
        val engine = MediaPipeLlmEngine(context, modelPath = "/does/not/exist/gemma3-1b-it-int4.task")

        val result = engine.load()

        assertTrue("expected Failed, got $result", result is LlmLoadResult.Failed)
        assertTrue("expected LlmState.Failed, got ${engine.state.value}", engine.state.value is LlmState.Failed)
    }

    @Test
    fun `generate before a successful load returns Failed rather than throwing`() = runTest {
        val engine = MediaPipeLlmEngine(context, modelPath = "/does/not/exist/gemma3-1b-it-int4.task")

        val result = engine.generate(LlmRequest("summarize", 64, emptySet()))

        assertTrue("expected Failed, got $result", result is LlmResult.Failed)
    }

    @Test
    fun `release before any load never throws and leaves state Unloaded`() {
        val engine = MediaPipeLlmEngine(context, modelPath = "/does/not/exist/gemma3-1b-it-int4.task")

        engine.release()

        assertEquals(LlmState.Unloaded, engine.state.value)
    }

    @Test
    fun `state starts Unloaded — construction never loads`() {
        val engine = MediaPipeLlmEngine(context, modelPath = "/does/not/exist/gemma3-1b-it-int4.task")

        assertEquals(LlmState.Unloaded, engine.state.value)
    }
}
