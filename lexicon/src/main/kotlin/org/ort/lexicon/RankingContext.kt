package org.ort.lexicon

/** One repeater/frequency match from the Rig Module (FR-LEX-9's strongest prior when present). */
public data class RepeaterMatch(
    val frequencyHz: Long,
    val expectedCallsigns: Set<String>,
    val isKnownRepeaterOrInternetLinked: Boolean = false,
)

/** How recently, if ever, this station has been heard (FR-LEX-9's recency prior). */
public data class RecencyInfo(val secondsSinceLastHeard: Long)

/** Whether the other station in this thread has already been identified (FR-LEX-9). */
public data class ConversationInfo(val otherStationIdentified: Boolean, val otherStationCallsign: String? = null)

/** Coarse season bucket for the static propagation model (FR-LEX-25). */
public enum class Season { WINTER, SPRING, SUMMER, AUTUMN }

/** Inputs to the static, offline propagation model (FR-LEX-25). */
public data class PropagationInputs(val band: String, val localHour: Int, val season: Season, val distanceKm: Double) {
    init {
        require(localHour in 0..23) { "localHour must be 0..23, was $localHour" }
        require(distanceKm >= 0.0) { "distanceKm must be >= 0, was $distanceKm" }
    }
}

/**
 * Everything the priors of technical design §9.3 read, gathered in one place. Every field
 * defaults to `null` (or an empty collection where that is meaningfully different from "no
 * data") to represent **cold start** honestly (FR-LEX-31): a prior with nothing to say
 * contributes exactly zero rather than guessing a default, and [RankedCandidate] surfaces that
 * as a widened interval rather than a distorted score.
 */
public data class RankingContext(
    val repeater: RepeaterMatch? = null,
    /** `null` = no database loaded at all (cold). An empty set is a loaded, empty database. */
    val databaseHits: Set<String>? = null,
    /** `null` = no recency subsystem at all (cold). A candidate absent from a non-null map is a
     * candidate genuinely never heard, which is evidence-free, not cold. */
    val recency: Map<String, RecencyInfo>? = null,
    /** `null` = operator location unknown (cold, FR-LEX-22/23). Returns null for an unknown prefix. */
    val geographicDistanceKm: ((prefix: String) -> Double?)? = null,
    val conversation: ConversationInfo? = null,
    /** `null` = the user has not configured a "my stations" list at all (cold). */
    val myStations: Set<String>? = null,
    val propagation: PropagationInputs? = null,
)
