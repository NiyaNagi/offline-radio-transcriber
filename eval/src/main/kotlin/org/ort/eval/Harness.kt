package org.ort.eval

import org.ort.core.AssetRef
import org.ort.lexicon.CallsignGrammar
import org.ort.lexicon.PlattCalibrator
import org.ort.lexicon.PriorCombiner
import org.ort.lexicon.RankedCandidate
import org.ort.lexicon.ThresholdDerivation

/**
 * The evaluation harness (technical design §17, AC-35): a pure function of a labelled corpus, a
 * grammar, a set of priors and a config — same inputs, same output, which is what AC-90 tests.
 * `:eval` composes the pure JVM pipeline built so far (`:lexicon`); the ASR passes (`:asr-*`,
 * build-plan P10) and capture (`:capture-*`, P4/P8) are later waves, so a [LabeledOccurrence]
 * arrives with its lattice already built rather than as raw audio.
 */
public class Harness(private val grammar: CallsignGrammar, private val combiner: PriorCombiner) {

    /**
     * [fitOn] and [evaluate] are separate parameters, not one list reused twice, so that the one
     * caller who ever wires the real `eval` fold in (build-plan P7: "Platt calibration fitted on
     * train+dev and verified on eval") cannot accidentally fit the calibrator on the same data it
     * is scored against — the constitution's "never read the eval fold" rule extended to
     * calibration leakage, not just raw access. Diagnostic self-consistency checks (e.g. AC-55 on
     * the dev fold) may legitimately pass the same list for both; what this signature forbids is
     * doing that *by default* when the two folds are meant to differ.
     */
    public fun run(fitOn: List<LabeledOccurrence>, evaluate: List<LabeledOccurrence>, config: HarnessConfig): HarnessReport {
        val fitResolved = fitOn.map { it to resolveWith(combiner, it) }
        val resolved = evaluate.map { it to resolveWith(combiner, it) }

        val (fitScores, fitLabels) = trainingPairs(fitResolved)
        val calibrator = if (fitScores.isNotEmpty()) {
            PlattCalibrator.fit(fitScores, fitLabels, AssetRef("eval-harness-calibration", config.fold))
        } else {
            null
        }
        val fitCalibrated = fitResolved.map { (_, top) -> calibrator?.calibrate(top?.totalScore ?: NO_CANDIDATE_SCORE) ?: 0f }
        val calibrated = resolved.map { (_, top) -> calibrator?.calibrate(top?.totalScore ?: NO_CANDIDATE_SCORE) ?: 0f }

        // The threshold is derived from the fit set alone (technical design §9.5) — deriving it
        // from `evaluate` would be the same leakage this split exists to prevent.
        val fitTruthLabels = fitResolved.map { (occ, top) -> top != null && top.candidate.text == occ.truthCallsign }
        val threshold = calibrator?.let {
            ThresholdDerivation.forPrecisionTarget(fitCalibrated, fitTruthLabels, config.precisionTarget)
        }

        val metrics = confirmedMetrics(resolved, calibrated, threshold?.threshold)
        val diagram = reliabilityDiagram(resolved, calibrated)
        val ablation = combiner.priorNames.sorted().map { name ->
            val ablated = combiner.withoutPrior(name)
            val ablatedResolved = evaluate.map { it to resolveWith(ablated, it) }
            PriorAblationResult(name, topPickMetrics(ablatedResolved))
        }

        val fingerprint = RunFingerprint(
            config.fold,
            config.machine,
            config.provider,
            config.threadCount,
            config.runtimeVersion,
        )
        return HarnessReport(fingerprint, metrics, diagram, ablation, threshold?.threshold)
    }

    private fun resolveWith(combiner: PriorCombiner, occurrence: LabeledOccurrence): RankedCandidate? {
        val candidates = grammar.parse(occurrence.lattice)
        if (candidates.isEmpty()) return null
        return combiner.rank(candidates, occurrence.context).firstOrNull()
    }

    private fun trainingPairs(
        resolved: List<Pair<LabeledOccurrence, RankedCandidate?>>,
    ): Pair<List<Float>, List<Boolean>> {
        val scores = ArrayList<Float>()
        val labels = ArrayList<Boolean>()
        for ((occurrence, top) in resolved) {
            if (top == null) continue
            scores += top.totalScore
            labels += (top.candidate.text == occurrence.truthCallsign)
        }
        return scores to labels
    }

    /** Standard binary framing: a prediction is the top candidate's text, or no prediction at all. */
    private fun topPickMetrics(resolved: List<Pair<LabeledOccurrence, RankedCandidate?>>): CallsignMetrics {
        var tp = 0
        var fp = 0
        var fn = 0
        for ((occurrence, top) in resolved) {
            val predicted = top?.candidate?.text
            when {
                predicted != null && predicted == occurrence.truthCallsign -> tp++
                predicted != null -> fp++
                occurrence.truthCallsign != null -> fn++
            }
        }
        return CallsignMetrics(tp, fp, fn)
    }

    /** Same framing as [topPickMetrics], gated by the CONFIRMED threshold rather than a bare top pick. */
    private fun confirmedMetrics(
        resolved: List<Pair<LabeledOccurrence, RankedCandidate?>>,
        calibratedScores: List<Float>,
        threshold: Float?,
    ): CallsignMetrics {
        var tp = 0
        var fp = 0
        var fn = 0
        for (i in resolved.indices) {
            val (occurrence, top) = resolved[i]
            val confirmed = if (top != null && threshold != null && calibratedScores[i] >= threshold) {
                top.candidate.text
            } else {
                null
            }
            when {
                confirmed != null && confirmed == occurrence.truthCallsign -> tp++
                confirmed != null -> fp++
                occurrence.truthCallsign != null -> fn++
            }
        }
        return CallsignMetrics(tp, fp, fn)
    }

    private fun reliabilityDiagram(
        resolved: List<Pair<LabeledOccurrence, RankedCandidate?>>,
        calibratedScores: List<Float>,
        binCount: Int = RELIABILITY_BIN_COUNT,
    ): ReliabilityDiagram {
        val bins = (0 until binCount).map { i ->
            val low = i.toFloat() / binCount
            val high = (i + 1).toFloat() / binCount
            val inBin = calibratedScores.indices.filter { idx ->
                val s = calibratedScores[idx]
                if (i == binCount - 1) s in low..high else s >= low && s < high
            }
            if (inBin.isEmpty()) {
                ReliabilityBin(low, high, 0, 0f, 0f)
            } else {
                val meanPredicted = inBin.map { calibratedScores[it] }.average().toFloat()
                val correct = inBin.count { idx ->
                    val (occurrence, top) = resolved[idx]
                    top != null && top.candidate.text == occurrence.truthCallsign
                }
                ReliabilityBin(low, high, inBin.size, meanPredicted, correct.toFloat() / inBin.size)
            }
        }
        return ReliabilityDiagram(bins)
    }

    private companion object {
        const val NO_CANDIDATE_SCORE = Float.NEGATIVE_INFINITY
        const val RELIABILITY_BIN_COUNT = 10
    }
}
