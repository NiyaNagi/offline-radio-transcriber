package org.ort.pipeline.digest

import org.ort.core.Tier
import org.ort.pipeline.capture.CaptureState

/**
 * The two device facts [ProseDigestGate] needs beyond [CaptureState] and the tier (FR-DIG-5):
 * idle and charging. Kept to exactly these two rather than reused from
 * [org.ort.pipeline.shed.ShedSignals] (which bundles battery/backlog/storage sampling this gate
 * has no reason to couple to, and has no notion of "idle" at all) — a real implementation reads
 * `UsageStatsManager`/screen state for idle and `BatteryManager` for charging; production wiring
 * is for the package that owns the settings/status screens (WPE/WPF) to supply.
 */
public interface ProseDigestDeviceSignals {
    public fun isDeviceIdle(): Boolean
    public fun isCharging(): Boolean
}

/** The behavioural fake every [ProseDigestGate] test runs against. */
public class FakeProseDigestDeviceSignals(public var idle: Boolean = true, public var charging: Boolean = true) :
    ProseDigestDeviceSignals {
    override fun isDeviceIdle(): Boolean = idle
    override fun isCharging(): Boolean = charging
}

/** Every conjunct [ProseDigestGate.evaluate] can fail on — a decision names all of them, not just the first. */
public enum class ProseDigestBlockReason { NOT_IDLE, NOT_CHARGING, CAPTURING, TIER_BELOW_T3, DISABLED }

public sealed interface ProseDigestGateDecision {
    public data object Run : ProseDigestGateDecision
    public data class Blocked(public val reasons: Set<ProseDigestBlockReason>) : ProseDigestGateDecision
}

/**
 * FR-DIG-5, AC-87, AC-138: prose generation may run only when the device is idle **and**
 * charging **and** capture is not running **and** the tier is T3 **and** the feature is enabled.
 * Every conjunct is independently necessary — constitution IV ("never in the capture path") is
 * [ProseDigestBlockReason.CAPTURING] alone; constitution I ("a weaker device may know less; it
 * must not be more wrong") is [ProseDigestBlockReason.TIER_BELOW_T3] alone. [isCapturing]
 * defaults to the real, live [CaptureState.isCapturing] so production callers need not thread it
 * through by hand; every test in `ProseDigestGateTest` passes it explicitly to falsify one
 * conjunct at a time without touching global state.
 */
public object ProseDigestGate {
    public fun evaluate(
        signals: ProseDigestDeviceSignals,
        tier: Tier,
        enabled: Boolean,
        isCapturing: Boolean = CaptureState.isCapturing,
    ): ProseDigestGateDecision {
        val reasons = buildSet {
            if (!signals.isDeviceIdle()) add(ProseDigestBlockReason.NOT_IDLE)
            if (!signals.isCharging()) add(ProseDigestBlockReason.NOT_CHARGING)
            if (isCapturing) add(ProseDigestBlockReason.CAPTURING)
            if (tier != Tier.T3) add(ProseDigestBlockReason.TIER_BELOW_T3)
            if (!enabled) add(ProseDigestBlockReason.DISABLED)
        }
        return if (reasons.isEmpty()) ProseDigestGateDecision.Run else ProseDigestGateDecision.Blocked(reasons)
    }
}
