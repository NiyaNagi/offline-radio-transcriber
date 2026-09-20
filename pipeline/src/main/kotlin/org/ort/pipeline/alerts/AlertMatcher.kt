package org.ort.pipeline.alerts

import kotlin.math.abs

/**
 * Build-plan P31 (FR-ALR-1, FR-ALR-3): a pure decision, no I/O — given one resolved transmission
 * and one watch, did it match. Deliberately a plain function rather than something stateful: the
 * same [AlertMatchInput] always produces the same answer, so a caller can evaluate every enabled
 * watch against one closure with no ordering dependency between them.
 */
public object AlertMatcher {

    /** functional spec §7.19: "a frequency waking up" — ordinary rig retuning drift reads as the
     * same watched frequency; a genuinely different channel does not. 2.5 kHz is comfortably
     * inside one NBFM channel step (12.5/25 kHz) and outside the next one over. */
    public const val FREQUENCY_TOLERANCE_HZ: Long = 2_500L

    public fun matches(watch: AlertWatch, input: AlertMatchInput): Boolean = when (watch) {
        is AlertWatch.Callsign -> input.stationId?.equals(watch.callsign, ignoreCase = true) == true
        is AlertWatch.Keyword -> input.transcriptText?.contains(watch.keyword, ignoreCase = true) == true
        is AlertWatch.Frequency ->
            input.frequencyHz != null &&
                abs(input.frequencyHz - watch.frequencyHz) <= FREQUENCY_TOLERANCE_HZ
    }
}
