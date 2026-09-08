package org.ort.app.ui.data

import java.io.File
import java.io.FileWriter

/**
 * Build-plan P16, FR-OBS-4: record a labelled sample from a live session in the format
 * `corpus/`'s harness already reads, per `docs/reference/labelling-protocol.md`'s "Output
 * format" section — a plain TSV, one row per transmission, with columns:
 * `session_id start_sample end_sample outcome doubled callsign certainty tactical thread_id note`.
 *
 * This is the producer side only. The protocol document itself says the TSV-to-`LabeledOccurrence`
 * conversion `corpus/`'s harness will eventually need is "not built and is a real, separate
 * follow-up task" — this file does not invent that conversion or a JSON schema; it follows the
 * TSV column order the protocol document already specifies, exactly.
 */
public enum class LabelOutcome(public val wire: String) {
    SPEECH("speech"),
    NO_SPEECH("no_speech"),
    DOUBLED_UNRESOLVABLE("doubled_unresolvable"),
    NON_SPEECH("non_speech"),
}

public enum class LabelCertainty(public val wire: String) {
    CERTAIN("certain"),
    UNCERTAIN("uncertain"),
    PARTIAL("partial"),
}

/**
 * One row. [certainty] is non-null exactly when [callsign] is non-blank (the protocol: "empty
 * when `callsign` is empty") — [LabelledSampleFormatter.formatRow] enforces this rather than
 * trusting every caller to have gotten it right.
 */
public data class LabelledSample(
    val sessionId: String,
    val startSample: Long,
    val endSample: Long,
    val outcome: LabelOutcome,
    val doubled: Boolean,
    val callsign: String,
    val certainty: LabelCertainty?,
    val tactical: String,
    val threadId: String,
    val note: String,
)

public object LabelledSampleFormatter {

    public const val HEADER: String =
        "session_id\tstart_sample\tend_sample\toutcome\tdoubled\tcallsign\tcertainty\ttactical\tthread_id\tnote"

    public fun formatRow(sample: LabelledSample): String {
        require((sample.callsign.isBlank()) == (sample.certainty == null)) {
            "certainty must be set exactly when callsign is non-blank (labelling-protocol.md); " +
                "got callsign='${sample.callsign}', certainty=${sample.certainty}"
        }
        val fields = listOf(
            sample.sessionId,
            sample.startSample.toString(),
            sample.endSample.toString(),
            sample.outcome.wire,
            sample.doubled.toString(),
            sample.callsign,
            sample.certainty?.wire.orEmpty(),
            sample.tactical,
            sample.threadId,
            sample.note,
        )
        require(fields.none { it.contains('\t') || it.contains('\n') }) {
            "a field contains a tab or newline, which would corrupt the TSV: $fields"
        }
        return fields.joinToString("\t")
    }
}

/** Appends one row to [file], writing [LabelledSampleFormatter.HEADER] first iff the file is new or empty. */
public object LabelledSampleWriter {

    public fun append(file: File, sample: LabelledSample) {
        val needsHeader = !file.exists() || file.length() == 0L
        file.parentFile?.mkdirs()
        FileWriter(file, true).use { writer ->
            if (needsHeader) {
                writer.write(LabelledSampleFormatter.HEADER)
                writer.write("\n")
            }
            writer.write(LabelledSampleFormatter.formatRow(sample))
            writer.write("\n")
        }
    }
}
