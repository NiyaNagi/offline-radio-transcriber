package org.ort.pipeline.digest

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.core.Tier
import org.ort.llm.FakeLlmEngine
import org.ort.llm.LlmState

/**
 * The scheduling/decision layer `ProseDigestRunner` (the real `CoroutineWorker`) delegates to —
 * tested with no Android/WorkManager machinery at all (constitution II). Covers the coordinator's
 * three named cases: a device-signals fake driving the gate, the runner refusing while capturing
 * (AC-87), and disabling mid-run releasing the engine (FR-DIG-3b).
 */
class ProseDigestWorkRunnerTest {

    private fun thread(id: String, text: String) = PendingThreadDigest(
        threadId = id,
        input = ThreadDigestInput(id, setOf("W1AW"), listOf(TimestampedTranscript(0L, text))),
        sourceTransmissionIds = listOf("$id-tx1"),
    )

    private fun readySignals() = FakeProseDigestDeviceSignals(idle = true, charging = true)

    @Test
    fun `the device-signals fake drives the gate -- not charging refuses before touching the source or engine`() =
        runTest {
            var sourceCalled = false
            val engine = FakeLlmEngine()
            val runner = ProseDigestWorkRunner(
                signals = FakeProseDigestDeviceSignals(idle = true, charging = false),
                settings = ProseDigestSettings(),
                engine = engine,
                store = FakeProseSummaryStore(),
                source = {
                    sourceCalled = true
                    listOf(thread("t1", "hello"))
                },
                modelId = "fake-model",
                tierProvider = { Tier.T3 },
                isCapturing = { false },
            )

            val outcome = runner.run()

            assertEquals(ProseDigestRunOutcome.NotEligible(setOf(ProseDigestBlockReason.NOT_CHARGING)), outcome)
            assertTrue(!sourceCalled, "a blocked gate must never even look for pending threads")
            assertEquals(0, engine.loadCallCount)
        }

    @Test
    fun `AC_87_the_runner_refuses_while_capturing`() = runTest {
        var sourceCalled = false
        val engine = FakeLlmEngine()
        val runner = ProseDigestWorkRunner(
            signals = readySignals(),
            settings = ProseDigestSettings(),
            engine = engine,
            store = FakeProseSummaryStore(),
            source = {
                sourceCalled = true
                listOf(thread("t1", "hello"))
            },
            modelId = "fake-model",
            tierProvider = { Tier.T3 },
            isCapturing = { true },
        )

        val outcome = runner.run()

        assertEquals(ProseDigestRunOutcome.NotEligible(setOf(ProseDigestBlockReason.CAPTURING)), outcome)
        assertTrue(!sourceCalled, "must never fetch pending threads while capturing")
        assertEquals(0, engine.loadCallCount, "must never load the engine while capturing")
    }

    @Test
    fun `a below-T3 tier refuses before touching the engine`() = runTest {
        val engine = FakeLlmEngine()
        val runner = ProseDigestWorkRunner(
            signals = readySignals(),
            settings = ProseDigestSettings(),
            engine = engine,
            store = FakeProseSummaryStore(),
            source = { listOf(thread("t1", "hello")) },
            modelId = "fake-model",
            tierProvider = { Tier.T2 },
            isCapturing = { false },
        )

        val outcome = runner.run()

        assertEquals(ProseDigestRunOutcome.NotEligible(setOf(ProseDigestBlockReason.TIER_BELOW_T3)), outcome)
        assertEquals(0, engine.loadCallCount)
    }

    @Test
    fun `every eligible pending thread is generated and stored when the gate holds throughout`() = runTest {
        val engine = FakeLlmEngine().apply { scriptedText = "Exchanged signal reports." }
        val store = FakeProseSummaryStore()
        val runner = ProseDigestWorkRunner(
            signals = readySignals(),
            settings = ProseDigestSettings(),
            engine = engine,
            store = store,
            source = { listOf(thread("t1", "a"), thread("t2", "b")) },
            modelId = "fake-model",
            tierProvider = { Tier.T3 },
            isCapturing = { false },
        )

        val outcome = runner.run()

        assertEquals(ProseDigestRunOutcome.Completed(generatedCount = 2, threadCount = 2), outcome)
        assertEquals(2, store.stored.size)
        assertEquals(1, engine.loadCallCount)
    }

    @Test
    fun `the runner stops mid-run and releases the engine when the gate flips (AC-87)`() = runTest {
        var capturingNow = false
        val engine = FakeLlmEngine().apply { scriptedText = "First thread done." }
        // Flips "capturing" to true as a side effect of the first thread's own storage --
        // proves the runner's per-thread re-check, not just its pre-flight one, actually stops it.
        val flippingStore = object : ProseSummaryStore {
            private val backing = FakeProseSummaryStore()
            override suspend fun store(summary: ProseSummary) {
                backing.store(summary)
                capturingNow = true
            }
            override suspend fun forThread(threadId: String) = backing.forThread(threadId)
            override suspend fun forThreads(threadIds: Collection<String>) = backing.forThreads(threadIds)
            override suspend fun all() = backing.all()
        }
        val runner = ProseDigestWorkRunner(
            signals = readySignals(),
            settings = ProseDigestSettings(),
            engine = engine,
            store = flippingStore,
            source = { listOf(thread("t1", "a"), thread("t2", "b"), thread("t3", "c")) },
            modelId = "fake-model",
            tierProvider = { Tier.T3 },
            isCapturing = { capturingNow },
        )

        val outcome = runner.run()

        assertEquals(ProseDigestRunOutcome.StoppedMidRun(1, setOf(ProseDigestBlockReason.CAPTURING)), outcome)
        assertEquals(1, flippingStore.all().size, "only the thread generated before the flip is stored")
        assertEquals(LlmState.Unloaded, engine.state.value, "a mid-run stop must release the engine")
        assertTrue(engine.releaseCallCount >= 1)
    }

    @Test
    fun `FR_DIG_3b_disabling_mid_run_releases_the_engine`() = runTest {
        val engine = FakeLlmEngine().apply { scriptedText = "First thread done." }
        val store = FakeProseSummaryStore()
        val settingsStore = InMemoryProseDigestSettingsStore(initiallyEnabled = true)
        val settings = ProseDigestSettings(settingsStore)
        // Simulates the operator flipping the CF04 toggle off partway through a run: the very
        // act of disabling releases the engine (ProseDigestSettings' own contract), and the
        // runner's next per-thread gate check must also see it and stop, never generating t2/t3.
        val disablingSource: suspend () -> List<PendingThreadDigest> = {
            settings.setEnabled(false, engine)
            listOf(thread("t1", "a"), thread("t2", "b"))
        }
        val runner = ProseDigestWorkRunner(
            signals = readySignals(),
            settings = settings,
            engine = engine,
            store = store,
            source = disablingSource,
            modelId = "fake-model",
            tierProvider = { Tier.T3 },
            isCapturing = { false },
        )

        val outcome = runner.run()

        assertEquals(ProseDigestRunOutcome.StoppedMidRun(0, setOf(ProseDigestBlockReason.DISABLED)), outcome)
        assertEquals(0, store.stored.size, "no thread may be generated once disabled")
        assertEquals(LlmState.Unloaded, engine.state.value)
        assertTrue(engine.releaseCallCount >= 1)
    }

    @Test
    fun `an engine that fails to load is reported honestly, never silently skipped`() = runTest {
        val engine = FakeLlmEngine().apply {
            loadBehavior = FakeLlmEngine.LoadBehavior.FAIL
            loadFailureReason =
                "no memory"
        }
        val runner = ProseDigestWorkRunner(
            signals = readySignals(),
            settings = ProseDigestSettings(),
            engine = engine,
            store = FakeProseSummaryStore(),
            source = { listOf(thread("t1", "a")) },
            modelId = "fake-model",
            tierProvider = { Tier.T3 },
            isCapturing = { false },
        )

        val outcome = runner.run()

        assertEquals(ProseDigestRunOutcome.EngineLoadFailed("no memory"), outcome)
    }

    @Test
    fun `no pending threads completes without ever loading the engine`() = runTest {
        val engine = FakeLlmEngine()
        val runner = ProseDigestWorkRunner(
            signals = readySignals(),
            settings = ProseDigestSettings(),
            engine = engine,
            store = FakeProseSummaryStore(),
            source = { emptyList() },
            modelId = "fake-model",
            tierProvider = { Tier.T3 },
            isCapturing = { false },
        )

        val outcome = runner.run()

        assertEquals(ProseDigestRunOutcome.Completed(0, 0), outcome)
        assertEquals(0, engine.loadCallCount)
    }
}
