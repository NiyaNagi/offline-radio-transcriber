package org.ort.app.fieldreport.bundle

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.ort.app.diagnostics.DiagnosticsFileProducer
import org.ort.data.OrtDatabase
import org.ort.data.entity.VoiceprintEntity

/**
 * D38/FR-SPK-20 (amended), FR-OBS-9's `VOICEPRINT_EMBEDDINGS` category: the one field-report gated
 * category that reads [org.ort.data.entity.VoiceprintEntity.embedding] at all — the embedding is
 * constitution V's fourth category, "no longer absolute", and only through this opt-in,
 * per-upload, per-category channel. Never wired into [org.ort.app.diagnostics.DiagnosticsBundleBuilder]'s
 * own producers, which structurally never touch a voiceprint at all (that object's own doc
 * comment, AC-109).
 *
 * **Gap closed (D45).** This producer used to walk every station [org.ort.data.dao.ActivityDao.listStations]
 * returns and collect each one's bound voiceprints via [org.ort.data.dao.CatalogDao.voiceprintsForStation]
 * — `:data` had no "every voiceprint" query, so a voiceprint never yet bound to any station
 * (`boundStationId == null`) was silently unreachable and absent from this file's output, even
 * though the consent screen names this whole category before every upload (FR-OBS-9). D45 adds
 * [org.ort.data.dao.CatalogDao.allVoiceprints] to close exactly that gap (register R-1010); this
 * producer now reads it directly, so an unbound voiceprint is included like any other.
 */
public object VoiceprintEmbeddingsProducer : DiagnosticsFileProducer {

    /** The file name this category's entry takes inside a field-report bundle. */
    public const val FILE_NAME: String = "voiceprints.json"

    /** The exact query [produce] itself uses, pulled out so
     * [org.ort.app.diagnostics.localsave.LocalSaveBundleBuilder] can ask "is there anything here at
     * all" ([count]) without a second, independently-written copy of the same query — the identical
     * reuse discipline [org.ort.app.fieldreport.bundle.FieldReportBundleBuilder.screenFrameFiles]'s
     * own visibility change (WPDUMP) applies one file over. D45: reads
     * [org.ort.data.dao.CatalogDao.allVoiceprints] directly — every voiceprint, station-bound or
     * not — rather than the old per-station walk that could never reach an unbound one. */
    private suspend fun loadVoiceprints(context: Context): List<VoiceprintEntity> = withContext(Dispatchers.IO) {
        val db = OrtDatabase.create(context.applicationContext)
        db.catalogDao().allVoiceprints()
    }

    /** WPDUMP: the real count behind the local-save checklist's `VOICEPRINT_EMBEDDINGS` row —
     * `0` is the honest "nothing to include yet" signal that row's own disabled/reasoned state is
     * built from, never a placeholder guessed some other way. */
    internal suspend fun count(context: Context): Int = loadVoiceprints(context).size

    override suspend fun produce(context: Context): ByteArray = withContext(Dispatchers.IO) {
        val voiceprints = loadVoiceprints(context)

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
