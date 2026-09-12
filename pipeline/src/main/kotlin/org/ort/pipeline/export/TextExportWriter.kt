package org.ort.pipeline.export

import java.time.Instant
import java.util.Locale

/**
 * Register R-1009 (WPX). Plain text is the fourth format `Settings-Export.dc.html` already
 * offers — a human-readable line per over, for reading rather than importing into other software
 * (that is what ADIF/CSV/JSON are for). FR-EXP-4 holds here exactly as in every other format: the
 * state always travels with the callsign, in the same bracketed clause, never separated.
 */
public object TextExportWriter {

    public fun write(records: List<ExportOverRecord>): String = records.joinToString("\n") { record -> line(record) }

    private fun line(record: ExportOverRecord): String {
        val cells = record.attribution.toCells()
        val time = Instant.ofEpochMilli(record.startedAtUtcMillis).toString()
        val freq = record.frequencyHz?.let { "%.3f MHz".format(Locale.ROOT, it / 1_000_000.0) + " ($it Hz)" }
            ?: "frequency not reported"
        val who = "${cells.callsignCell} (${cells.stateTag}${
            if (cells.confidenceCell.isNotEmpty()) ", ${cells.confidenceCell}" else ""
        })"
        val transcript = record.transcriptText?.let { " — $it" } ?: ""
        return "$time · $freq · $who$transcript"
    }
}
