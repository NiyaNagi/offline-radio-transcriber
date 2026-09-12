package org.ort.app.export

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ort.core.AttributionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.TransmissionEntity
import org.ort.pipeline.export.AdifExportWriter
import org.ort.pipeline.export.CsvExportWriter
import org.ort.pipeline.export.ExportAttribution
import org.ort.pipeline.export.ExportOverRecord
import org.ort.pipeline.export.JsonExportWriter
import org.ort.pipeline.export.PotaActivityExportWriter
import org.ort.pipeline.export.TextExportWriter
import org.ort.pipeline.export.confirmedOnly
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** FR-EXP-1/2/3/4/5. The four formats `Settings-Export.dc.html` already offers, plus the
 * POTA-relevant writer FR-EXP-3 names separately (not one of the four format chips — a distinct
 * export, so it is not in [ExportFileFormat] itself). [extension] is the real, sole source for
 * both the writer dispatch in [ExportCoordinator.build] and the suggested file name — one place,
 * never a second copy of the mapping that could silently drift. */
public enum class ExportFileFormat(public val extension: String) {
    ADIF("adi"),
    CSV("csv"),
    JSON("json"),
    TEXT("txt"),
}

/**
 * Register R-1009 (WPX). "Tonight" and "Everything" — the same two scopes
 * [org.ort.app.ui.settings.SettingsPolling.export]'s own `tonightOverCount`/`allOverCount` already
 * compute for the Export screen's radio rows (`TONIGHT` = the most recent session only,
 * `EVERYTHING` = every session), read across rather than redefined a second way. **`RANGE`
 * ("a range of nights") is deliberately absent**: `Settings-Export.dc.html` offers the radio row,
 * but no date-range picker exists anywhere in this build to supply the two dates a real query
 * would need, and this round does not add one (see this package's own report on why: a new
 * date-range control is a visual change with no artboard reference to build it against, and this
 * round is explicitly scoped away from screenshot capture). [org.ort.app.ui.settings
 * .SettingsExportScreen] disables `Save file` while that radio option is selected rather than
 * silently mapping it onto `EVERYTHING` or `TONIGHT` (constitution I: never claim a scope that was
 * not actually honoured).
 */
public enum class ExportRequestScope { TONIGHT, EVERYTHING }

/**
 * R-1035 (register, from the operator's own device: an ADIF export "wrote a header and zero QSO
 * records" — correct per FR-EXP-4, and visibly honest about why, but discovered only after the
 * write). [totalCount] is every record [ExportRequest.scope]/[ExportRequest.confirmedOnly] would
 * hand a writer; [exportableCount] is how many of those would actually become a record a reader
 * sees as a logged contact — for [ExportFileFormat.ADIF] specifically, that means
 * [org.ort.pipeline.export.ExportAttribution.CallsignKnown] (`AdifExportWriter`'s own `<EOR>` gate;
 * see [ExportCoordinator.previewCount]'s own doc comment for why every other format has no such
 * gap at all). [excludedCount] is `totalCount - exportableCount`, named separately so a caller never
 * has to re-derive it.
 */
public data class ExportCountPreview(val totalCount: Int, val exportableCount: Int, val excludedCount: Int)

public data class ExportRequest(
    val scope: ExportRequestScope,
    val format: ExportFileFormat,
    /** FR-EXP-5: "a filtered export of confirmed-only records." `false` by default — the ordinary
     * export includes every attribution state, each carrying its own real one (FR-EXP-4). */
    val confirmedOnly: Boolean = false,
    /** `Settings-Export.dc.html`'s "Transcripts, current version" checkbox. `true` by default
     * (unchanged behaviour for a caller that does not pass this). `false` strips both the
     * transcript text and its model provenance from every row together — never one without the
     * other, since a model id with no text next to it would misstate what was actually included. */
    val includeTranscripts: Boolean = true,
)

/**
 * Register R-1009 (WPX), FR-EXP-1..5. Reads the real `:data` entities for [ExportRequest.scope]
 * and hands `:pipeline`'s format writers the [ExportOverRecord] rows they need — the one place a
 * `TransmissionEntity`'s flat `attributionState`/`stationId`/`attributionConfidence` fields are
 * turned into the structural [ExportAttribution] every writer requires (see [toExportAttribution]).
 *
 * Mirrors [org.ort.app.diagnostics.DiagnosticsBundleBuilder]'s own idiom: a plain `object`, a
 * suspend function on [Dispatchers.IO], `OrtDatabase.create(context.applicationContext)` — the
 * same pattern this package's sibling bundle-builders already use, not a new one.
 */
public object ExportCoordinator {

    private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC)

    /** `ort-export-<scope>-<UTC timestamp>.<real extension>` — the suggested name only; the
     * operator may still rename it in the SAF picker (the same caveat `SettingsContent.kt`'s own
     * "Save bundle" flow already documents for its own suggested name). */
    public fun suggestedFileName(request: ExportRequest, now: Instant = Instant.now()): String {
        val scopeWord = when (request.scope) {
            ExportRequestScope.TONIGHT -> "tonight"
            ExportRequestScope.EVERYTHING -> "all"
        }
        return "ort-export-$scopeWord-${FILE_STAMP.format(now)}.${request.format.extension}"
    }

    public suspend fun build(context: Context, request: ExportRequest): ByteArray = withContext(Dispatchers.IO) {
        val effective = effectiveRecords(context, request)
        val text = when (request.format) {
            ExportFileFormat.ADIF -> AdifExportWriter.write(effective)
            ExportFileFormat.CSV -> CsvExportWriter.write(effective)
            ExportFileFormat.JSON -> JsonExportWriter.write(effective)
            ExportFileFormat.TEXT -> TextExportWriter.write(effective)
        }
        text.toByteArray(Charsets.UTF_8)
    }

    /**
     * R-1035: the exportable count **before** the write, not after — "0 of 8 can be exported as
     * QSOs; 7 have no identified station" is [ExportCountPreview.exportableCount] of
     * [ExportCountPreview.totalCount], [ExportCountPreview.excludedCount] the difference. Resolved
     * from the exact same [effectiveRecords] list [build] itself hands to a writer — the identical
     * "the count comes from the same producer that writes the file, never a separate estimate"
     * discipline [org.ort.app.diagnostics.DiagnosticsBundleBuilder]'s own doc comment already holds
     * one package over — never a second, independently-scoped query that could silently disagree
     * with what the write actually does.
     *
     * Only [ExportFileFormat.ADIF] ever excludes a record at all: `AdifExportWriter.write` is the
     * one writer in this package with no way to represent an unidentified station (ADIF's `CALL`
     * field has no "unknown" value, so [org.ort.pipeline.export.ExportAttribution.Ambiguous]/
     * [org.ort.pipeline.export.ExportAttribution.Unknown] records are named in its header instead of
     * becoming a `<EOR>` record). `CsvExportWriter`/`JsonExportWriter`/`TextExportWriter` write one
     * row per record regardless of attribution state (checked directly against each writer's own
     * source before writing this) — for every format but ADIF, [ExportCountPreview.exportableCount]
     * therefore equals [ExportCountPreview.totalCount].
     */
    public suspend fun previewCount(context: Context, request: ExportRequest): ExportCountPreview =
        withContext(Dispatchers.IO) {
            val effective = effectiveRecords(context, request)
            val exportable = when (request.format) {
                ExportFileFormat.ADIF -> effective.count { it.attribution is ExportAttribution.CallsignKnown }
                ExportFileFormat.CSV, ExportFileFormat.JSON, ExportFileFormat.TEXT -> effective.size
            }
            ExportCountPreview(
                totalCount = effective.size,
                exportableCount = exportable,
                excludedCount = effective.size - exportable,
            )
        }

    /** The exact record list [build] hands a writer and [previewCount] counts against — shared so
     * the two can never drift (this object's own [previewCount] doc comment). */
    private suspend fun effectiveRecords(context: Context, request: ExportRequest): List<ExportOverRecord> {
        var records = scopedRecords(context, request.scope)
        if (!request.includeTranscripts) {
            records =
                records.map { it.copy(transcriptText = null, transcriptModelId = null, transcriptModelVersion = null) }
        }
        return if (request.confirmedOnly) confirmedOnly(records) else records
    }

    /** FR-EXP-3's own writer, over the same [scope]-resolved records every other format uses —
     * never a second, independently-scoped read. */
    public suspend fun buildPotaActivity(context: Context, scope: ExportRequestScope): ByteArray =
        withContext(Dispatchers.IO) {
            PotaActivityExportWriter.write(scopedRecords(context, scope)).toByteArray(Charsets.UTF_8)
        }

    private suspend fun scopedRecords(context: Context, scope: ExportRequestScope): List<ExportOverRecord> {
        val db = OrtDatabase.create(context.applicationContext)
        val sessions = db.sessionDao().listAll()
        val scopedSessions = when (scope) {
            ExportRequestScope.TONIGHT -> listOfNotNull(sessions.firstOrNull())
            ExportRequestScope.EVERYTHING -> sessions
        }
        return scopedSessions.flatMap { session ->
            db.transmissionDao().listBySession(session.id).map { transmission -> toExportOverRecord(db, transmission) }
        }
    }

    private suspend fun toExportOverRecord(db: OrtDatabase, transmission: TransmissionEntity): ExportOverRecord {
        val transcript = db.transcriptDao().getCurrent(transmission.id)
        val station = transmission.stationId?.let { db.catalogDao().getStation(it) }
        return ExportOverRecord(
            transmissionId = transmission.id,
            sessionId = transmission.sessionId,
            startedAtUtcMillis = transmission.startedAtUtc,
            endedAtUtcMillis = transmission.endedAtUtc,
            frequencyHz = transmission.frequencyHz,
            mode = transmission.mode,
            channelName = transmission.channelName,
            attribution = toExportAttribution(transmission, station?.callsign),
            transcriptText = transcript?.text,
            transcriptModelId = transcript?.modelId,
            transcriptModelVersion = transcript?.modelVersion,
            potaRefs = station?.potaRefs ?: emptyList(),
        )
    }

    /**
     * The one place a `TransmissionEntity`'s flat attribution fields become the structural
     * [ExportAttribution] every writer requires. `CONFIRMED`/`INFERRED` require a real
     * `callsign`/`attributionConfidence` — [org.ort.core.Attribution]'s own factory functions
     * ([org.ort.core.Attribution.confirmed]/[org.ort.core.Attribution.inferred]) already make
     * constructing one without both impossible at the point a transmission is first attributed, so
     * a row reaching here in that state with either missing is a data-integrity defect, not a
     * reachable "just export it anyway" case — this throws rather than silently downgrading the
     * row to `AMBIGUOUS`/`UNKNOWN` (which would misstate what the record actually says) or
     * inventing a placeholder callsign (constitution I).
     */
    internal fun toExportAttribution(transmission: TransmissionEntity, callsign: String?): ExportAttribution =
        when (transmission.attributionState) {
            AttributionState.CONFIRMED -> ExportAttribution.Confirmed(
                callsign = requireNotNull(callsign) {
                    "CONFIRMED transmission ${transmission.id} has no resolvable callsign — data integrity defect"
                },
                confidence = requireNotNull(transmission.attributionConfidence) {
                    "CONFIRMED transmission ${transmission.id} has no confidence — data integrity defect"
                },
                corrected = transmission.corrected,
            )
            AttributionState.INFERRED -> ExportAttribution.Inferred(
                callsign = requireNotNull(callsign) {
                    "INFERRED transmission ${transmission.id} has no resolvable callsign — data integrity defect"
                },
                confidence = requireNotNull(transmission.attributionConfidence) {
                    "INFERRED transmission ${transmission.id} has no confidence — data integrity defect"
                },
                corrected = transmission.corrected,
            )
            AttributionState.AMBIGUOUS -> ExportAttribution.Ambiguous
            AttributionState.UNKNOWN -> ExportAttribution.Unknown
        }
}
