package org.ort.app.export

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.ort.app.BuildConfig
import org.ort.app.diagnostics.DiagnosticsLogPaths
import org.ort.core.SystemClock
import org.ort.data.OrtDatabase
import org.ort.data.entity.CaptureGapEntity
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TransmissionEntity
import org.ort.data.entity.WorkAttemptEntity
import org.ort.data.entity.WorkQueueItemEntity
import org.ort.pipeline.diagnostics.DiagnosticsLog
import java.io.File
import java.time.Instant

/**
 * Register R-1009 (WPX) — the debug dump half of the register row: *"An NDJSON export of
 * sessions, overs, attributions, gaps and pass outcomes, so a failed field session is analysable
 * on the workstation... Include the WorkQueueItemEntity attempt history and lastError, because
 * that is exactly what nineteen silently-failed overs looked like from the outside this week."*
 *
 * One JSON object per line (NDJSON — a workstation reads it with `jq`/a streaming line reader,
 * never a single 100 MB array a tool must load whole), `type` discriminating six closed record
 * shapes: `meta`, `session`, `over`, `gap`, `pass_outcome`, `vad_stats`. Constitution VI ("no number without
 * its provenance"): the `meta` line carries the schema version, the real app version and the real
 * git commit; every `over` line carries its own `executionProvider`/`calibrationId` (the pass
 * identity that actually produced it) rather than a single dump-wide claim that would be wrong the
 * moment two overs ran on different providers.
 *
 * **Only `pass_outcome`s for terminally-`FAILED` [org.ort.data.entity.WorkQueueState] items are
 * emitted** ([org.ort.data.dao.WorkQueueDao.selectFailed] with both filters `null`, i.e. every
 * one) — a `READY`/`LEASED`/`DEFERRED` item has not failed anything yet, so there is no attempt
 * history or `lastError` for this dump's "why did this fail" purpose to say anything about; a
 * failed item's [org.ort.data.entity.WorkAttemptEntity] history
 * ([org.ort.data.dao.WorkQueueDao.attemptsFor]) rides along in full, oldest attempt first — the
 * exact shape `Fail-Pass.dc.html` already renders for a single item, now for every one.
 *
 * **The same discipline `org.ort.app.diagnostics.DiagnosticsBundleBuilder` already holds for its
 * own zip** (AC-109/AC-120): this producer never reads a voiceprint embedding, a station's
 * user-supplied name or its notes — structurally, by never querying [org.ort.data.dao.CatalogDao]
 * at all, not merely an omission a future edit could quietly undo. Real callsigns are *not*
 * excluded from the format-writer exports in this same package ([ExportCoordinator]) because a
 * callsign is the public over-the-air identity the whole product exists to capture — but this
 * particular producer has no need of one for its own purpose (correlating sessions, overs, gaps
 * and pass failures), so it simply never looks one up.
 *
 * **`vad_stats` (FR-OBS-1, register Q20):** one line per closed segment — accepted and rejected
 * alike (constitution III) — parsed back out of `capture.log` (and its one `.1` rotation
 * generation) rather than a database table, because [org.ort.pipeline.diagnostics.DiagnosticsLog]
 * is exactly where the segmenter's real-time facts already land (see that object's own kdoc for
 * why they are not duplicated into a Room column). A field the writer logged as the literal
 * `NONE` (never measured, never fabricated as `0.0` — constitution I) round-trips here as JSON
 * `null`, the same discipline every other nullable field in this file already uses.
 */
public object DebugDumpBuilder {

    public suspend fun build(context: Context): ByteArray = withContext(Dispatchers.IO) {
        val db = OrtDatabase.create(context.applicationContext)
        val lines = mutableListOf<String>()

        lines += metaLine(context).toString()

        val sessions = db.sessionDao().listAll()
        for (session in sessions) lines += sessionLine(session).toString()

        for (transmission in db.transmissionDao().listAll()) lines += overLine(transmission).toString()

        for (session in sessions) {
            for (gap in db.captureGapDao().listBySession(session.id)) lines += gapLine(gap).toString()
        }

        for (item in db.workQueueDao().selectFailed(pass = null, lastErrorPrefix = null)) {
            lines += passOutcomeLine(item, db.workQueueDao().attemptsFor(item.id)).toString()
        }

        for (vadStats in vadStatsLines(context)) lines += vadStats.toString()

        for (failure in modelVerificationFailureLines(context)) lines += failure.toString()

        lines.joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    private fun metaLine(context: Context): JSONObject = JSONObject().apply {
        put("type", "meta")
        put("schemaVersion", OrtDatabase.SCHEMA_VERSION)
        put("exportedAtUtc", Instant.ofEpochMilli(SystemClock.wallMillis()).toString())
        put("appVersion", appVersionLabel(context))
        put("gitShortCommit", BuildConfig.GIT_SHORT_COMMIT)
        put("deviceModel", Build.MODEL ?: JSONObject.NULL)
        put("androidVersion", Build.VERSION.RELEASE ?: JSONObject.NULL)
        put("sdkInt", Build.VERSION.SDK_INT)
    }

    /** Same fallback [org.ort.app.diagnostics.DeviceJsonProducer.appVersionLabel] already uses —
     * never fabricated on the (packaging-only) failure path. */
    private fun appVersionLabel(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "dev build"
    } catch (e: PackageManager.NameNotFoundException) {
        "dev build"
    }

    private fun sessionLine(session: SessionEntity): JSONObject = JSONObject().apply {
        put("type", "session")
        put("id", session.id)
        put("startedAt", session.startedAt)
        put("endedAt", session.endedAt ?: JSONObject.NULL)
        put("terminationReason", session.terminationReason?.name ?: JSONObject.NULL)
        put("captureMode", session.captureMode ?: JSONObject.NULL)
        put("audioRouteKind", session.audioRouteKind ?: JSONObject.NULL)
        put("rigTransport", session.rigTransport ?: JSONObject.NULL)
        put("rigDescriptorId", session.rigDescriptorId ?: JSONObject.NULL)
        put("audioRouteVerified", session.audioRouteVerified ?: JSONObject.NULL)
        put("audioNativeRateHz", session.audioNativeRateHz ?: JSONObject.NULL)
        put("gapCount", session.gapCount)
        put("shedEvents", session.shedEvents)
        // FR-SEG-10 (register R-1054, AC-162): the detector this session's whole Segmenter ran --
        // see TransmissionEntity.vadDetector's own kdoc for why this is denormalized here too.
        put("vadDetector", session.vadDetector.name)
        put("vadDetectorVersion", session.vadDetectorVersion ?: JSONObject.NULL)
        put("appVersion", session.appVersion ?: JSONObject.NULL)
        put("deviceTier", session.deviceTier ?: JSONObject.NULL)
        put("schemaVersion", session.schemaVersion)
    }

    private fun overLine(t: TransmissionEntity): JSONObject = JSONObject().apply {
        put("type", "over")
        put("id", t.id)
        put("sessionId", t.sessionId)
        put("threadId", t.threadId ?: JSONObject.NULL)
        put("startedAtUtc", Instant.ofEpochMilli(t.startedAtUtc).toString())
        put("endedAtUtc", t.endedAtUtc?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
        put("durationMs", t.durationMs)
        put("frequencyHz", t.frequencyHz ?: JSONObject.NULL)
        put("frequencyProvenance", t.frequencyProvenance)
        put("mode", t.mode ?: JSONObject.NULL)
        put("channelName", t.channelName ?: JSONObject.NULL)
        put("attributionState", t.attributionState.name)
        put("stationId", t.stationId ?: JSONObject.NULL)
        put("attributionConfidence", t.attributionConfidence ?: JSONObject.NULL)
        put("corrected", t.corrected)
        put("processingState", t.processingState.name)
        put("rejectionReason", t.rejectionReason ?: JSONObject.NULL)
        put("processedTier", t.processedTier?.name ?: JSONObject.NULL)
        put("executionProvider", t.executionProvider ?: JSONObject.NULL)
        put("calibrationId", t.calibrationId ?: JSONObject.NULL)
        put("isReprocessCandidate", t.isReprocessCandidate)
        put("rigStateChangedMidTransmission", t.rigStateChangedMidTransmission)
        // FR-SEG-10 (register R-1054, AC-162): which detector actually cut this over's boundaries,
        // whether rig squelch fusion applied, and the single data-layer answer to "does this
        // conform to FR-SEG-1" -- never re-derived by this exporter (see
        // TransmissionEntity.conformsToFrSeg1's own kdoc).
        put("vadDetector", t.vadDetector.name)
        put("vadDetectorVersion", t.vadDetectorVersion ?: JSONObject.NULL)
        put("rigSquelchFusionApplied", t.rigSquelchFusionApplied)
        put("conformsToFrSeg1", t.conformsToFrSeg1())
    }

    private fun gapLine(gap: CaptureGapEntity): JSONObject = JSONObject().apply {
        put("type", "gap")
        put("id", gap.id)
        put("sessionId", gap.sessionId)
        put("startedAt", gap.startedAt)
        put("endedAt", gap.endedAt ?: JSONObject.NULL)
        put("cause", gap.cause.name)
        put("recoveredAutomatically", gap.recoveredAutomatically)
    }

    private fun passOutcomeLine(item: WorkQueueItemEntity, attempts: List<WorkAttemptEntity>): JSONObject =
        JSONObject().apply {
            put("type", "pass_outcome")
            put("itemId", item.id)
            put("transmissionId", item.transmissionId)
            put("pass", item.pass.name)
            put("state", item.state.name)
            put("priority", item.priority)
            put("attemptCount", item.attemptCount)
            put("lastError", item.lastError ?: JSONObject.NULL)
            put("shedLevel", item.shedLevel)
            put("leaseRunId", item.leaseRunId ?: JSONObject.NULL)
            put("deadlineAt", item.deadlineAt ?: JSONObject.NULL)
            put("enqueuedAt", item.enqueuedAt)
            put("startedAt", item.startedAt ?: JSONObject.NULL)
            put("retryNotBeforeMillis", item.retryNotBeforeMillis ?: JSONObject.NULL)
            put(
                "attempts",
                JSONArray(
                    attempts.map { attempt ->
                        JSONObject().apply {
                            put("attemptNo", attempt.attemptNo)
                            put("startedAtMillis", attempt.startedAtMillis)
                            put("finishedAtMillis", attempt.finishedAtMillis)
                            put("outcome", attempt.outcome.name)
                            put("reason", attempt.reason)
                        }
                    },
                ),
            )
        }

    /**
     * FR-OBS-1 (Q20): every [DiagnosticsLog.logVadStats] line this session's `capture.log` (and
     * its one `.1` rotation generation, oldest first — [DiagnosticsLog]'s own rotation kdoc) still
     * holds, parsed back into the closed shape [logVadStatsLine] emits. A line this parser cannot
     * make sense of (any other `capture.log` event, or a line rotation split mid-write) is simply
     * skipped, never guessed at.
     */
    private fun vadStatsLines(context: Context): List<JSONObject> {
        val dir = DiagnosticsLogPaths.logDir(context)
        val fileName = DiagnosticsLog.Category.CAPTURE.fileName
        val rawLines = listOf(File(dir, "$fileName.1"), File(dir, fileName))
            .filter { it.isFile }
            .flatMap { it.readLines() }
        return rawLines.mapNotNull(::parseLogLine)
            .filter { it.event == DiagnosticsLog.EVENT_VAD_STATS }
            .map(::vadStatsLine)
    }

    private data class ParsedLogLine(val event: String, val fields: Map<String, String>)

    /** `capture.log`'s own line shape (see [DiagnosticsLog]'s kdoc): ISO-8601 timestamp, level,
     * event, then space-separated `key=value` fields — never a value containing a space, since
     * every field [DiagnosticsLog] writes is a number, an enum name, an id, or the literal `NONE`. */
    private fun parseLogLine(raw: String): ParsedLogLine? {
        val parts = raw.trim().split(" ")
        if (parts.size < 3) return null
        val fields = parts.drop(3).mapNotNull { token ->
            val eq = token.indexOf('=')
            if (eq <= 0) null else token.substring(0, eq) to token.substring(eq + 1)
        }.toMap()
        return ParsedLogLine(event = parts[2], fields = fields)
    }

    /** `null`/absent exactly when [DiagnosticsLog.logVadStats] wrote the literal `NONE` for a
     * value it genuinely could not measure — never a fabricated `0`/`0.0` (constitution I). */
    private fun numericFieldOrNull(fields: Map<String, String>, key: String): Double? {
        val raw = fields[key] ?: return null
        return if (raw == "NONE") null else raw.toDoubleOrNull()
    }

    private fun vadStatsLine(parsed: ParsedLogLine): JSONObject = JSONObject().apply {
        val f = parsed.fields
        put("type", "vad_stats")
        put("transmissionId", f["transmissionId"] ?: JSONObject.NULL)
        put("outcome", f["outcome"] ?: JSONObject.NULL)
        put("closeReason", f["closeReason"] ?: JSONObject.NULL)
        put("durationMs", f["durationMs"]?.toLongOrNull() ?: JSONObject.NULL)
        put("vadFrameCount", f["vadFrameCount"]?.toIntOrNull() ?: JSONObject.NULL)
        put("vadSpeechFrameCount", f["vadSpeechFrameCount"]?.toIntOrNull() ?: JSONObject.NULL)
        put("peakDbfs", numericFieldOrNull(f, "peakDbfs") ?: JSONObject.NULL)
        put("meanDbfs", numericFieldOrNull(f, "meanDbfs") ?: JSONObject.NULL)
        put("noiseFloorDbfsAtOnset", numericFieldOrNull(f, "noiseFloorDbfsAtOnset") ?: JSONObject.NULL)
        // FR-SEG-10 (register R-1054, AC-162): the same closed-enum discipline every other field on
        // this line already holds -- absent only if a pre-WPSEGPROV capture.log line is ever parsed
        // (a line this build itself never writes without the field), never fabricated as a guess.
        put("vadDetector", f["vadDetector"] ?: JSONObject.NULL)
        put("rigSquelchFusionApplied", f["rigSquelchFusionApplied"]?.toBooleanStrictOrNull() ?: JSONObject.NULL)
    }

    /**
     * R-1058: every [DiagnosticsLog.logModelVerificationFailed] line still held in `pipeline.log`
     * (and its one `.1` rotation generation) — parsed back exactly like [vadStatsLines] reads
     * `capture.log`, never re-derived from in-memory state (this process may not even be the one
     * that logged it — a debug dump can run long after the launch that refused the model).
     */
    private fun modelVerificationFailureLines(context: Context): List<JSONObject> {
        val dir = DiagnosticsLogPaths.logDir(context)
        val fileName = DiagnosticsLog.Category.PIPELINE.fileName
        val rawLines = listOf(File(dir, "$fileName.1"), File(dir, fileName))
            .filter { it.isFile }
            .flatMap { it.readLines() }
        return rawLines.mapNotNull(::parseLogLine)
            .filter { it.event == DiagnosticsLog.EVENT_MODEL_VERIFICATION_FAILED }
            .map { parsed ->
                JSONObject().apply {
                    put("type", "model_verification_failed")
                    put("assetId", parsed.fields["assetId"] ?: JSONObject.NULL)
                    put("kind", parsed.fields["kind"] ?: JSONObject.NULL)
                }
            }
    }
}
