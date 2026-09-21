package org.ort.pipeline.shed

/**
 * The inputs the shed controller samples every 10 s (technical design §7.3). A real
 * implementation reads `BatteryManager`, free storage and the queue backlog; tests use
 * [FakeShedSignals].
 */
public interface ShedSignals {
    public fun batteryPercent(): Int
    public fun isCharging(): Boolean
    public fun queueBacklog(): Int
    public fun freeStorageBytes(): Long

    /**
     * R-1141 (FR-TIER-3): the operator's Settings tier override
     * ([org.ort.app.ui.settings.SettingsStore.tierOverrideName]), as an [org.ort.core.Tier]
     * ordinal — `0` ("Hold at tier 0") through `3`, or `null` for "let the phone choose" (no
     * override). This is a **ceiling on tier**, which [ShedController] turns into a **floor on
     * shed level**: it can only ever add shedding beyond what [batteryPercent]/[queueBacklog]
     * alone would choose, never remove shedding those signals demand (FR-TIER-4's automatic
     * degradation is mandatory and must not be overridable away). `null` must read exactly as
     * "no floor" — never as `0` (which would wrongly force the coolest, most-shed tier whenever
     * the operator has expressed no preference at all).
     */
    public fun tierCapOrdinal(): Int?
}

/** The behavioural fake every [org.ort.pipeline.shed.ShedControllerTest] runs against. */
public class FakeShedSignals(
    public var batteryPercent: Int = 100,
    public var charging: Boolean = true,
    public var backlog: Int = 0,
    public var freeStorageBytes: Long = Long.MAX_VALUE,
    public var tierCapOrdinal: Int? = null,
) : ShedSignals {
    override fun batteryPercent(): Int = batteryPercent
    override fun isCharging(): Boolean = charging
    override fun queueBacklog(): Int = backlog
    override fun freeStorageBytes(): Long = freeStorageBytes
    override fun tierCapOrdinal(): Int? = tierCapOrdinal
}
