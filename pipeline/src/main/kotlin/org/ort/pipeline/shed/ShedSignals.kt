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
}

/** The behavioural fake every [org.ort.pipeline.shed.ShedControllerTest] runs against. */
public class FakeShedSignals(
    public var batteryPercent: Int = 100,
    public var charging: Boolean = true,
    public var backlog: Int = 0,
    public var freeStorageBytes: Long = Long.MAX_VALUE,
) : ShedSignals {
    override fun batteryPercent(): Int = batteryPercent
    override fun isCharging(): Boolean = charging
    override fun queueBacklog(): Int = backlog
    override fun freeStorageBytes(): Long = freeStorageBytes
}
