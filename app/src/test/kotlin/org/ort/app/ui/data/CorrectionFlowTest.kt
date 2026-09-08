package org.ort.app.ui.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ort.data.dao.CorrectionDao

/**
 * Build-plan P16, FR-UI-6 + Q8's tiered correction: pick from a resolved candidate, search the
 * lexicon, or type free text. Only the first two can feed the priors (they name an already-known,
 * ITU-allocated identity); free text must be *marked* unverified, not quietly treated as ground
 * truth. This is pure mapping — [org.ort.app.ui.data.ReaderPolling]'s extension is what actually
 * calls [org.ort.data.dao.CorrectionDao.recordCorrection]; this class only proves the tier -> field
 * mapping the write path depends on.
 *
 * Audit F-018: the middle tier was `SEARCH_KNOWN_STATION` (a substitute over `:data`'s known
 * stations, since `:app` had no path to `:lexicon`); it is now `SEARCH_LEXICON`, backed by
 * `:pipeline`'s `LexiconLookup` — see [org.ort.app.ui.data.CorrectionTier]'s doc comment.
 */
class CorrectionFlowTest {

    @Test
    fun `FR_UI_6 picking a resolved candidate records a verified station correction`() {
        val entity = CorrectionRequest(
            transmissionId = "TX1",
            previousStationId = "K7ABC",
            newStationId = "W7NPC",
            tier = CorrectionTier.PICK_CANDIDATE,
            correctedAtMillis = 100L,
        ).toEntity(idGenerator = { "CORR1" })

        assertEquals(CorrectionDao.FIELD_STATION, entity.field)
        assertEquals("W7NPC", entity.newValue)
        assertEquals("K7ABC", entity.previousValue)
        assertEquals("TX1", entity.transmissionId)
        assertEquals("CORR1", entity.id)
        assertEquals(100L, entity.correctedAt)
    }

    @Test
    fun `FR_UI_6_Q8 searching the lexicon also records a verified station correction`() {
        val entity = CorrectionRequest(
            transmissionId = "TX1",
            previousStationId = null,
            newStationId = "K9ZZZ",
            tier = CorrectionTier.SEARCH_LEXICON,
            correctedAtMillis = 5L,
        ).toEntity(idGenerator = { "CORR2" })

        assertEquals(CorrectionDao.FIELD_STATION, entity.field)
    }

    @Test
    fun `Q8 free text is marked unverified, never quietly treated as ground truth`() {
        val entity = CorrectionRequest(
            transmissionId = "TX1",
            previousStationId = null,
            newStationId = "N0CALL",
            tier = CorrectionTier.FREE_TEXT,
            correctedAtMillis = 5L,
        ).toEntity(idGenerator = { "CORR3" })

        assertEquals(CorrectionDao.FIELD_STATION_UNVERIFIED, entity.field)
    }
}
