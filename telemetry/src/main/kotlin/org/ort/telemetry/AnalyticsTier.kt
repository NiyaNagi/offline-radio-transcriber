package org.ort.telemetry

/**
 * FR-ANL-1: analytics exists in **exactly three tiers**, each a closed, documented field list —
 * never an open schema a future change can extend without a spec amendment. [TIER_1] is on by
 * default and can be turned off; [TIER_2] and [TIER_3] are opt-in, off until the operator turns
 * each on individually (see [AnalyticsTierPreferences]).
 */
public enum class AnalyticsTier {
    /** FR-ANL-2's closed field list. Never transcript text, a callsign, a name, station
     * knowledge or location of any precision — see [AnalyticsTier1Payload]. */
    TIER_1,

    /** FR-ANL-3's closed field list, opt-in: transcript text and callsigns, including
     * `(ASR hypothesis, user correction)` pairs — see [AnalyticsTier2Payload]. */
    TIER_2,

    /** FR-ANL-4's closed field list, opt-in: retained over audio with its corrected transcript —
     * see [AnalyticsTier3Payload]. */
    TIER_3,
}
