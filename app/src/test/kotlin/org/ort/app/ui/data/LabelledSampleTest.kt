package org.ort.app.ui.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Build-plan P16, FR-OBS-4: record a labelled sample from a live session into the format
 * `corpus/`'s harness already reads, per `docs/reference/labelling-protocol.md`. That document's
 * own "Output format" section is authoritative — this test asserts against its exact column
 * list and vocabulary, not a paraphrase of it, since `corpus/` will one day parse this file by
 * that document's contract, not by whatever `:app` happens to emit.
 */
class LabelledSampleTest {

    private fun sample(
        callsign: String = "K7ABC",
        certainty: LabelCertainty? = LabelCertainty.CERTAIN,
        outcome: LabelOutcome = LabelOutcome.SPEECH,
        doubled: Boolean = false,
        tactical: String = "",
        threadId: String = "1",
        note: String = "",
    ) = LabelledSample(
        sessionId = "SESSION01",
        startSample = 16_000L,
        endSample = 32_000L,
        outcome = outcome,
        doubled = doubled,
        callsign = callsign,
        certainty = certainty,
        tactical = tactical,
        threadId = threadId,
        note = note,
    )

    @Test
    fun `the header matches the protocol's exact column list`() {
        assertEquals(
            "session_id\tstart_sample\tend_sample\toutcome\tdoubled\tcallsign\tcertainty\ttactical\tthread_id\tnote",
            LabelledSampleFormatter.HEADER,
        )
    }

    @Test
    fun `a certain callsign formats every column in order`() {
        val row = LabelledSampleFormatter.formatRow(sample())

        assertEquals("SESSION01\t16000\t32000\tspeech\tfalse\tK7ABC\tcertain\t\t1\t", row)
    }

    @Test
    fun `outcome and certainty use the protocol's exact vocabulary`() {
        val row = LabelledSampleFormatter.formatRow(
            sample(outcome = LabelOutcome.DOUBLED_UNRESOLVABLE, doubled = true, callsign = "", certainty = null),
        )

        assertEquals("SESSION01\t16000\t32000\tdoubled_unresolvable\ttrue\t\t\t\t1\t", row)
    }

    @Test
    fun `a negative example carries an empty callsign distinct from uncertain or partial`() {
        val row = LabelledSampleFormatter.formatRow(
            sample(callsign = "", certainty = null, outcome = LabelOutcome.NON_SPEECH),
        )

        assertEquals("SESSION01\t16000\t32000\tnon_speech\tfalse\t\t\t\t1\t", row)
    }

    @Test
    fun `certainty must be present exactly when a callsign is present, per the protocol`() {
        assertThrows(IllegalArgumentException::class.java) {
            LabelledSampleFormatter.formatRow(sample(callsign = "K7ABC", certainty = null))
        }
        assertThrows(IllegalArgumentException::class.java) {
            LabelledSampleFormatter.formatRow(sample(callsign = "", certainty = LabelCertainty.CERTAIN))
        }
    }

    @Test
    fun `a tab or newline in a free-text field is refused rather than silently corrupting the TSV`() {
        assertThrows(IllegalArgumentException::class.java) {
            LabelledSampleFormatter.formatRow(sample(note = "line one\tand two"))
        }
    }

    @Test
    fun `appending to a new file writes the header once, then the row`(@TempDir dir: File) {
        val file = File(dir, "labels.tsv")

        LabelledSampleWriter.append(file, sample())
        LabelledSampleWriter.append(file, sample(callsign = "W7NPC"))

        val lines = file.readLines()
        assertEquals(3, lines.size)
        assertEquals(LabelledSampleFormatter.HEADER, lines[0])
        assertEquals(true, lines[1].contains("K7ABC"))
        assertEquals(true, lines[2].contains("W7NPC"))
    }

    @Test
    fun `appending to an existing non-empty file does not repeat the header`(@TempDir dir: File) {
        val file = File(dir, "labels.tsv")
        LabelledSampleWriter.append(file, sample())

        LabelledSampleWriter.append(file, sample(callsign = "W7NPC"))

        assertEquals(1, file.readLines().count { it == LabelledSampleFormatter.HEADER })
    }
}
