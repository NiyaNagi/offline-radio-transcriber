package org.ort.capture.android.proveit

import org.ort.capture.android.heartbeat.HeartbeatRecord

/** What the "prove it" 30-minute test reports (test-plan §7, D8 → AC-64). */
public data class ProveItReport(
    val durationMillis: Long,
    val heartbeatCount: Int,
    val gapCount: Int,
    val maxGapMillis: Long,
    val processRestarted: Boolean,
    val predictedToSurviveEightHours: Boolean,
)

/**
 * The "prove it" 30-minute test mechanism (test-plan §7, D8 → AC-64). It cannot be run to a real
 * verdict on Robolectric — it needs the reference device with all four ColorOS interventions
 * applied, which is P9's job — so this class is the reporting half only: given the heartbeats
 * actually observed during a 30-minute run and whether the process restarted mid-run, it renders
 * the same continuity/gap-count/restarted verdict a human would read off D8's protocol.
 *
 * **`predictedToSurviveEightHours` is a prediction from a 30-minute sample, unverified until it
 * is checked against an actual 8-hour run on hardware (P9, AC-64).**
 */
public class ProveItAnalyzer(
    private val expectedIntervalMillis: Long,
    private val toleranceMillis: Long = DEFAULT_TOLERANCE_MILLIS,
) {

    public fun analyze(
        heartbeats: List<HeartbeatRecord>,
        durationMillis: Long,
        processRestarted: Boolean,
    ): ProveItReport {
        var gapCount = 0
        var maxGap = 0L
        for (i in 1 until heartbeats.size) {
            val delta = heartbeats[i].wallMillis - heartbeats[i - 1].wallMillis
            if (delta > expectedIntervalMillis + toleranceMillis) {
                gapCount++
                if (delta > maxGap) maxGap = delta
            }
        }
        return ProveItReport(
            durationMillis = durationMillis,
            heartbeatCount = heartbeats.size,
            gapCount = gapCount,
            maxGapMillis = maxGap,
            processRestarted = processRestarted,
            predictedToSurviveEightHours = !processRestarted && gapCount == 0,
        )
    }

    private companion object {
        const val DEFAULT_TOLERANCE_MILLIS = 5_000L
    }
}
