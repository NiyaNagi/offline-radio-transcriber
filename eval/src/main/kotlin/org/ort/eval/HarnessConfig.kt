package org.ort.eval

/**
 * Everything a reported number must carry (constitution VI: "never report a number without its
 * fold, machine and provider"). [precisionTarget] is the tier's precision target, from which the
 * `CONFIRMED` threshold is derived (FR-LEX-19) — never a raw score.
 */
public data class HarnessConfig(
    val fold: String,
    val machine: String,
    val provider: String,
    val threadCount: Int,
    val runtimeVersion: String,
    val precisionTarget: Float = 0.9f,
)
