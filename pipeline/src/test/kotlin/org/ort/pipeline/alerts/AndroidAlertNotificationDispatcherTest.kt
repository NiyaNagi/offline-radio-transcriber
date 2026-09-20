package org.ort.pipeline.alerts

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.AttributionState
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Build-plan P31: the real, `NotificationManager`-backed dispatcher — channel creation, and the
 * `setOnlyAlertOnce` mechanism the coalescing rule relies on at the OS layer (see
 * [AndroidAlertNotificationDispatcher]'s own doc comment).
 */
@RunWith(RobolectricTestRunner::class)
class AndroidAlertNotificationDispatcherTest {

    private fun firing(id: String = "w1", count: Int = 1, repeat: Boolean = false) = AlertFiring(
        watch = AlertWatch.Callsign(id = id, callsign = "K7ABC"),
        input = AlertMatchInput("TX1", AttributionState.CONFIRMED, "K7ABC", null, null),
        occurrenceCount = count,
        isRepeat = repeat,
    )

    @Test
    fun `dispatch creates the channel lazily and posts a notification carrying onlyAlertOnce`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dispatcher = AndroidAlertNotificationDispatcher(context)
        val nm = context.getSystemService(NotificationManager::class.java)

        dispatcher.dispatch(firing())

        val channel = nm.getNotificationChannel(AndroidAlertNotificationDispatcher.CHANNEL_ID)
        assertEquals("Live alerts", channel.name)
        val posted = shadowOf(nm).allNotifications.single()
        assertTrue(
            "expected FLAG_ONLY_ALERT_ONCE so repeated matches for one watch don't re-alert",
            (posted.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0,
        )
    }

    @Test
    fun `repeated firings for the same watch reuse one notification id, never stacking`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dispatcher = AndroidAlertNotificationDispatcher(context)
        val nm = context.getSystemService(NotificationManager::class.java)

        repeat(50) { index -> dispatcher.dispatch(firing(count = index + 1, repeat = index > 0)) }

        assertEquals(
            "fifty matches for one watch must post to one notification id, not fifty",
            1,
            shadowOf(nm).allNotifications.size,
        )
    }

    @Test
    fun `two different watches get two independent notifications`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dispatcher = AndroidAlertNotificationDispatcher(context)
        val nm = context.getSystemService(NotificationManager::class.java)

        dispatcher.dispatch(firing(id = "w1"))
        dispatcher.dispatch(firing(id = "w2"))

        assertEquals(2, shadowOf(nm).allNotifications.size)
    }

    @Test
    fun `canDeliver reflects the platform's own notifications-enabled state`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dispatcher = AndroidAlertNotificationDispatcher(context)

        // Robolectric's default shadow reports notifications enabled -- this asserts the real
        // dispatcher delegates to the platform check rather than hard-coding `true`.
        assertEquals(notificationsCanFire(context), dispatcher.canDeliver())
    }

    /**
     * FR-ALR-2, AC-195's own honesty requirement: a caller that never checked `canDeliver()` MUST
     * NOT get a false "it fired" -- and lint MUST NOT be silenced by a suppression, only by a real
     * guard. Pinned to API 33 (`TIRAMISU`), the level `POST_NOTIFICATIONS` became a runtime
     * permission at (the same reasoning `AndroidBluetoothLinkPermissionTest` pins to 31 for
     * `BLUETOOTH_CONNECT`) -- this is the branch [AndroidAlertNotificationDispatcher.dispatch]'s
     * own doc comment says the explicit `ContextCompat.checkSelfPermission` guard exists for.
     */
    @Test
    @Config(sdk = [33])
    fun `FR_ALR_2 dispatch reports not delivered and posts nothing when POST_NOTIFICATIONS is denied`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val dispatcher = AndroidAlertNotificationDispatcher(context)
        val nm = context.getSystemService(NotificationManager::class.java)

        val delivered = dispatcher.dispatch(firing())

        assertFalse("a denied POST_NOTIFICATIONS must never be reported as delivered", delivered)
        assertTrue(
            "a denied POST_NOTIFICATIONS must never actually post a notification",
            shadowOf(nm).allNotifications.isEmpty(),
        )
    }

    /** The discriminating counterpart to the denial test above: the identical call, with the
     * platform permission granted, must both report delivery and actually post. */
    @Test
    @Config(sdk = [33])
    fun `FR_ALR_2 dispatch delivers and posts when POST_NOTIFICATIONS is granted`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val dispatcher = AndroidAlertNotificationDispatcher(context)
        val nm = context.getSystemService(NotificationManager::class.java)

        val delivered = dispatcher.dispatch(firing())

        assertTrue("a granted POST_NOTIFICATIONS must be reported as delivered", delivered)
        assertEquals(1, shadowOf(nm).allNotifications.size)
    }
}
