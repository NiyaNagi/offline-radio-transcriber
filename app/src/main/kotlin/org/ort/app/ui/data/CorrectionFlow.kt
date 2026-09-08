package org.ort.app.ui.data

import org.ort.core.Ulid
import org.ort.data.dao.CorrectionDao
import org.ort.data.entity.CorrectionEntity

/**
 * Build-plan P16, FR-UI-6 + FR-SPK-7 + Q8's tiered correction (implementation-plan M5): one-tap
 * correction of an attribution. Q8 names exactly three tiers, in order of preference: pick from
 * the resolved candidates, search the lexicon, or free text marked unverified.
 *
 * **Audit F-018, resolved.** P16 originally shipped [SEARCH_LEXICON] as `SEARCH_KNOWN_STATION` —
 * searching stations `:data` already knew about, because `:app` has no path to `:lexicon` and
 * P16's own scope forbade touching `:pipeline` to add one. That call site now exists
 * ([org.ort.pipeline.passb.LexiconLookup], `:pipeline`) and [ReaderPolling.searchLexicon] wires
 * it in, so this tier is true lexicon search — a callsign search over the bundled ITU allocation
 * table and callsign grammar, not limited to stations this device has already heard. Q8 names
 * only three tiers, not four, so the known-stations substitute is *replaced* here, not kept
 * alongside it (per this fix's own instruction: keep the substitute only if Q8 names both — it
 * does not).
 *
 * Both [PICK_CANDIDATE] and [SEARCH_LEXICON] name an already-known, ITU-allocated identity (a
 * resolved candidate, or a lexicon match) and are recorded as [CorrectionDao.FIELD_STATION] —
 * eligible, in principle, to feed a future prior. [FREE_TEXT] is recorded as
 * [CorrectionDao.FIELD_STATION_UNVERIFIED] so it can never be silently treated as ground truth by
 * code that has not been told to check the field.
 */
public enum class CorrectionTier { PICK_CANDIDATE, SEARCH_LEXICON, FREE_TEXT }

public data class CorrectionRequest(
    val transmissionId: String,
    val previousStationId: String?,
    val newStationId: String,
    val tier: CorrectionTier,
    val correctedAtMillis: Long,
) {
    public fun toEntity(idGenerator: () -> String = { Ulid.generate().toString() }): CorrectionEntity {
        val field = if (tier == CorrectionTier.FREE_TEXT) {
            CorrectionDao.FIELD_STATION_UNVERIFIED
        } else {
            CorrectionDao.FIELD_STATION
        }
        return CorrectionEntity(
            id = idGenerator(),
            transmissionId = transmissionId,
            field = field,
            previousValue = previousStationId,
            newValue = newStationId,
            correctedAt = correctedAtMillis,
        )
    }
}
