package org.ort.app.analytics

import org.ort.core.AttributionState
import org.ort.core.capture.conformsToFrSeg1
import org.ort.data.entity.TransmissionEntity
import org.ort.telemetry.AnalyticsTier1Payload

/**
 * FR-ANL-2's "aggregate transcript-quality statistics — correction rate by field, confidence and
 * attribution-state mix, unresolved-callsign rate, VAD-fallback rate (FR-SEG-10)". A pure function
 * over already-read rows, never an entity graph serialised into the payload (FR-ANL-6): every
 * field below is a count or a rate, recomputed fresh every call, never a transcript, a callsign,
 * or a per-row identifier.
 *
 * [transmissions] and [correctionFields] are both plain reads a caller already has to do (
 * [org.ort.data.dao.TransmissionDao.listAll], [org.ort.data.dao.CorrectionDao.listAllFields]) —
 * this object owns none of the `:data` access itself, only the arithmetic, so it is testable with
 * plain in-memory lists and no Room/Robolectric dependency at all.
 */
public object QualityStatsAggregator {

    private const val HIGH_CONFIDENCE_THRESHOLD = 0.8
    private const val MEDIUM_CONFIDENCE_THRESHOLD = 0.5
    private const val CONFIDENCE_HIGH = "high"
    private const val CONFIDENCE_MEDIUM = "medium"
    private const val CONFIDENCE_LOW = "low"

    /** `null` when there is nothing to aggregate yet (a fresh install, or between sessions before
     * any transmission exists) — an honest "not yet measured", never a fabricated all-zero stat
     * (constitution I). */
    public fun aggregate(
        transmissions: List<TransmissionEntity>,
        correctionFields: List<String>,
    ): AnalyticsTier1Payload.QualityStats? {
        if (transmissions.isEmpty()) return null
        val total = transmissions.size.toDouble()

        val correctionRateByField = correctionFields.groupingBy { it }.eachCount()
            .mapValues { (_, count) -> count / total }

        val attributionStateMix = transmissions.groupingBy { it.attributionState.name }.eachCount()
            .mapValues { (_, count) -> count / total }

        val confidences = transmissions.mapNotNull { it.attributionConfidence }
        val confidenceMix = if (confidences.isEmpty()) {
            emptyMap()
        } else {
            val confidenceTotal = confidences.size.toDouble()
            mapOf(
                CONFIDENCE_HIGH to confidences.count { it >= HIGH_CONFIDENCE_THRESHOLD } / confidenceTotal,
                CONFIDENCE_MEDIUM to confidences.count {
                    it >= MEDIUM_CONFIDENCE_THRESHOLD && it < HIGH_CONFIDENCE_THRESHOLD
                } / confidenceTotal,
                CONFIDENCE_LOW to confidences.count { it < MEDIUM_CONFIDENCE_THRESHOLD } / confidenceTotal,
            )
        }

        val unresolvedCallsignRate = transmissions.count { it.attributionState == AttributionState.UNKNOWN } / total
        val vadFallbackRate = transmissions.count { !it.vadDetector.conformsToFrSeg1 } / total

        return AnalyticsTier1Payload.QualityStats(
            correctionRateByField = correctionRateByField,
            confidenceMix = confidenceMix,
            attributionStateMix = attributionStateMix,
            unresolvedCallsignRate = unresolvedCallsignRate,
            vadFallbackRate = vadFallbackRate,
        )
    }
}
