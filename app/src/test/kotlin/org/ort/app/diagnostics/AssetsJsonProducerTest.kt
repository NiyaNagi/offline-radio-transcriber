package org.ort.app.diagnostics

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.ModelId
import org.ort.app.ui.data.ModelsController
import org.ort.data.OrtDatabase
import org.ort.data.entity.LexiconVersionEntity
import org.robolectric.RobolectricTestRunner

/**
 * WP11e (register R-137, `Settings-Diagnostics.dc.html`'s `assets.json`, brief step 4): "every
 * model and lexicon: name, version, checksum, install date". Models are read through
 * [ModelsController.currentState] — the same real install-state read `ModelsScreen` already uses
 * (audit F-008) — never a second, drifting definition of "installed". The lexicon comes straight
 * from [org.ort.data.dao.CatalogDao.versionsFor], the same source `RoomActiveLexiconStore` reads.
 */
@RunWith(RobolectricTestRunner::class)
class AssetsJsonProducerTest {

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
    }

    @Test
    fun `FR_OBS_1 assets json lists every catalog model, not installed on a fresh device`() = runTest {
        val bytes = AssetsJsonProducer.produce(context)
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        val models = json.getJSONArray("models")

        assertEquals(ModelId.entries.size, models.length())
        for (i in 0 until models.length()) {
            val entry = models.getJSONObject(i)
            assertTrue(entry.has("name"))
            assertTrue(entry.has("status"))
            assertEquals("NOT_INSTALLED", entry.getString("status"))
        }
    }

    @Test
    fun `FR_OBS_1 assets json reports the active lexicon version, checksum and install date when one exists`() =
        runTest {
            db.catalogDao().insert(
                LexiconVersionEntity(
                    assetId = ModelsController.CALLSIGN_LEXICON_ASSET_ID,
                    version = "2026.08",
                    importedAt = 12_345L,
                    recordCount = 1_104_208,
                    checksum = "deadbeef",
                ),
            )

            val bytes = AssetsJsonProducer.produce(context)
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val lexicon = json.getJSONObject("lexicon")

            assertEquals("2026.08", lexicon.getString("version"))
            assertEquals("deadbeef", lexicon.getString("checksum"))
            assertEquals(12_345L, lexicon.getLong("installedAtMillis"))
            assertEquals(1_104_208, lexicon.getInt("recordCount"))
        }

    @Test
    fun `FR_OBS_1 with no lexicon imported, the lexicon entry honestly reports not installed`() = runTest {
        val bytes = AssetsJsonProducer.produce(context)
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        val lexicon = json.getJSONObject("lexicon")

        assertEquals("not installed", lexicon.getString("status"))
        assertTrue(!lexicon.has("checksum"))
    }
}
