package org.ort.app.fieldreport.bundle

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.ort.app.diagnostics.DiagnosticsFileProducer
import org.ort.data.OrtDatabase

/**
 * D38/FR-SPK-20 (amended), FR-OBS-9's `VOICEPRINT_EMBEDDINGS` category: the one field-report gated
 * category that reads [org.ort.data.entity.VoiceprintEntity.embedding] at all — the embedding is
 * constitution V's fourth category, "no longer absolute", and only through this opt-in,
 * per-upload, per-category channel. Never wired into [org.ort.app.diagnostics.DiagnosticsBundleBuilder]'s
 * own producers, which structurally never touch a voiceprint at all (that object's own doc
 * comment, AC-109).
 *
 * **Known gap, stated rather than silently narrowed (constitution I).** `:data`'s public API
 * ([org.ort.data.dao.CatalogDao], [org.ort.data.dao.ActivityDao] — checked before writing this;
 * the `:data` module is outside this round's own file-ownership map to extend) has no "every voiceprint"
 * query, only [org.ort.data.dao.CatalogDao.voiceprintsForStation], keyed by a station id. This
 * producer therefore walks every station [org.ort.data.dao.ActivityDao.listStations] returns and
 * collects each one's bound voiceprints — a voiceprint never yet bound to any station
 * (`boundStationId == null`) is not reachable this way, and is silently absent from this file's
 * output rather than from a station's own row. Flagged as real follow-up work: a `:data`-side
 * `CatalogDao.allVoiceprints()` (or equivalent) would close it; adding one is outside this
 * package's ownership this round.
 */
public object VoiceprintEmbeddingsProducer : DiagnosticsFileProducer {

    /** The file name this category's entry takes inside a field-report bundle. */
    public const val FILE_NAME: String = "voiceprints.json"

    override suspend fun produce(context: Context): ByteArray = withContext(Dispatchers.IO) {
        val db = OrtDatabase.create(context.applicationContext)
        val stations = db.activityDao().listStations()
        val voiceprints = stations
            .flatMap { station -> db.catalogDao().voiceprintsForStation(station.id) }
            .distinctBy { it.id }

        val array = JSONArray()
        for (voiceprint in voiceprints) {
            val entry = JSONObject()
            entry.put("id", voiceprint.id)
            entry.put("embeddingBase64", Base64.encodeToString(voiceprint.embedding, Base64.NO_WRAP))
            entry.put("memberCount", voiceprint.memberCount)
            voiceprint.embeddingModelId?.let { entry.put("embeddingModelId", it) }
            voiceprint.embeddingModelVersion?.let { entry.put("embeddingModelVersion", it) }
            array.put(entry)
        }
        val json = JSONObject()
        json.put("voiceprints", array)
        json.toString(2).toByteArray(Charsets.UTF_8)
    }
}
