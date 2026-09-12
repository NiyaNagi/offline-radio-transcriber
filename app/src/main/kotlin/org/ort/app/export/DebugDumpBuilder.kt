package org.ort.app.export

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.ort.app.BuildConfig
import org.ort.core.SystemClock
import org.ort.data.OrtDatabase
import org.ort.data.entity.CaptureGapEntity
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TransmissionEntity
import org.ort.data.entity.WorkAttemptEntity
import org.ort.data.entity.WorkQueueItemEntity
import java.time.Instant

/**
 * Register R-1009 (WPX) — the debug dump half of the register row: *"An NDJSON export of
 * sessions, overs, attributions, gaps and pass outcomes, so a failed field session is analysable
 * on the workstation... Include the WorkQueueItemEntity attempt history and lastError, because
 * that is exactly what nineteen silently-failed overs looked like from the outside this week."*
 *
 * One JSON object per line (NDJSON — a workstation reads it with `jq`/a streaming line reader,
 * never a single 100 MB array a tool must load whole), `type` discriminating five closed record
 * shapes: `meta`, `session`, `over`, `gap`, `pass_outcome`. Constitution VI ("no number without
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
}
