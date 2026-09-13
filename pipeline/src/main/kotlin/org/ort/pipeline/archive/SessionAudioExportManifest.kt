package org.ort.pipeline.archive

import org.ort.pipeline.export.AttributionCells
import org.ort.pipeline.export.ExportAttribution
import org.ort.pipeline.export.stateTag
import org.ort.pipeline.export.toCells
import java.time.Instant

/**
 * [SessionAudioExport]'s manifest — the provenance record constitution VI requires for every file
 * a session-audio export writes (never a bare `audio/<sessionId>/<id>.flac` with nothing saying
 * where it came from). One JSON object, written as `manifest.json` at the root of the zip
 * [SessionAudioExport.write] produces.
 *
 * **Deliberately shaped so a future FR-STO-6 whole-database export can reuse it unchanged**: this
 * is already "one session's slice" of that larger export, not a competing format — see
 * [SessionAudioExport]'s own class kdoc for why no second format was invented. A whole-database
 * export can nest many of these (one per session) under a top-level `sessions` array sharing this
 * same [formatVersion], with the exact same `audio/<sessionId>/…` and `archive/<sessionId>/…` zip
 * entry layout [SessionAudioExport] already uses per session — concatenating sessions never
 * collides, because every entry name is already namespaced by [sessionId].
 *
 * **What this manifest never carries, structurally** (never queried by [SessionAudioExport] at
 * all, not merely omitted after being read — see that object's own kdoc for the proof):
 * [org.ort.data.entity.VoiceprintEntity.embedding], [org.ort.data.entity.StationEntity.userName]/
 * `.notes`, [org.ort.data.entity.OperatorLocationEntity], and every
 * [org.ort.data.entity.TransmissionLabelEntity] field (Q21, `spec/open-questions.md`: training
 * labels are excluded from every outbound path until decided).
 */
public data class SessionAudioExportManifest(
    /** Bumped only on a breaking change to this shape — the one thing a future importer checks
     * before trying to parse the rest (the same role `TransmissionEntity.schemaVersion` plays for
     * the database itself). */
    public val formatVersion: Int,
    public val sessionId: String,
    public val startedAtUtcMillis: Long,
    public val endedAtUtcMillis: Long?,
    /** [org.ort.data.entity.SessionEntity.appVersion] — the real version this session was
     * *captured* on, `null` for a pre-v7 row that never recorded it (constitution I: never
     * fabricate one). Not the version doing the exporting; a device re-exporting an old session
     * runs a newer build than the one that captured it. */
    public val appVersion: String?,
    public val exportedAtUtcMillis: Long,
    public val target: SessionAudioExportTarget,
    public val overAudio: SessionAudioExportAudioHalf,
    public val archive: SessionAudioExportArchiveHalf,
    public val overs: List<SessionAudioExportOverManifest>,
)

/** P9 ("nothing is deleted quietly"): [removedAtUtcMillis] is stated even when [includedInThisExport]
 * is `false` because [SessionAudioExportTarget] did not ask for this half at all — a reader must
 * never infer "not included" as "never existed" or "still present". */
public data class SessionAudioExportAudioHalf(
    public val removedAtUtcMillis: Long?,
    public val includedInThisExport: Boolean,
)

/** Mirrors [SessionAudioExportAudioHalf] for the raw continuous archive, plus its own three-state
 * [ArchiveState] (`NONE` is a real, distinct fact — this session never kept one — never conflated
 * with `REMOVED`, constitution I). */
public data class SessionAudioExportArchiveHalf(
    public val state: ArchiveState,
    public val removedAtUtcMillis: Long?,
    public val includedInThisExport: Boolean,
)

/**
 * One over's provenance row — present for every transmission in the session regardless of
 * [SessionAudioExportTarget], so a reader always has the full timeline even when this particular
 * export did not carry over-audio bytes at all (`audioIncluded = false` states that plainly rather
 * than omitting the row).
 */
public data class SessionAudioExportOverManifest(
    public val transmissionId: String,
    public val startedAtUtcMillis: Long,
    public val endedAtUtcMillis: Long?,
    public val durationMs: Long,
    /** A session can span many frequencies (scanner mode); this is deliberately a per-over fact,
     * never invented as a single session-wide frequency (constitution I). */
    public val frequencyHz: Long?,
    public val frequencyProvenance: String,
    public val mode: String?,
    /** FR-EXP-4: never a bare callsign — the state and (when real) the confidence travel with it,
     * enforced by [ExportAttribution]'s own type (see that class's kdoc: an exhaustive `when` is
     * the only way this compiles). */
    public val attribution: ExportAttribution,
    /** [org.ort.data.dao.TranscriptDao.getCurrent] — the *current* version only; a superseded
     * transcript is real history (FR-REP-3, P9) but is not this over's provenance today. */
    public val transcriptText: String?,
    public val transcriptModelId: String?,
    public val transcriptModelVersion: String?,
    /** [org.ort.data.entity.TransmissionEntity.processedTier]/`.name` — `null` until a real pass
     * has ever completed for this over (constitution VI: never a number without its provenance,
     * so never fabricated). */
    public val processedTier: String?,
    public val executionProvider: String?,
    /** Whether this over's own `audio/<sessionId>/<id>.flac` is actually an entry in this zip —
     * `false` either because [SessionAudioExportTarget] did not include over audio, or because the
     * file is genuinely absent on disk (over audio removed, or never retained). Never conflated
     * with the row simply not being written at all (P9). */
    public val audioIncluded: Boolean,
)

/**
 * Hand-rolled, matching [org.ort.pipeline.export.JsonExportWriter]'s own reasoning: every value
 * this manifest ever emits is a string, a finite number, a boolean, `null`, or a nested object of
 * those — a general JSON library would be more surface than the problem needs, and none is on
 * this module's classpath. A separate small writer, not a shared one, because
 * [org.ort.pipeline.export.JsonExportWriter]'s own escaping helpers are `private` to that object
 * (module-boundary independence, not an oversight — see [SessionAudioExport]'s own report for why
 * duplicating ~15 lines here was preferred to widening that object's visibility for one caller).
 */
public object SessionAudioManifestWriter {

    public fun write(manifest: SessionAudioExportManifest): String {
        val fields = listOf(
            "formatVersion" to manifest.formatVersion.toString(),
            "sessionId" to jsonString(manifest.sessionId),
            "startedAtUtc" to jsonInstant(manifest.startedAtUtcMillis),
            "endedAtUtc" to jsonNullableInstant(manifest.endedAtUtcMillis),
            "appVersion" to jsonNullableString(manifest.appVersion),
            "exportedAtUtc" to jsonInstant(manifest.exportedAtUtcMillis),
            "target" to jsonString(manifest.target.name),
            "overAudio" to overAudioObject(manifest.overAudio),
            "archive" to archiveObject(manifest.archive),
            "overs" to oversArray(manifest.overs),
        )
        return "{\n" + fields.joinToString(",\n") { (k, v) -> "  \"$k\": $v" } + "\n}\n"
    }

    private fun overAudioObject(half: SessionAudioExportAudioHalf): String = "{" +
        "\"removedAtUtc\":${jsonNullableInstant(half.removedAtUtcMillis)}," +
        "\"includedInThisExport\":${half.includedInThisExport}" +
        "}"

    private fun archiveObject(half: SessionAudioExportArchiveHalf): String = "{" +
        "\"state\":${jsonString(half.state.name)}," +
        "\"removedAtUtc\":${jsonNullableInstant(half.removedAtUtcMillis)}," +
        "\"includedInThisExport\":${half.includedInThisExport}" +
        "}"

    private fun oversArray(overs: List<SessionAudioExportOverManifest>): String {
        if (overs.isEmpty()) return "[]"
        val body = overs.joinToString(",\n") { "    " + overObject(it) }
        return "[\n$body\n  ]"
    }

    private fun overObject(over: SessionAudioExportOverManifest): String {
        val cells = over.attribution.toCells()
        val fields = listOf(
            "transmissionId" to jsonString(over.transmissionId),
            "startedAtUtc" to jsonInstant(over.startedAtUtcMillis),
            "endedAtUtc" to jsonNullableInstant(over.endedAtUtcMillis),
            "durationMs" to over.durationMs.toString(),
            "frequencyHz" to (over.frequencyHz?.toString() ?: "null"),
            "frequencyProvenance" to jsonString(over.frequencyProvenance),
            "mode" to jsonNullableString(over.mode),
            "attribution" to attributionObject(over.attribution, cells),
            "transcriptText" to jsonNullableString(over.transcriptText),
            "transcriptModelId" to jsonNullableString(over.transcriptModelId),
            "transcriptModelVersion" to jsonNullableString(over.transcriptModelVersion),
            "processedTier" to jsonNullableString(over.processedTier),
            "executionProvider" to jsonNullableString(over.executionProvider),
            "audioIncluded" to over.audioIncluded.toString(),
        )
        return "{" + fields.joinToString(",") { (k, v) -> "\"$k\":$v" } + "}"
    }

    /** Same shape [org.ort.pipeline.export.JsonExportWriter.attributionObject] uses — `state`
     * always present, `callsign`/`confidence`/`corrected` all `null` together for a row that
     * cannot legitimately name one (FR-EXP-4). */
    private fun attributionObject(attribution: ExportAttribution, cells: AttributionCells): String {
        val callsign = if (attribution is ExportAttribution.CallsignKnown) jsonString(cells.callsignCell) else "null"
        val confidence = if (attribution is ExportAttribution.CallsignKnown) {
            attribution.confidence?.toString() ?: "null"
        } else {
            "null"
        }
        val corrected = if (attribution is ExportAttribution.CallsignKnown) attribution.corrected.toString() else "null"
        val note = if (cells.noteCell.isNotEmpty()) jsonString(cells.noteCell) else "null"
        return "{" +
            "\"state\":${jsonString(attribution.stateTag())}," +
            "\"callsign\":$callsign," +
            "\"confidence\":$confidence," +
            "\"corrected\":$corrected," +
            "\"note\":$note" +
            "}"
    }

    private fun jsonInstant(millis: Long): String = jsonString(Instant.ofEpochMilli(millis).toString())

    private fun jsonNullableInstant(millis: Long?): String = millis?.let { jsonInstant(it) } ?: "null"

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
