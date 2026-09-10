package org.ort.pipeline.digest

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import org.ort.core.Clock
import org.ort.core.SystemClock

/**
 * Tracks "the operator was last known to be looking at this app" without this module taking any
 * Activity/Compose lifecycle dependency of its own — the same process-wide-holder shape
 * [org.ort.pipeline.capture.CaptureState]/[org.ort.pipeline.capture.ShedStatus] already use.
 * Whichever screen-lifecycle owner exists (WPE/WPF) calls [markActive] from its own lifecycle
 * hook (e.g. a `DisposableEffect` on `LocalLifecycleOwner`, or `Activity.onResume`); this object
 * only remembers *when* that last happened.
 */
public object ForegroundActivityTracker {
    @Volatile
    public var lastActiveAtMillis: Long = SystemClock.wallMillis()
        private set

    public fun markActive(atMillis: Long = SystemClock.wallMillis()) {
        lastActiveAtMillis = atMillis
    }
}

/**
 * The real Android [ProseDigestDeviceSignals] (FR-DIG-5). Never `isIgnoringBatteryOptimizations()`
 * — constitution IV: that API lies on the reference device (ColorOS). Both facts are read
 * directly from the platform, or from real, self-recorded activity — never inferred from a hint.
 *
 * **`isCharging()`**: `BatteryManager.isCharging()` (API 23+; always available at this project's
 * minSdk 26). If the service is ever unreachable, the honest, conservative fallback is `false` —
 * never "assume charging" (constitution I: an unreadable signal reads as the state that blocks
 * the additive background task, not the state that lets it run).
 *
 * **`isDeviceIdle()` — the rule, stated once, here**: true when *either*
 * `PowerManager.isDeviceIdleMode()` (a genuine OS Doze maintenance window) *or* the app has
 * recorded no foreground activity ([ForegroundActivityTracker]) for at least [idleAfterMillis]
 * (default 5 minutes). The OS signal alone is not enough: Doze windows are rare and OEM-delayed
 * on exactly the reference device this project targets (ColorOS — constitution IV's own case
 * study), so relying on it exclusively would mean the prose digest almost never runs there even
 * when the operator has genuinely stepped away. The app-level clock is the looser,
 * reliable-on-this-hardware half of the "or" — `WorkManager`'s own `setRequiresDeviceIdle`
 * constraint (see [ProseDigestRunner]) is what actually decides *whether the process gets woken
 * at all*; this decides whether the *app's own, possibly stricter* idle definition holds once
 * it has been.
 */
public class AndroidProseDigestDeviceSignals(
    private val context: Context,
    private val clock: Clock = SystemClock,
    private val idleAfterMillis: Long = DEFAULT_IDLE_AFTER_MILLIS,
) : ProseDigestDeviceSignals {

    override fun isCharging(): Boolean {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return false
        return try {
            batteryManager.isCharging
        } catch (e: Exception) {
            false
        }
    }

    override fun isDeviceIdle(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val osIdle = try {
            powerManager?.isDeviceIdleMode == true
        } catch (e: Exception) {
            false
        }
        if (osIdle) return true
        return clock.wallMillis() - ForegroundActivityTracker.lastActiveAtMillis >= idleAfterMillis
    }

    public companion object {
        public const val DEFAULT_IDLE_AFTER_MILLIS: Long = 5 * 60 * 1000L
    }
}
