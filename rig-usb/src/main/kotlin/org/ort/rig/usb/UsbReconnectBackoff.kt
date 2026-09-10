package org.ort.rig.usb

/**
 * The reconnect ladder for a USB rig transport (FR-RIG-7): 1s, 2s, 5s, 10s, 30s, then holding at
 * 30s. This is the same *policy* as `capture-android`'s `BackoffLadder` (technical design §5.5 →
 * AC-48) — copied deliberately rather than depended on: `:rig-usb` may only depend on `:core` and
 * `:rig` (ModuleGraph), never `:capture-android`.
 */
public object UsbReconnectBackoff {
    private val stepsMillis = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)

    /** Delay before retry attempt [attempt] (1-based, first retry is attempt 1). */
    public fun delayMillisFor(attempt: Int): Long {
        require(attempt >= 1) { "attempt must be >= 1, was $attempt" }
        val idx = (attempt - 1).coerceAtMost(stepsMillis.lastIndex)
        return stepsMillis[idx]
    }
}
