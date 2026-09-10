package org.ort.pipeline.digest

import org.ort.core.SystemClock
import org.ort.llm.CallsignShapeFilter
import org.ort.llm.LlmEngine
import org.ort.llm.LlmRequest
import org.ort.llm.LlmResult

/** What [ProseDigestGenerator.generate] did for one thread — never silently nothing (constitution I). */
public sealed interface ProseDigestOutcome {
    public data class Stored(public val summary: ProseSummary) : ProseDigestOutcome
    public data class Refused(public val reason: String) : ProseDigestOutcome
    public data class Failed(public val reason: String) : ProseDigestOutcome
}

/**
 * Produces one [ProseSummary] per thread from already-resolved entities and that thread's own
 * transcripts (FR-DIG-3, FR-DIG-4) and stores **only** filtered results (FR-DIG-4, D5) — a
 * refusal or an engine failure is reported back to the caller but never reaches
 * [ProseSummaryStore]. Never touches anything but [store]: no transmission, thread or station
 * record is read or written here, which is what makes the deterministic digest's independence
 * (FR-DIG-3a, AC-84, AC-140) automatic rather than a discipline someone has to remember.
 */
public class ProseDigestGenerator(
    private val engine: LlmEngine,
    private val store: ProseSummaryStore,
    private val modelId: String,
    private val promptBuilder: ProsePromptBuilder = ProsePromptBuilder(),
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
) {

    public suspend fun generate(
        threadId: String,
        resolvedCallsigns: Set<String>,
        transcripts: List<TimestampedTranscript>,
        sourceTransmissionIds: List<String>,
    ): ProseDigestOutcome {
        val input = ThreadDigestInput(threadId, resolvedCallsigns, transcripts)
        val prompt = promptBuilder.build(input)
        val request = LlmRequest(prompt = prompt, maxTokens = maxTokens, allowedCallsigns = resolvedCallsigns)

        return when (val result = engine.generate(request)) {
            is LlmResult.Text -> handleGenerated(result.text, resolvedCallsigns, threadId, sourceTransmissionIds)
            is LlmResult.Refused -> ProseDigestOutcome.Refused(result.reason)
            is LlmResult.Failed -> ProseDigestOutcome.Failed(result.reason)
        }
    }

    private suspend fun handleGenerated(
        text: String,
        resolvedCallsigns: Set<String>,
        threadId: String,
        sourceTransmissionIds: List<String>,
    ): ProseDigestOutcome = when (val filtered = CallsignShapeFilter.filter(text, resolvedCallsigns)) {
        is LlmResult.Text -> {
            val summary = ProseSummary(
                threadId = threadId,
                text = filtered.text,
                sourceTransmissionIds = sourceTransmissionIds,
                generatedAtMillis = SystemClock.wallMillis(),
                modelId = modelId,
            )
            store.store(summary)
            ProseDigestOutcome.Stored(summary)
        }
        is LlmResult.Refused -> ProseDigestOutcome.Refused(filtered.reason)
        // CallsignShapeFilter never returns Failed (its own doc comment) — handled defensively
        // rather than assumed, so a future change to that contract cannot silently crash here.
        is LlmResult.Failed -> ProseDigestOutcome.Failed(filtered.reason)
    }

    public companion object {
        public const val DEFAULT_MAX_TOKENS: Int = 256
    }
}
