package org.ort.pipeline.digest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.reflect.full.declaredMemberProperties

/**
 * E2-I07, FR-DIG-12: the prompt builder's input carries *only* the closed field list — thread id,
 * resolved callsigns, transcripts with timestamps. Reflection over the data class rather than a
 * behavioural assertion on the rendered string, so the test fails the moment a field is added,
 * not only when someone remembers to feed it something incriminating.
 */
class ProsePromptBuilderTest {

    @Test
    fun `FR_DIG_12_thread_digest_input_field_list_is_closed`() {
        val threadDigestInputFields = ThreadDigestInput::class.declaredMemberProperties.map { it.name }.toSet()
        assertEquals(setOf("threadId", "resolvedCallsigns", "transcripts"), threadDigestInputFields)

        val transcriptFields = TimestampedTranscript::class.declaredMemberProperties.map { it.name }.toSet()
        assertEquals(setOf("timestampMillis", "text"), transcriptFields)
    }

    @Test
    fun `built prompt surfaces the thread id, every callsign and every transcript`() {
        val input = ThreadDigestInput(
            threadId = "thread-1",
            resolvedCallsigns = setOf("W1AW", "K9ZZZ"),
            transcripts = listOf(
                TimestampedTranscript(0L, "W1AW here, testing"),
                TimestampedTranscript(60_000L, "K9ZZZ back to you"),
            ),
        )

        val prompt = ProsePromptBuilder().build(input)

        assertTrue(prompt.contains("thread-1"))
        assertTrue(prompt.contains("W1AW"))
        assertTrue(prompt.contains("K9ZZZ"))
        assertTrue(prompt.contains("W1AW here, testing"))
        assertTrue(prompt.contains("K9ZZZ back to you"))
    }

    @Test
    fun `built prompt orders transcripts by timestamp regardless of input order`() {
        val input = ThreadDigestInput(
            threadId = "thread-1",
            resolvedCallsigns = emptySet(),
            transcripts = listOf(
                TimestampedTranscript(60_000L, "second"),
                TimestampedTranscript(0L, "first"),
            ),
        )

        val prompt = ProsePromptBuilder().build(input)

        assertTrue(prompt.indexOf("first") < prompt.indexOf("second"))
    }
}
