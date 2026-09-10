package org.ort.pipeline.digest

import org.ort.core.Tier
import org.ort.llm.LlmEngine
import org.ort.llm.LlmLoadResult
import org.ort.llm.LlmState
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.ShedStatus

/**
 * The same "current tier" formula `:app`'s `DigestPolling` and `:pipeline`'s
 * `RealCaptureService` already derive from [ShedStatus] (the brief's own pointer: "how the
 * current tier is exposed — search `deviceTier`/`Tier` in `:pipeline`"). Kept local to this
 * package rather than added to [ShedStatus] itself, which this package does not own.
 */
private fun currentTierFromShedStatus(): Tier {
    val maxOrdinal = Tier.entries.size - 1
    val ordinal = (maxOrdinal - ShedStatus.currentLevel).coerceIn(0, maxOrdinal)
    return Tier.entries[ordinal]
}

/** What one [ProseDigestWorkRunner.run] call did — never silently nothing (constitution I). */
public sealed interface ProseDigestRunOutcome {
    /** The gate refused before any thread was even looked at — [reasons] names every failed conjunct. */
    public data class NotEligible(public val reasons: Set<ProseDigestBlockReason>) : ProseDigestRunOutcome

    /** Every pending thread was evaluated; [generatedCount] of [threadCount] actually produced a stored summary
     * (a per-thread refusal/failure from [ProseDigestGenerator] is not a mid-run stop). */
    public data class Completed(public val generatedCount: Int, public val threadCount: Int) : ProseDigestRunOutcome

    /** The gate flipped mid-run (AC-87) — [generatedCount] threads finished before it did; the engine was released. */
    public data class StoppedMidRun(public val generatedCount: Int, public val reasons: Set<ProseDigestBlockReason>) :
        ProseDigestRunOutcome

    /** The gate held, but the engine itself would not load. */
    public data class EngineLoadFailed(public val reason: String) : ProseDigestRunOutcome
}

/**
 * The scheduling decision plus one run of prose-digest generation, kept independent of
 * WorkManager entirely so it can be tested without any Android Worker machinery (constitution
 * II) — [ProseDigestRunner] (the real `androidx.work.CoroutineWorker`) is a thin adapter over
 * this that only supplies real Android-backed dependencies.
 *
 * The gate is evaluated **once before starting** and **again before every thread** — AC-87's
 * "never runs during active capture" together with "stops if the gate flips mid-run": a run that
 * starts idle-and-charging must not keep going to completion once the operator picks the phone
 * up, unplugs it, or capture starts, or the tier drops, or the feature is disabled mid-run. A
 * mid-run stop releases [engine] itself — defence in depth alongside whatever already called
 * [ProseDigestSettings.setEnabled] — because the guarantee (FR-DIG-3b: disabling frees resident
 * memory) must hold even when the flip that stopped this run was a *different* conjunct (tier,
 * capture, charging, idle) than an explicit disable.
 */
public class ProseDigestWorkRunner(
    private val signals: ProseDigestDeviceSignals,
    private val settings: ProseDigestSettings,
    private val engine: LlmEngine,
    private val store: ProseSummaryStore,
    private val source: suspend () -> List<PendingThreadDigest>,
    private val modelId: String,
    private val tierProvider: () -> Tier = ::currentTierFromShedStatus,
    private val isCapturing: () -> Boolean = { CaptureState.isCapturing },
) {
    private fun evaluate(): ProseDigestGateDecision = ProseDigestGate.evaluate(
        signals = signals,
        tier = tierProvider(),
        enabled = settings.enabled.value,
        isCapturing = isCapturing(),
    )

    public suspend fun run(): ProseDigestRunOutcome {
        val initial = evaluate()
        if (initial is ProseDigestGateDecision.Blocked) {
            return ProseDigestRunOutcome.NotEligible(initial.reasons)
        }

        val pending = source()
        if (pending.isEmpty()) return ProseDigestRunOutcome.Completed(generatedCount = 0, threadCount = 0)

        if (engine.state.value !is LlmState.Ready) {
            when (val loaded = engine.load()) {
                is LlmLoadResult.Failed -> return ProseDigestRunOutcome.EngineLoadFailed(loaded.reason)
                LlmLoadResult.Loaded -> Unit
            }
        }

        val generator = ProseDigestGenerator(engine, store, modelId)
        var generated = 0
        for (thread in pending) {
            val decision = evaluate()
            if (decision is ProseDigestGateDecision.Blocked) {
                engine.release()
                return ProseDigestRunOutcome.StoppedMidRun(generated, decision.reasons)
            }
            val outcome = generator.generate(
                thread.threadId,
                thread.input.resolvedCallsigns,
                thread.input.transcripts,
                thread.sourceTransmissionIds,
            )
            if (outcome is ProseDigestOutcome.Stored) generated++
        }
        return ProseDigestRunOutcome.Completed(generated, pending.size)
    }
}
