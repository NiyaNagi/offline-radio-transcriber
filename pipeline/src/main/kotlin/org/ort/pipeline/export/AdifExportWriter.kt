package org.ort.pipeline.export

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Register R-1009 (WPX), FR-EXP-1: "Export stations heard as ADIF, for logging software." ADIF
 * (the Amateur Data Interchange Format) records a QSO as a sequence of `<FIELD:LENGTH>value`
 * tags terminated by `<EOR>`, and its `CALL` field is how every logging program identifies who
 * was worked — the format has no field meaning "unidentified station".
 *
 * **The decision this writer makes about that gap** (constitution I: never fabricate, never drop
 * silently): an [ExportAttribution.Ambiguous] or [ExportAttribution.Unknown] over is **not**
 * written as a QSO record at all — inventing a `CALL` value (even a placeholder like `UNKNOWN`,
 * which a logging program could read back as a literal, confusable callsign) would be worse than
 * omitting the record. Instead, every excluded over is named in the ADIF **header** — the
 * free-text block ADIF itself allows before `<EOH>` — with its transmission id, timestamp,
 * frequency and attribution state, so the file states what happened rather than pretending those
 * overs never occurred. `AdifExportWriterTest`'s own FR-EXP-4 tests prove this both ways: no
 * `<CALL:` field for an unidentified over, and no `<EOR>` record for one either — but its
 * transmission id is still present in the header text.
 *
 * `CONFIRMED` and `INFERRED` overs both become QSO records; every writer in this package must
 * still distinguish them (constitution I), which this one does with the `APP_ORT_ATTRIBUTION_STATE`
 * and `APP_ORT_CONFIDENCE` application-defined fields ADIF's own spec reserves for exactly this
 * (`APP_<PROGRAMID>_<FIELDNAME>`) — never a variant of "verified"/"confirmed" baked into `CALL` or
 * `NOTES` where a careless reader could miss it.
 */
public object AdifExportWriter {

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT).withZone(ZoneOffset.UTC)
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC)

    public fun write(records: List<ExportOverRecord>): String {
        val (named, excluded) = records.partition { it.attribution is ExportAttribution.CallsignKnown }

        val header = StringBuilder()
        header.append("Offline Radio Transcriber ADIF export\n")
        header.append("ADIF_VER:3.1.4\n")
        if (excluded.isNotEmpty()) {
            header.append(
                "${excluded.size} transmission(s) excluded: no station identified " +
                    "(AMBIGUOUS or UNKNOWN attribution) — never fabricated as a callsign:\n",
            )
            for (record in excluded) {
                val cells = record.attribution.toCells()
                header.append(
                    "  ${record.transmissionId} " +
                        "${Instant.ofEpochMilli(record.startedAtUtcMillis)} " +
                        "${record.frequencyHz ?: "unknown"}Hz (${cells.stateTag})\n",
                )
            }
        }
        header.append("<EOH>\n")

        val body = named.joinToString("") { record -> qsoRecord(record) }
        return header.toString() + body
    }

    private fun qsoRecord(record: ExportOverRecord): String {
        val cells = record.attribution.toCells()
        val instant = Instant.ofEpochMilli(record.startedAtUtcMillis)
        val fields = mutableListOf(
            adifField("CALL", cells.callsignCell),
            adifField("QSO_DATE", DATE_FORMAT.format(instant)),
            adifField("TIME_ON", TIME_FORMAT.format(instant)),
        )
        record.frequencyHz?.let { fields += adifField("FREQ", "%.6f".format(Locale.ROOT, it / 1_000_000.0)) }
        record.mode?.let { fields += adifField("MODE", it) }
        fields += adifField("APP_ORT_ATTRIBUTION_STATE", cells.stateTag)
        fields += adifField("APP_ORT_CONFIDENCE", cells.confidenceCell)
        record.transcriptText?.let { fields += adifField("NOTES", it) }
        return fields.joinToString("") + "<EOR>\n"
    }

    /** `<FIELDNAME:LENGTH>value` — [length] is the value's own real character count, per ADIF's
     * own rule, never a guessed or rounded one (a mismatched length is a malformed record every
     * logging program would reject or mis-truncate). */
    private fun adifField(name: String, value: String): String = "<$name:${value.length}>$value"
}
