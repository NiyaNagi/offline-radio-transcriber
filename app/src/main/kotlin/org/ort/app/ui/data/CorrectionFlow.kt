package org.ort.app.ui.data

import org.ort.core.Ulid
import org.ort.data.dao.CorrectionDao
import org.ort.data.entity.CorrectionEntity

/**
 * Build-plan P16, FR-UI-6 + FR-SPK-7 + Q8's tiered correction (implementation-plan M5): one-tap
 * correction of an attribution. Q8 names three tiers, in order of preference: pick from the
 * resolved candidates, search the lexicon, or free text marked unverified.
 *
 * **Module boundary note.** "Search the lexicon" cannot be built here: `:app` has no path to
 * `:lexicon` (see [InspectionViewStateMapper]'s doc comment for the same constraint), and reaching
 * it would mean either widening `:app`'s allowed edges or adding a `:pipeline` call site — both
 * out of this prompt's scope (`:pipeline` is explicitly not to be touched). [SEARCH_KNOWN_STATION]
 * is the honest substitute this prompt actually built: searching stations `:data` already knows
 * about (heard before, resolved before) rather than the full ULS lexicon. This is a real,
 * reported divergence, not a silent narrowing of Q8 — a future session with a `:pipeline` call
 * site can add true lexicon search alongside it without changing this tier's shape.
 *
 * Both [PICK_CANDIDATE] and [SEARCH_KNOWN_STATION] name an already-known identity (a resolved
 * candidate, or a station this device has heard before) and are recorded as
 * [CorrectionDao.FIELD_STATION] — eligible, in principle, to feed a future prior. [FREE_TEXT] is
 * recorded as [CorrectionDao.FIELD_STATION_UNVERIFIED] so it can never be silently treated as
 * ground truth by code that has not been told to check the field.
 */
public enum class CorrectionTier { PICK_CANDIDATE, SEARCH_KNOWN_STATION, FREE_TEXT }

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
