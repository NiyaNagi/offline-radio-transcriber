package org.ort.app.diagnostics

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.ort.core.AttributionState
import org.ort.data.OrtDatabase

/**
 * WP11e (register R-137, `Settings-Diagnostics.dc.html`'s `counts.json`, AC-120): "overs by state,
 * rejections by reason … numbers only". Reads only [org.ort.data.dao.SessionDao.listAll] and
 * [org.ort.data.dao.TransmissionDao.listAll] — never a station, voiceprint, transcript or callsign
 * table — so nothing here can carry an identity even by accident (proven at the bundle level, with
 * seeded station/voiceprint rows, in `DiagnosticsBundleBuilderTest`).
 *
 * [org.ort.data.entity.TransmissionEntity.rejectionReason] is free text (`"TOO_SHORT: segment is
 * 120 ms, below the 250 ms floor"`) — grouped by its leading rule code only (the part before the
 * first `:`), so the bundle carries a small, closed set of codes and counts, never the free-text
 * detail half of the string.
 *
 * `corrections by tier` (the board's own clause) is **not** produced: no per-transmission tier
 * field exists on [org.ort.data.entity.TransmissionEntity] or [org.ort.data.entity.CorrectionEntity]
 * to group by (checked against both entities before writing this) — `correctedOversCount` is the
 * honest total this build can compute instead. Left open for whichever change adds a tier field.
 */
public object CountsJsonProducer : DiagnosticsFileProducer {

    override suspend fun produce(context: Context): ByteArray = withContext(Dispatchers.IO) {
        val db = OrtDatabase.create(context.applicationContext)
        val sessions = db.sessionDao().listAll()
        val transmissions = db.transmissionDao().listAll()

        val json = JSONObject()
        json.put("sessionsCount", sessions.size)
        json.put("oversCount", transmissions.size)

        val stateCounts = JSONObject()
        for (state in AttributionState.entries) {
            stateCounts.put(state.name, transmissions.count { it.attributionState == state })
        }
        json.put("attributionStateCounts", stateCounts)

        val rejectionCounts = JSONObject()
        transmissions.asSequence()
            .mapNotNull { it.rejectionReason }
            .map { it.substringBefore(":").trim() }
            .filter { it.isNotEmpty() }
            .groupingBy { it }
            .eachCount()
            .forEach { (code, count) -> rejectionCounts.put(code, count) }
        json.put("rejectionReasonCounts", rejectionCounts)

        json.put("correctedOversCount", transmissions.count { it.corrected })

        json.toString(2).toByteArray(Charsets.UTF_8)
    }
}
