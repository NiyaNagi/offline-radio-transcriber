package org.ort.lexicon

import org.ort.core.AssetRef
import kotlin.math.exp

/**
 * Platt scaling — a one-dimensional logistic mapping a raw ranking score to an estimated
 * probability of correctness (technical design §9.5, FR-LEX-17). Fitting and verification MUST
 * use different folds (constitution VI): [fit] takes whatever samples the caller hands it and
 * has no notion of which fold they came from — that discipline lives in `:eval`, not here.
 */
public class PlattCalibrator internal constructor(
    public val a: Float,
    public val b: Float,
    public val version: AssetRef,
) {

    /** The calibrated probability of correctness for a raw [score]. Always in `[0, 1]`. */
    public fun calibrate(score: Float): Float {
        val z = (a * score + b).toDouble()
        return (1.0 / (1.0 + exp(-z))).toFloat()
    }

    public companion object {
        /**
         * Fits `(a, b)` by gradient descent on the log-loss of [scores] against [labels] (`true`
         * = the candidate was correct). [version] is the calibration asset's own version — every
         * score-bearing record cites it (FR-LEX-18).
         */
        public fun fit(
            scores: List<Float>,
            labels: List<Boolean>,
            version: AssetRef,
            iterations: Int = 3_000,
            learningRate: Double = 0.05,
        ): PlattCalibrator {
            require(scores.size == labels.size) {
                "scores (${scores.size}) and labels (${labels.size}) must be the same length"
            }
            require(scores.isNotEmpty()) { "cannot fit a calibrator on an empty sample" }
            var a = 1.0
            var b = 0.0
            val n = scores.size
            repeat(iterations) {
                var gradA = 0.0
                var gradB = 0.0
                for (i in 0 until n) {
                    val s = scores[i].toDouble()
                    val y = if (labels[i]) 1.0 else 0.0
                    val z = a * s + b
                    val p = 1.0 / (1.0 + exp(-z))
                    val err = p - y
                    gradA += err * s
                    gradB += err
                }
                a -= learningRate * gradA / n
                b -= learningRate * gradB / n
            }
            return PlattCalibrator(a.toFloat(), b.toFloat(), version)
        }
    }
}
