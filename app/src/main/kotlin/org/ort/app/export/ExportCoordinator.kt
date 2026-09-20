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

    /**
     * R-1045(b) (register, design: `Settings-Export.dc.html`'s `Save file` button reads
     * "Save file · <filename> · <size>", the build showed only "Save file"). The real byte count
     * `Save file` is about to write — never an estimate presented as a size (constitution I/VI) —
     * resolved the same way [org.ort.app.fieldreport.bundle.FieldReportBundleBuilder.preview]
     * already resolves a real pre-upload size for its own bundle: by running the *actual* producer
     * before the write, not a second, independently-scoped guess. Here that producer is [build]
     * itself — literally the same function [org.ort.app.ui.settings.SettingsExportScreen]'s own
     * real `Save file` handler calls — so this is never a duplicated writer implementation that
     * could silently disagree with what gets written, only a second *call* to the one real one
     * (the same "read twice, write once" cost [previewCount] already accepts for its own counts,
     * for the identical reason: a preview must answer before the operator has committed to
     * anything, and only the real producer's own output is honest enough to show).
     */
    public suspend fun previewSizeBytes(context: Context, request: ExportRequest): Long =
        build(context, request).size.toLong()

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

    /** P30 (AC-169): a distinct suggested name for the POTA-relevant export — never
     * [suggestedFileName], which is keyed on [ExportFileFormat] and [buildPotaActivity] does not
     * take one (POTA is "not one of the four format chips", this file's own [ExportFileFormat]
     * kdoc). `ort-pota-<scope>-<UTC timestamp>.csv` — the same stamp/scope-word shape, the real
     * `.csv` extension [org.ort.pipeline.export.PotaActivityExportWriter] actually writes. */
    public fun suggestedPotaFileName(scope: ExportRequestScope, now: Instant = Instant.now()): String {
        val scopeWord = when (scope) {
            ExportRequestScope.TONIGHT -> "tonight"
            ExportRequestScope.EVERYTHING -> "all"
        }
        return "ort-pota-$scopeWord-${FILE_STAMP.format(now)}.csv"
    }

    /**
     * P30 (AC-169, constitution I): how many POTA-relevant rows [buildPotaActivity] would
     * actually write for [scope] — one row per park reference named by a scoped over
     * ([org.ort.pipeline.export.PotaActivityExportWriter]'s own "one row per park reference, never
     * a single row silently naming only the first"), so the screen can say "0 park activations"
     * honestly rather than showing a button with no idea whether it does anything. Reads the same
     * [scopedRecords] every other preview and the real write already use — never a second,
     * independently-scoped count.
     */
    public suspend fun previewPotaCount(context: Context, scope: ExportRequestScope): Int =
        withContext(Dispatchers.IO) {
            scopedRecords(context, scope).sumOf { it.potaRefs.size }
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
            // Register R-1039: `station?.callsign` alone is not the real source of truth for a
            // CONFIRMED/INFERRED transmission's callsign — the `station` catalog table is a
            // secondary, lazily-populated aggregate (no production write path upserts a row for
            // every resolved callsign; `CorrectionPolling.searchHeardStations`'s own kdoc states
            // this directly: "every station this device has heard is only reachable by walking
            // every transmission"). `transmission.stationId` is the real value every other reader in
            // this codebase already trusts directly for exactly this (`LiveMonitorViewData
            // .listEntryFromEntity`'s own `callsign = entity.stationId`, `StationPolling`'s
            // `station?.callsign ?: stationId` idiom, repeated at every one of its own call sites) —
            // `CallsignResolver`, the one place a CONFIRMED attribution is ever newly decided, always
            // sets it to `top.candidate.text`, a real callsign already validated by `CallsignGrammar`.
            // The catalog's own copy is preferred when present (it may carry a correction), falling
            // back to the transmission's own value rather than treating an absent/stale catalog row
            // as "no callsign exists" and throwing.
            attribution = toExportAttribution(
                transmission,
                station?.callsign?.takeIf { it.isNotBlank() } ?: transmission.stationId,
            ),
            transcriptText = transcript?.text,
            transcriptModelId = transcript?.modelId,
            transcriptModelVersion = transcript?.modelVersion,
            potaRefs = station?.potaRefs ?: emptyList(),
        )
    }

    /**
     * The one place a `TransmissionEntity`'s flat attribution fields become the structural
     * [ExportAttribution] every writer requires.
     *
     * Register R-1039 (halt): before this fix, a missing [callsign] threw
     * (`IllegalArgumentException: CONFIRMED transmission ... has no resolvable callsign`) — real,
     * reachable data, not a fixture artifact (see this file's own `toExportOverRecord` call site
     * kdoc for the evidence: the `station` catalog table is a lazily-populated secondary aggregate,
     * never guaranteed to carry a row for every callsign a transmission was actually attributed to).
     * [confidence] can also be genuinely absent for a real, callsign-known row —
     * [org.ort.core.Attribution.withCorrection] (`CorrectionPolling.applyCorrectedAttribution`, an
     * everyday human correction) produces exactly that: `INFERRED`, a real chosen callsign, `null`
     * confidence. Neither case is a reason to throw, silently drop the row, invent a callsign, or
     * relabel it `AMBIGUOUS`/`UNKNOWN` (constitution I: that would hide a confirmation or an
     * inference that genuinely happened) — see [ExportAttribution.CallsignKnown] (nullable
     * confidence) and [ExportAttribution.UnresolvedCallsign] (state stated, callsign honestly
     * absent, reason stated) for how the type itself makes both representable.
     *
     * A row can still reach here with [callsign] `null` despite a resolved state: [org.ort.core
     * .Attribution]'s own factory functions require a non-blank station id for `CONFIRMED`/
     * `INFERRED`, but `TransmissionDao.updateAttribution` (a raw SQL update) does not itself enforce
     * that invariant on the `transmission` row, so a future write path bypassing those factories —
     * or a migration/corrupted restore — could still produce it. That is the genuine data-integrity
     * defect [ExportAttribution.UnresolvedCallsign] exists for.
     */
    internal fun toExportAttribution(transmission: TransmissionEntity, callsign: String?): ExportAttribution =
        when (transmission.attributionState) {
            AttributionState.CONFIRMED -> if (!callsign.isNullOrBlank()) {
                ExportAttribution.Confirmed(
                    callsign = callsign,
                    confidence = transmission.attributionConfidence,
                    corrected = transmission.corrected,
                )
            } else {
                ExportAttribution.UnresolvedCallsign(
                    state = AttributionState.CONFIRMED,
                    reason = "CONFIRMED transmission ${transmission.id} has no station id recorded — " +
                        "data integrity defect",
                )
            }
            AttributionState.INFERRED -> if (!callsign.isNullOrBlank()) {
                ExportAttribution.Inferred(
                    callsign = callsign,
                    confidence = transmission.attributionConfidence,
                    corrected = transmission.corrected,
                )
            } else {
                ExportAttribution.UnresolvedCallsign(
                    state = AttributionState.INFERRED,
                    reason = "INFERRED transmission ${transmission.id} has no station id recorded — " +
                        "data integrity defect",
                )
            }
            AttributionState.AMBIGUOUS -> ExportAttribution.Ambiguous
            AttributionState.UNKNOWN -> ExportAttribution.Unknown
        }
}
