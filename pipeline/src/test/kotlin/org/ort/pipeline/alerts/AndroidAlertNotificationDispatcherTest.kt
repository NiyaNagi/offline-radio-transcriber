package org.ort.pipeline.alerts

import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.AttributionState
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

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
}
