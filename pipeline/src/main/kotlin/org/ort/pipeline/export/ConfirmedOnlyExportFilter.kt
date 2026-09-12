package org.ort.pipeline.export

/**
 * Register R-1009 (WPX), FR-EXP-5: "Offer a filtered export of confirmed-only records for users
 * who want a conservative log." A pure filter, deliberately separate from every writer — a
 * caller composes `confirmedOnly(records)` with any of [AdifExportWriter]/[CsvExportWriter]/
 * [JsonExportWriter]/[TextExportWriter]/[PotaActivityExportWriter] rather than each writer
 * growing its own "confirmed only" flag, so the filter's own correctness (exactly one predicate,
 * tested once here) can never drift between formats.
 */
public fun confirmedOnly(records: List<ExportOverRecord>): List<ExportOverRecord> =
    records.filter { it.attribution is ExportAttribution.Confirmed }
