package org.ort.pipeline.digest

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.testing.TestClock
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowBatteryManager
import org.robolectric.shadows.ShadowPowerManager

/**
 * E2-I0x follow-up (FR-DIG-5): the real Android [ProseDigestDeviceSignals], proven against
 * Robolectric's shadows rather than assumed to match [FakeProseDigestDeviceSignals]'s contract —
 * a real `BatteryManager`/`PowerManager` read, plus the app-level idle-after-N-minutes fallback
 * [AndroidProseDigestDeviceSignals] states as its own rule.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidProseDigestDeviceSignalsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun powerManagerShadow(): ShadowPowerManager {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return Shadows.shadowOf(pm)
    }

    @After
    fun resetForegroundTracker() {
        ForegroundActivityTracker.markActive()
        powerManagerShadow().setIsDeviceIdleMode(false)
    }

    @Test
    fun `isCharging reflects a real BatteryManager charging state`() {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val shadow: ShadowBatteryManager = Shadows.shadowOf(bm)
        shadow.setIsCharging(true)

        val signals = AndroidProseDigestDeviceSignals(context)

        assertTrue(signals.isCharging())
    }

    @Test
    fun `isCharging is false when BatteryManager reports not charging`() {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        Shadows.shadowOf(bm).setIsCharging(false)

        val signals = AndroidProseDigestDeviceSignals(context)

        assertFalse(signals.isCharging())
    }

    @Test
    fun `isDeviceIdle is true when the OS reports a real Doze idle mode`() {
        powerManagerShadow().setIsDeviceIdleMode(true)
        // Foreground activity "just now" -- the OS signal alone must still win.
        ForegroundActivityTracker.markActive(atMillis = 0L)
        val clock = TestClock(startWallMillis = 0L)

        val signals = AndroidProseDigestDeviceSignals(context, clock = clock)

        assertTrue(signals.isDeviceIdle())
    }

    @Test
    fun `isDeviceIdle is false when neither the OS is idle nor the app has been backgrounded long enough`() {
        powerManagerShadow().setIsDeviceIdleMode(false)
        ForegroundActivityTracker.markActive(atMillis = 0L)
        val clock = TestClock(startWallMillis = AndroidProseDigestDeviceSignals.DEFAULT_IDLE_AFTER_MILLIS - 1)

        val signals = AndroidProseDigestDeviceSignals(context, clock = clock)

        assertFalse(signals.isDeviceIdle())
    }

    @Test
    fun `isDeviceIdle is true once no foreground activity has been recorded for the idle window, even without Doze`() {
        powerManagerShadow().setIsDeviceIdleMode(false)
        ForegroundActivityTracker.markActive(atMillis = 0L)
        val clock = TestClock(startWallMillis = AndroidProseDigestDeviceSignals.DEFAULT_IDLE_AFTER_MILLIS)

        val signals = AndroidProseDigestDeviceSignals(context, clock = clock)

        assertTrue(signals.isDeviceIdle())
    }

    @Test
    fun `a custom idleAfterMillis is honoured`() {
        powerManagerShadow().setIsDeviceIdleMode(false)
        ForegroundActivityTracker.markActive(atMillis = 0L)
        val clock = TestClock(startWallMillis = 60_000L)

        val signals = AndroidProseDigestDeviceSignals(context, clock = clock, idleAfterMillis = 60_000L)

        assertTrue(signals.isDeviceIdle())
    }
}
