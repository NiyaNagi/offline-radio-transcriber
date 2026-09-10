package org.ort.rig.bluetooth

/**
 * The reconnect ladder for a Bluetooth rig transport (FR-RIG-15: a Bluetooth drop degrades
 * exactly as a USB drop does): 1s, 2s, 5s, 10s, 30s, then holding at 30s. Same *policy* as
 * `capture-android`'s `BackoffLadder` (technical design §5.5 → AC-48) and `:rig-usb`'s
 * `UsbReconnectBackoff` — copied deliberately rather than depended on: `:rig-bluetooth` may only
 * depend on `:core` and `:rig` (ModuleGraph), never `:capture-android` or `:rig-usb` (main
 * source set — the test-only dependency on `:rig-usb` is a separate, narrower thing; see this
 * module's `build.gradle.kts`).
 */
public object BluetoothReconnectBackoff {
    private val stepsMillis = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)

    /** Delay before retry attempt [attempt] (1-based, first retry is attempt 1). */
    public fun delayMillisFor(attempt: Int): Long {
        require(attempt >= 1) { "attempt must be >= 1, was $attempt" }
        val idx = (attempt - 1).coerceAtMost(stepsMillis.lastIndex)
        return stepsMillis[idx]
    }
}
