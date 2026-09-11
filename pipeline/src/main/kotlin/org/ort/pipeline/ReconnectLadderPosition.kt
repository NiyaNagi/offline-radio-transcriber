package org.ort.pipeline

/**
 * F23/F9's ladder-position fields — the retry attempt, the ladder's own length, and the delay
 * before the next attempt — computed from the **real** backoff ladder a transport/capture path
 * already runs (`org.ort.capture.android.BackoffLadder`, `org.ort.rig.usb.UsbReconnectBackoff`,
 * `org.ort.rig.bluetooth.BluetoothReconnectBackoff`), never a second, independently-invented
 * schedule (constitution I: a number the operator reads as authoritative must not be guessed).
 *
 * [attempt] is 1-based, matching every ladder's own `delayMillisFor` convention ("first retry is
 * attempt 1"). [ofTotal] is the ladder's step count before it holds at its last step. [nextRetryInMillis]
 * is that step's own configured delay — a static fact from the ladder, not a live countdown (this
 * function is called once per real state observation, not on a recurring display timer, so it
 * never drifts from — or duplicates — the actual retry scheduling those modules already own).
 */
public data class ReconnectLadderPosition(
    public val attempt: Int,
    public val ofTotal: Int,
    public val nextRetryInMillis: Long,
)

/**
 * Derives [ReconnectLadderPosition] from [elapsedMillis] (real wall time since the loss began)
 * against [delayMillisFor] (the real ladder function this path already retries on) and [ofTotal]
 * (that ladder's own step count — the three ladders above are identical: 1s, 2s, 5s, 10s, 30s,
 * five steps, then holding at 30s).
 *
 * A pure, idempotent function of elapsed time: calling this from every real state observation the
 * underlying transport happens to produce (which, for a dual-band rig's per-band stale events,
 * can be more than one observation per actual retry cycle — see [org.ort.pipeline.rig.RigSupervisor]'s
 * own report) always yields the same, correct answer for the same elapsed time, so it cannot
 * double-count a retry the way an incrementing counter driven by "how many events arrived" would.
 */
public fun reconnectLadderPositionAt(
    elapsedMillis: Long,
    ofTotal: Int,
    delayMillisFor: (Int) -> Long,
): ReconnectLadderPosition {
    var cumulativeMillis = 0L
    var attempt = 1
    while (true) {
        val stepMillis = delayMillisFor(attempt)
        val stepEndsAtMillis = cumulativeMillis + stepMillis
        if (elapsedMillis < stepEndsAtMillis || attempt >= ofTotal) {
            val nextRetryInMillis = (stepEndsAtMillis - elapsedMillis).coerceAtLeast(0L)
            return ReconnectLadderPosition(attempt, ofTotal, nextRetryInMillis)
        }
        cumulativeMillis = stepEndsAtMillis
        attempt++
    }
}
