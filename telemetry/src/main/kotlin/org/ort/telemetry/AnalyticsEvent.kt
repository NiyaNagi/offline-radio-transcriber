package org.ort.telemetry

import kotlinx.serialization.Serializable

/**
 * One analytics event: a [tier], the [provenance] envelope FR-ANL-8 requires on every event
 * (non-nullable — an event without provenance cannot be constructed), and a [payload] whose
 * runtime type is always one of [tier]'s own closed field-list variants (enforced by
 * [AnalyticsEventFactory], the only place this class is normally constructed).
 *
 * FR-ANL-6: nothing here is ever built by serialising an entity graph — `:telemetry` has no
 * compile-time dependency on `:data` at all (`ModuleGraph`), so a caller in `:app` or `:pipeline`
 * can only ever reach this class's constructor with plain values it already extracted itself,
 * never a Room entity reference. [AnalyticsEventCodec] recomputes the wire form from exactly
 * these fields every time, never from a cached serialization.
 */
@Serializable
public data class AnalyticsEvent(
    val tier: AnalyticsTier,
    val provenance: AnalyticsProvenance,
    val payload: AnalyticsPayload,
)

/**
 * The only supported way to build an [AnalyticsEvent] — pairs a payload with the [AnalyticsTier]
 * its own sealed supertype belongs to, so a tier/payload mismatch (a [AnalyticsTier2Payload]
 * tagged [AnalyticsTier.TIER_1]) cannot happen by construction.
 */
public object AnalyticsEventFactory {
    public fun tier1(provenance: AnalyticsProvenance, payload: AnalyticsTier1Payload): AnalyticsEvent =
        AnalyticsEvent(AnalyticsTier.TIER_1, provenance, payload)

    public fun tier2(provenance: AnalyticsProvenance, payload: AnalyticsTier2Payload): AnalyticsEvent =
        AnalyticsEvent(AnalyticsTier.TIER_2, provenance, payload)

    public fun tier3(provenance: AnalyticsProvenance, payload: AnalyticsTier3Payload): AnalyticsEvent =
        AnalyticsEvent(AnalyticsTier.TIER_3, provenance, payload)
}
