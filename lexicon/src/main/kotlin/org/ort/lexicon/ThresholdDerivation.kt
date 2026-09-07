package org.ort.lexicon

/**
 * Derives the `CONFIRMED` threshold from a precision target (FR-LEX-19, AC-56) rather than
 * exposing a raw score to configure: the caller sets a target precision for the active tier,
 * and this scans the calibrated precision/recall curve for the lowest threshold that meets it,
 * maximising recall subject to the constraint (NFR-1a — precision outranks recall).
 */
public object ThresholdDerivation {

    /** [threshold] is a calibrated score in `[0, 1]` — never a raw ranking score. */
    public data class Result(val threshold: Float, val achievedPrecision: Float, val achievedRecall: Float)

    /**
     * The lowest threshold over [calibratedScores] whose precision against [labels] is at or
     * above [precisionTarget], or `null` if no threshold reaches it (an honest "cannot meet this
     * target on this data", never a false promise).
     */
    public fun forPrecisionTarget(
        calibratedScores: List<Float>,
        labels: List<Boolean>,
        precisionTarget: Float,
    ): Result? {
        require(calibratedScores.size == labels.size) {
            "scores (${calibratedScores.size}) and labels (${labels.size}) must be the same length"
        }
        require(precisionTarget in 0f..1f) { "precisionTarget must be in [0,1], was $precisionTarget" }
        val totalPositives = labels.count { it }
        val sorted = calibratedScores.zip(labels).sortedByDescending { it.first }
        var truePositives = 0
        var falsePositives = 0
        var best: Result? = null
        for ((score, label) in sorted) {
            if (label) truePositives++ else falsePositives++
            val precision = truePositives.toFloat() / (truePositives + falsePositives)
            if (precision >= precisionTarget) {
                val recall = if (totalPositives == 0) 0f else truePositives.toFloat() / totalPositives
                // Keep scanning to the lowest threshold that still meets the target: recall is
                // monotonically non-decreasing as the threshold falls, so the last update wins.
                best = Result(score, precision, recall)
            }
        }
        return best
    }
}
