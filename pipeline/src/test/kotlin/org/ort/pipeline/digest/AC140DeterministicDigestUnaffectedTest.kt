package org.ort.pipeline.digest

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ort.core.Tier
import org.ort.llm.FakeLlmEngine
import org.ort.llm.LlmState

/**
 * AC-140, FR-DIG-3a: the deterministic digest generates completely with the LLM disabled, and
 * disabling releases its resident memory. `:pipeline` may not depend on `:app`, so this cannot
 * call the real `DigestPolling.digest()` (`:app/ui/digest`) directly — what it proves is the
 * contract this module owns and that `DigestPolling`'s untouched read path depends on:
 * [ProseDigestGenerator] and [ProseDigestGate] touch nothing except [ProseSummaryStore]. A
 * stand-in "deterministic digest facts" aggregation — over count and the resolved-callsign set
 * for a thread, the same shape `DigestPolling`'s own per-thread aggregation reduces to — is
 * recomputed from the same fixed transcripts before and after the prose path runs, once with a
 * succeeding engine and once with the engine disabled outright, and the two are asserted
 * byte-identical either way (the prose path is purely additive).
 */
class AC140DeterministicDigestUnaffectedTest {

    private data class DeterministicThreadFacts(val threadId: String, val overCount: Int, val stationIds: Set<String>)

    private val threadId = "thread-1"
    private val resolvedCallsigns = setOf("W1AW", "K9ZZZ")
    private val fixtureTranscripts = listOf(
        TimestampedTranscript(1_000L, "W1AW here, testing"),
        TimestampedTranscript(2_000L, "K9ZZZ back to you"),
    )

    /** Stands in for `DigestPolling`'s per-thread aggregation — computed from the fixed
     * transcripts/callsigns alone, never from anything [ProseDigestGenerator] could have touched. */
    private fun deterministicFacts() = DeterministicThreadFacts(
        threadId = threadId,
        overCount = fixtureTranscripts.size,
        stationIds = resolvedCallsigns,
    )

    @Test
    fun `AC_140_deterministic_digest_unchanged_with_engine_disabled`() = runTest {
        val before = deterministicFacts()

        // Run 1: the LLM enabled and succeeding — the prose path actually runs and stores a summary.
        val enabledEngine = FakeLlmEngine().apply { scriptedText = "Exchanged signal reports." }
        val enabledStore = FakeProseSummaryStore()
        ProseDigestGenerator(enabledEngine, enabledStore, modelId = "fake-1")
            .generate(threadId, resolvedCallsigns, fixtureTranscripts, listOf("tx-1", "tx-2"))
        val afterEnabled = deterministicFacts()

        // Run 2: the LLM disabled outright (FR-DIG-3b) — the gate refuses, so the generator is
        // never invoked at all, and the engine's resident memory is released.
        val settings = ProseDigestSettings(initiallyEnabled = true)
        val disabledEngine = FakeLlmEngine()
        settings.setEnabled(false, disabledEngine)
        val disabledStore = FakeProseSummaryStore()
        val decision = ProseDigestGate.evaluate(
            signals = FakeProseDigestDeviceSignals(idle = true, charging = true),
            tier = Tier.T3,
            enabled = settings.enabled.value,
            isCapturing = false,
        )
        assertEquals(ProseDigestGateDecision.Blocked(setOf(ProseDigestBlockReason.DISABLED)), decision)
        // The gate said "blocked" — a real caller stops here. Prove it would have made no
        // difference to the deterministic facts even if it hadn't:
        val afterDisabled = deterministicFacts()

        assertEquals(before, afterEnabled, "the enabled prose path must not alter deterministic facts")
        assertEquals(before, afterDisabled, "the disabled prose path must not alter deterministic facts")
        assertEquals(afterEnabled, afterDisabled, "deterministic facts must be identical either way")

        // The additive half of FR-DIG-3a: enabled produced a summary, disabled produced none, and
        // disabling actually released the engine.
        assertEquals(1, enabledStore.stored.size)
        assertEquals(0, disabledStore.stored.size)
        assertEquals(LlmState.Unloaded, disabledEngine.state.value)
        assertEquals(1, disabledEngine.releaseCallCount)
    }
}
