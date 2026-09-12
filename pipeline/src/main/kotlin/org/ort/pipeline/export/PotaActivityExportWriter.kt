package org.ort.pipeline.export

import java.time.Instant

/**
 * Register R-1009 (WPX), FR-EXP-3: "Export POTA-relevant activity (park reference, station,
 * frequency, time)." Scoped strictly to overs whose attributed station carries at least one park
 * reference ([ExportOverRecord.potaRefs]) — everything else is out of scope for *this* export,
 * not for the general log (`CsvExportWriter` already covers every over regardless of POTA
 * relevance). An over naming more than one park reference (a multi-park activation) produces one
 * row per reference, never a single row silently naming only the first.
 *
 * FR-EXP-4 holds here too: an unidentified station (`AMBIGUOUS`/`UNKNOWN`) is still POTA-relevant
 * if its over names a park reference — the row is still emitted (a park activation with an
 * unidentified caller is still real activity worth a conservative operator seeing), but the
 * `station` cell is never a fabricated callsign, only [AttributionCells.callsignCell]'s own
 * honest placeholder, alongside the real `attribution_state`.
 */
public object PotaActivityExportWriter {

    private val COLUMNS = listOf("park_reference", "station", "attribution_state", "frequency_hz", "time_utc")

    public fun write(records: List<ExportOverRecord>): String {
        val lines = mutableListOf(COLUMNS.joinToString(","))
        for (record in records) {
            if (record.potaRefs.isEmpty()) continue
            val cells = record.attribution.toCells()
            val time = Instant.ofEpochMilli(record.startedAtUtcMillis).toString()
            for (parkRef in record.potaRefs) {
                lines += listOf(
                    parkRef,
                    cells.callsignCell,
                    cells.stateTag,
                    record.frequencyHz?.toString() ?: "",
                    time,
                ).joinToString(",")
            }
        }
        return lines.joinToString("\n")
    }
}
