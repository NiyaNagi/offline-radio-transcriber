package org.ort.lexicon

import kotlin.math.min

/**
 * Static, offline band-plausibility model (FR-LEX-25, technical design §9.4): a function of
 * band, local solar time, season and the distance between the operator's grid and the
 * candidate prefix's allocation centroid. No network, no live solar indices — every input is
 * already in [PropagationInputs].
 *
 * The output is raw log-odds; [BandPlausibilityPrior] applies the asymmetric clamp that makes
 * it "demote, never eliminate" (FR-LEX-26). This class only has to be deterministic and bounded.
 */
public class PropagationModel {

    /** The distance (km) beyond which a contact on [band] is not ordinarily expected. */
    private fun maxPlausibleKm(band: String): Double = when (band.uppercase()) {
        "VHF", "UHF", "2M", "70CM", "1_25M" -> 300.0
        "6M" -> 3_000.0
        "60M", "80M", "160M" -> 4_000.0
        "30M", "40M" -> 12_000.0
        "10M", "12M", "15M" -> 15_000.0
        "17M", "20M" -> 20_000.0
        else -> 20_000.0
    }

    /** Lower HF bands favour night propagation; the upper HF bands favour day. A static, coarse
     * approximation of skip characteristics — not a solar model. */
    private fun dayNightFactor(band: String, localHour: Int): Double {
        val isNight = localHour < 6 || localHour >= 20
        val lowBand = band.uppercase() in setOf("160M", "80M", "60M", "40M")
        val highBand = band.uppercase() in setOf("10M", "12M", "15M", "17M", "20M")
        return when {
            lowBand && isNight -> 0.2
            highBand && !isNight -> 0.2
            else -> 0.0
        }
    }

    /** Winter favours low-band HF DX (lower atmospheric noise); a small, static, bounded term —
     * refined once the corpus supplies real per-band seasonal counts, which is exactly the sort
     * of asset P7's calibration mechanism can refit without a code change. */
    private fun seasonFactor(season: Season, band: String): Double {
        val lowBand = band.uppercase() in setOf("160M", "80M", "60M", "40M")
        return if (lowBand && season == Season.WINTER) 0.1 else 0.0
    }

    /** Raw (unclamped) log-odds plausibility of [inputs]. Deterministic: same inputs, same output. */
    public fun plausibilityLogOdds(inputs: PropagationInputs): Float {
        val maxKm = maxPlausibleKm(inputs.band)
        val distanceTerm = if (inputs.distanceKm <= maxKm) {
            0.3
        } else {
            -1.5 * min((inputs.distanceKm - maxKm) / maxKm, 1.0)
        }
        val dayNight = dayNightFactor(inputs.band, inputs.localHour)
        val season = seasonFactor(inputs.season, inputs.band)
        return (distanceTerm + dayNight + season).toFloat()
    }
}
