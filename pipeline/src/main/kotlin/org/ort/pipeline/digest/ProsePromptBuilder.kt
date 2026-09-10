package org.ort.pipeline.digest

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * One transcript line the prompt may quote, with the timestamp it was heard at (FR-DIG-11's
 * "reported speech" needs a "when").
 */
public data class TimestampedTranscript(public val timestampMillis: Long, public val text: String)

/**
 * FR-DIG-12's closed field list, as a type: **exactly** the thread id, the resolved callsigns for
 * that thread, and its transcripts with timestamps — nothing else reaches the model. No station
 * facts, no user-supplied names, no location (FR-DIG-13, FR-SPK-20/25). Adding a field here is a
 * spec change, not a refactor — `FR_DIG_12_thread_digest_input_field_list_is_closed`
 * (`ProsePromptBuilderTest`) fails the moment this type grows one.
 */
public data class ThreadDigestInput(
    public val threadId: String,
    public val resolvedCallsigns: Set<String>,
    public val transcripts: List<TimestampedTranscript>,
)

/**
 * Renders [ThreadDigestInput] into the literal prompt text handed to [org.ort.llm.LlmEngine]
 * (FR-DIG-3, FR-DIG-4, FR-DIG-11). The instruction preamble is a fixed constant, not a field of
 * the input — it carries no per-thread data of its own, so it does not widen the closed field
 * list above; it exists to push the model toward FR-DIG-11's "reported speech" phrasing and away
 * from naming a callsign outside the allowed list, in addition to (never instead of)
 * [org.ort.llm.CallsignShapeFilter]'s hard post-filter.
 */
public class ProsePromptBuilder {

    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC)

    public fun build(input: ThreadDigestInput): String = buildString {
        appendLine(INSTRUCTION)
        appendLine()
        appendLine("Thread: ${input.threadId}")
        appendLine("Callsigns heard in this thread: ${input.resolvedCallsigns.sorted().joinToString(", ")}")
        appendLine("Transcripts:")
        input.transcripts.sortedBy { it.timestampMillis }.forEach { transcript ->
            val time = timeFormat.format(Instant.ofEpochMilli(transcript.timestampMillis))
            appendLine("[$time] ${transcript.text}")
        }
    }

    private companion object {
        const val INSTRUCTION = "Summarize only what was transmitted in this thread, in one or two sentences. " +
            "Phrase it as reported speech (for example, \"said they were working on an antenna\"), never as an " +
            "asserted fact about a person. Name only callsigns that appear in the list below — never any other " +
            "callsign, invented or otherwise."
    }
}
