package org.ort.telemetry

/**
 * The one place a caller submits an [AnalyticsEvent] — gates by [AnalyticsTierPreferences] before
 * ever touching the [AnalyticsEventQueue] (FR-ANL-1: a disabled tier's events are never queued at
 * all, not queued-then-filtered-at-upload) and purges a tier's already-queued events the instant
 * it is turned off (FR-ANL-9: "takes effect immediately for events not yet sent").
 */
public class AnalyticsController(
    private val queue: AnalyticsEventQueue,
    private val preferences: AnalyticsTierPreferences,
) {

    /** `null` when [event]'s tier is disabled — the event is dropped before it ever reaches the
     * queue, never queued-then-discarded. Otherwise, the [EnqueueOutcome] the queue reports. */
    public fun submit(event: AnalyticsEvent): EnqueueOutcome? {
        if (!preferences.isEnabled(event.tier)) return null
        return queue.enqueue(event)
    }

    public fun isTierEnabled(tier: AnalyticsTier): Boolean = preferences.isEnabled(tier)

    /** FR-ANL-9: turning a tier off purges every event of that tier already queued, so a later
     * drain can never surface one the operator just disabled. Turning a tier back on has no
     * retroactive effect — only what is submitted from this point on is gated by the new state. */
    public fun setTierEnabled(tier: AnalyticsTier, enabled: Boolean) {
        preferences.setEnabled(tier, enabled)
        if (!enabled) queue.removeAll { it.tier == tier }
    }
}
