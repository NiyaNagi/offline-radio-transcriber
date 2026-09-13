package org.ort.pipeline.export

import java.time.Instant

/**
 * Register R-1009 (WPX). JSON is one of the four formats `Settings-Export.dc.html` already
 * offers alongside ADIF/CSV/text. No JSON library is on this module's classpath (checked before
 * writing this), so this is a small, deliberately-scoped hand-rolled writer — every value this
 * package ever needs to emit is a string, a finite double, a boolean or `null`, so a general
 * parser/serializer would be more surface than the problem needs.
 *
 * **FR-EXP-4**: `attribution` is always a nested object carrying `state` — never flattened onto
 * the row, and never omitted — so a JSON consumer cannot read a `callsign` field without also
 * seeing the `state` field beside it in the same object. `callsign`/`confidence` are JSON `null`
 * (never an empty string, which a consumer could mistake for "known but blank") for
 * `AMBIGUOUS`/`UNKNOWN` rows, and `confidence` alone is `null` for a real, callsign-known row with
 * no calibrated probability (a human correction — `ExportAttribution.CallsignKnown`'s own kdoc).
 * `note` (register R-1039) is `null` except for `ExportAttribution.UnresolvedCallsign`, where it
 * carries the real reason a resolved (`CONFIRMED`/`INFERRED`) row cannot name a callsign at all.
 */
public object JsonExportWriter {

    public fun write(records: List<ExportOverRecord>): String {
        if (records.isEmpty()) return "[]"
        val body = records.joinToString(",\n") { record -> "  " + recordObject(record) }
        return "[\n$body\n]"
    }

    private fun recordObject(record: ExportOverRecord): String {
        val cells = record.attribution.toCells()
        val fields = listOf(
            "transmissionId" to jsonString(record.transmissionId),
            "sessionId" to jsonString(record.sessionId),
            "startedAtUtc" to jsonString(Instant.ofEpochMilli(record.startedAtUtcMillis).toString()),
            "endedAtUtc" to (
                record.endedAtUtcMillis?.let {
                    jsonString(Instant.ofEpochMilli(it).toString())
                } ?: "null"
                ),
            "frequencyHz" to (record.frequencyHz?.toString() ?: "null"),
            "mode" to jsonNullableString(record.mode),
            "channelName" to jsonNullableString(record.channelName),
            "attribution" to attributionObject(record.attribution, cells),
            "transcriptText" to jsonNullableString(record.transcriptText),
            "transcriptModelId" to jsonNullableString(record.transcriptModelId),
            "transcriptModelVersion" to jsonNullableString(record.transcriptModelVersion),
        )
        return "{" + fields.joinToString(",") { (k, v) -> "\"$k\":$v" } + "}"
    }

    private fun attributionObject(attribution: ExportAttribution, cells: AttributionCells): String {
        val callsign = if (attribution is ExportAttribution.CallsignKnown) jsonString(cells.callsignCell) else "null"
        // `Double.toString()` is always locale-independent (fixed decimal notation, unlike
        // `String.format`) — safe to use directly here without a `Locale.ROOT` argument. `null`
        // both when the state itself carries no callsign at all, and — register R-1039 — when a
        // CallsignKnown row's own confidence is genuinely absent (a human correction,
        // `ExportAttribution.CallsignKnown`'s own kdoc): never a fabricated `0.0` standing in for
        // "no calibrated probability exists".
        val confidence = if (attribution is ExportAttribution.CallsignKnown) {
            attribution.confidence?.toString() ?: "null"
        } else {
            "null"
        }
        val corrected = if (attribution is ExportAttribution.CallsignKnown) attribution.corrected.toString() else "null"
        // Register R-1039: `null` for every row except ExportAttribution.UnresolvedCallsign — the
        // one case where the state is real but this row cannot name a callsign, so a reader needs
        // the reason alongside it (constitution I).
        val note = if (cells.noteCell.isNotEmpty()) jsonString(cells.noteCell) else "null"
        return "{" +
            "\"state\":${jsonString(cells.stateTag)}," +
            "\"callsign\":$callsign," +
            "\"confidence\":$confidence," +
            "\"corrected\":$corrected," +
            "\"note\":$note" +
            "}"
    }

    private fun jsonNullableString(value: String?): String = value?.let { jsonString(it) } ?: "null"

    private fun jsonString(value: String): String {
        val escaped = StringBuilder("\"")
        for (c in value) {
            when (c) {
                '"' -> escaped.append("\\\"")
                '\\' -> escaped.append("\\\\")
                '\n' -> escaped.append("\\n")
                '\r' -> escaped.append("\\r")
                '\t' -> escaped.append("\\t")
                else -> escaped.append(c)
            }
        }
        escaped.append("\"")
        return escaped.toString()
    }
}
