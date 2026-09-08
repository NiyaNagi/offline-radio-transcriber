package org.ort.pipeline.backlog

/**
 * AC-75's *mechanism* (build-plan P11, NFR-2b): "no backlog growth over a sustained run" is a
 * claim about the **trend** of the queue depth, not any single sample — an oscillating backlog
 * that always drains back down is fine (that is what "keeps up" looks like); one with a
 * sustained positive slope means the shed controller's thresholds (`:pipeline`'s
 * [org.ort.pipeline.shed.ShedController], P8) are not shedding enough, or drain capacity is
 * genuinely insufficient, and either way the run will eventually exhaust storage (constitution
 * IV: only storage exhaustion may stop capture, but a monitor exists precisely so that point is
 * never silently approached).
 *
 * Method: ordinary least-squares slope of backlog depth against sample index. A slope is only
 * called "growth" when it is both positive and large relative to the mean depth
 * ([relativeSlopeThreshold]) — an absolute-only threshold would flag a backlog oscillating
 * between 0 and 2 as "growing" on noise alone.
 */
public class BacklogGrowthMonitor(private val relativeSlopeThreshold: Double = DEFAULT_RELATIVE_SLOPE_THRESHOLD) {

    private val samples = mutableListOf<Int>()

    public fun sample(backlogDepth: Int) {
        require(backlogDepth >= 0) { "a backlog depth cannot be negative, was $backlogDepth" }
        samples += backlogDepth
    }

    public val sampleCount: Int get() = samples.size

    /**
     * `true` iff the least-squares slope of the recorded samples is positive and exceeds
     * [relativeSlopeThreshold] times the mean depth. Fewer than [MIN_SAMPLES] samples cannot
     * support a verdict either way — `false`, not a fabricated answer either direction.
     */
    public fun isGrowing(): Boolean {
        if (samples.size < MIN_SAMPLES) return false
        val n = samples.size
        val xs = (0 until n).map { it.toDouble() }
        val ys = samples.map { it.toDouble() }
        val meanX = xs.average()
        val meanY = ys.average()
        val numerator = xs.indices.sumOf { (xs[it] - meanX) * (ys[it] - meanY) }
        val denominator = xs.sumOf { (it - meanX) * (it - meanX) }
        if (denominator == 0.0) return false
        val slope = numerator / denominator
        // A backlog that is always zero has no meaningful "relative" growth; guard the divide.
        val scale = if (meanY > 0.0) meanY else 1.0
        return slope > 0.0 && (slope / scale) > relativeSlopeThreshold
    }

    private companion object {
        const val MIN_SAMPLES = 5
        const val DEFAULT_RELATIVE_SLOPE_THRESHOLD = 0.02
    }
}
