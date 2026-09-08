package org.ort.app.diagnostics

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.ort.app.ui.data.ModelCatalog
import org.ort.app.ui.data.ModelId
import org.ort.app.ui.data.ModelsController
import org.ort.data.OrtDatabase

/**
 * WP11e (register R-137, `Settings-Diagnostics.dc.html`'s `assets.json`, brief step 4): "every
 * model and lexicon: name, version, checksum, install date". Models are read through
 * [ModelsController.currentState] — audit F-008's real install-state read, the same one
 * `ModelsScreen` uses — never a second, drifting definition of "installed". The lexicon comes
 * straight from [org.ort.data.dao.CatalogDao.versionsFor], the same source
 * `org.ort.app.ui.data.RoomActiveLexiconStore` reads.
 *
 * "version" for a model here is [org.ort.app.BuildConfig.SHERPA_ONNX_VERSION] would be the runtime
 * library's version, not a per-model one — this build's [org.ort.app.ui.data.ModelCatalog] pins
 * models by checksum, not by a named version, so "install date" ([installedAtMillis]) is the real,
 * distinguishing fact reported instead: the installed file's own filesystem mtime, read directly
 * from disk, never fabricated.
 */
public object AssetsJsonProducer : DiagnosticsFileProducer {

    override suspend fun produce(context: Context): ByteArray = withContext(Dispatchers.IO) {
        val json = JSONObject()
        json.put("models", modelsArray(context))
        json.put("lexicon", lexiconObject(context))
        json.toString(2).toByteArray(Charsets.UTF_8)
    }

    private fun modelsArray(context: Context): JSONArray {
        val state = ModelsController.currentState(context)
        val array = JSONArray()
        for (row in state.rows) {
            val entry = JSONObject()
            entry.put("name", row.label)
            entry.put("status", row.status.name)
            row.checksumPrefix?.let { entry.put("checksumPrefix", it) }
            row.sizeBytes?.let { entry.put("sizeBytes", it) }
            installedAtMillis(context, row.id)?.let { entry.put("installedAtMillis", it) }
            array.put(entry)
        }
        return array
    }

    /** The installed destination file's own OS mtime — a real, on-disk fact — `null` when nothing
     * is installed there (never a fabricated install date). */
    private fun installedAtMillis(context: Context, id: ModelId): Long? {
        val destination = ModelCatalog.entry(id).destination(context.filesDir)
        return if (destination.isFile) destination.lastModified() else null
    }

    private suspend fun lexiconObject(context: Context): JSONObject {
        val db = OrtDatabase.create(context.applicationContext)
        val active = db.catalogDao().versionsFor(ModelsController.CALLSIGN_LEXICON_ASSET_ID).firstOrNull()
        val entry = JSONObject()
        entry.put("name", "Callsign lexicon")
        if (active != null) {
            entry.put("version", active.version)
            entry.put("checksum", active.checksum)
            entry.put("installedAtMillis", active.importedAt)
            entry.put("recordCount", active.recordCount)
        } else {
            entry.put("status", "not installed")
        }
        return entry
    }
}
