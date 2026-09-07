package org.ort.eval

import java.util.Locale
import kotlin.math.abs

private fun fmt(pattern: String, value: Float): String = String.format(Locale.ROOT, pattern, value)

/** `(machine, provider, threads, runtime version, fold)` — technical design §17: every report
 * carries these, because determinism is bounded to exactly this tuple, not stated as absolute. */
public data class RunFingerprint(
    val fold: String,
    val machine: String,
    val provider: String,
    val threadCount: Int,
    val runtimeVersion: String,
) {
    public fun canonical(): String =
        "fold=$fold;machine=$machine;provider=$provider;threads=$threadCount;runtime=$runtimeVersion"
}

/** Callsign precision/recall (technical design §17). */
public data class CallsignMetrics(val truePositives: Int, val falsePositives: Int, val falseNegatives: Int) {
    public val precision: Float
        get() {
            val denom = truePositives + falsePositives
            return if (denom == 0) 0f else truePositives.toFloat() / denom
        }

    public val recall: Float
        get() {
            val denom = truePositives + falseNegatives
            return if (denom == 0) 0f else truePositives.toFloat() / denom
        }
}

/** One bin of a reliability diagram (FR-LEX-20): predicted confidence versus observed accuracy. */
public data class ReliabilityBin(
    val binLow: Float,
    val binHigh: Float,
    val count: Int,
    val meanPredicted: Float,
    val observedAccuracy: Float,
)

/** AC-55: a confidence of 0.9 should correspond to ~90% observed accuracy, evidenced per bin. */
public data class ReliabilityDiagram(val bins: List<ReliabilityBin>) {
    /** Weighted mean absolute gap between predicted confidence and observed accuracy. */
    public val expectedCalibrationError: Float
        get() {
            val total = bins.sumOf { it.count }
            if (total == 0) return 0f
            return bins.sumOf { bin ->
                (bin.count.toDouble() / total) * abs(bin.observedAccuracy - bin.meanPredicted).toDouble()
            }.toFloat()
        }
}

/** One prior removed and the metrics that result — the harness's per-prior ablation (build-plan P7). */
public data class PriorAblationResult(val priorRemoved: String, val metrics: CallsignMetrics)

/**
 * The harness's full output for one run (technical design §17): fingerprint, callsign
 * precision/recall, a reliability diagram, and a per-prior ablation.
 */
public data class HarnessReport(
    val fingerprint: RunFingerprint,
    val callsignMetrics: CallsignMetrics,
    val reliabilityDiagram: ReliabilityDiagram,
    val ablation: List<PriorAblationResult>,
    val confirmedThreshold: Float?,
) {
    /**
     * A deterministic, field-order-fixed text form (AC-90): two runs over the same corpus,
     * model and configuration must be byte-identical, so nothing here may depend on hash-map or
     * hash-set iteration order — every collection below is explicitly sorted first.
     */
    public fun canonicalText(): String = buildString {
        appendLine("fingerprint:${fingerprint.canonical()}")
        appendLine("precision:${fmt("%.6f", callsignMetrics.precision)}")
        appendLine("recall:${fmt("%.6f", callsignMetrics.recall)}")
        appendLine(
            "tp:${callsignMetrics.truePositives};fp:${callsignMetrics.falsePositives};" +
                "fn:${callsignMetrics.falseNegatives}",
        )
        appendLine("confirmedThreshold:${confirmedThreshold?.let { fmt("%.6f", it) } ?: "none"}")
        appendLine("ece:${fmt("%.6f", reliabilityDiagram.expectedCalibrationError)}")
        for (bin in reliabilityDiagram.bins) {
            appendLine(
                "bin:[${fmt("%.2f", bin.binLow)},${fmt("%.2f", bin.binHigh)}]:n=${bin.count}:" +
                    "pred=${fmt("%.4f", bin.meanPredicted)}:obs=${fmt("%.4f", bin.observedAccuracy)}",
            )
        }
        for (a in ablation.sortedBy { it.priorRemoved }) {
            appendLine(
                "ablation:${a.priorRemoved}:p=${fmt("%.6f", a.metrics.precision)}:r=${fmt("%.6f", a.metrics.recall)}",
            )
        }
    }
}
