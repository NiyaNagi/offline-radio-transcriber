package org.ort.pipeline.export

import java.time.Instant

/**
 * Register R-1009 (WPX), FR-EXP-2: "Export as CSV with all fields including confidence and
 * provenance." One row per [ExportOverRecord], RFC4180-quoted where a field needs it. Timestamps
 * are [Instant.toString] (always `yyyy-MM-ddTHH:mm:ssZ` UTC, locale-independent by contract —
 * never [java.text.SimpleDateFormat] or a locale-formatted number, per this round's own testing
 * rule) and [ExportOverRecord.frequencyHz] is written as the raw integer, never a locale-decimal
 * MHz string.
 */
public object CsvExportWriter {

    private val COLUMNS = listOf(
        "transmission_id", "session_id", "started_at_utc", "ended_at_utc", "frequency_hz",
        "mode", "channel_name", "attribution_state", "callsign", "confidence", "corrected",
        "transcript_model_id", "transcript_model_version", "transcript_text",
    )

    public fun write(records: List<ExportOverRecord>): String {
        val lines = mutableListOf(COLUMNS.joinToString(","))
        for (record in records) {
            val cells = record.attribution.toCells()
            lines += listOf(
                record.transmissionId,
                record.sessionId,
                Instant.ofEpochMilli(record.startedAtUtcMillis).toString(),
                record.endedAtUtcMillis?.let { Instant.ofEpochMilli(it).toString() } ?: "",
                record.frequencyHz?.toString() ?: "",
                record.mode ?: "",
                record.channelName ?: "",
                cells.stateTag,
                cells.callsignCell,
                cells.confidenceCell,
                cells.correctedCell,
                record.transcriptModelId ?: "",
                record.transcriptModelVersion ?: "",
                record.transcriptText ?: "",
            ).joinToString(",", transform = ::csvField)
        }
        return lines.joinToString("\n")
    }

    /** The characters RFC4180 forces a field to be quoted for — a comma, a quote or either
     * newline convention. Named as a set (rather than a chained `||` condition) purely to keep
     * detekt's `ComplexCondition` threshold happy; the rule itself is unchanged. */
    private val FIELDS_NEEDING_QUOTES = setOf(',', '"', '\n', '\r')

    /** RFC4180: a field is quoted whenever it contains a comma, a quote or a newline; an internal
     * quote is doubled. Never a library — this project has no CSV dependency and the rule is
     * three lines. */
    private fun csvField(value: String): String = if (value.any { it in FIELDS_NEEDING_QUOTES }) {
        "\"" + value.replace("\"", "\"\"") + "\""
    } else {
        value
    }
}
