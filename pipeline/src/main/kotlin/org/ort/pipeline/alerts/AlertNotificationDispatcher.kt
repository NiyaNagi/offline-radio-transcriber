package org.ort.pipeline.alerts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Build-plan P31 (FR-ALR-2, FR-ALR-4): where a matched watch actually becomes something the
 * operator sees. [canDeliver] lets a caller (this package's own coordinator, or `:app`'s Settings
 * screen) ask honestly whether anything will actually happen before pretending it did — POST_NOTIFICATIONS
 * may have been denied at setup (functional spec §7.19's own "handle that honestly").
 */
public interface AlertNotificationDispatcher {
    public fun canDeliver(): Boolean
    public fun dispatch(firing: AlertFiring)
}

/**
 * The real, `NotificationManager`-backed dispatcher. The channel is created lazily, on first
 * [dispatch] — never from `OrtApplication`, per this unit's own file-ownership map — the same
 * lazy-`ensureChannel()` pattern `org.ort.capture.android.service.CaptureService` already uses for
 * its own channel.
 *
 * **Coalescing (FR-ALR-6's neighbour concern, this unit's own decision — see the build report for
 * the full rule):** every firing for the same [AlertWatch.id] reuses the identical notification id
 * ([notificationIdFor]), and [NotificationCompat.Builder.setOnlyAlertOnce] is set unconditionally.
 * Android's own documented behaviour for that flag is exactly the rule this unit needs: the first
 * post for a given id alerts (sound/heads-up) once; every subsequent post that reuses the same id
 * *while the notification is still showing* updates its content silently, with no repeat alert,
 * until the operator dismisses it. A watch matching fifty times overnight therefore produces one
 * audible alert and one notification whose own text keeps counting, never fifty separate pings.
 */
public class AndroidAlertNotificationDispatcher(private val context: Context) : AlertNotificationDispatcher {

    override fun canDeliver(): Boolean = notificationsCanFire(context)

    override fun dispatch(firing: AlertFiring) {
        ensureChannel()
        val content = AlertNotificationContentBuilder.build(firing)
        val notification = build(content)
        NotificationManagerCompat.from(context).notify(notificationIdFor(firing.watch.id), notification)
    }

    private fun build(content: AlertNotificationContent): Notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setContentTitle(content.title)
        .setContentText(content.text)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setOnlyAlertOnce(true)
        .setAutoCancel(true)
        .build()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Live alerts", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    /** A stable, non-negative int from [AlertWatch.id]'s own hash — collisions are harmless here
     * (two watches sharing a notification id would simply coalesce together, never crash), and no
     * watch id this store mints is ever reused (see [AlertWatchStore]'s own doc comment). */
    private fun notificationIdFor(watchId: String): Int = watchId.hashCode() and 0x7fffffff

    public companion object {
        public const val CHANNEL_ID: String = "org.ort.alerts"
    }
}

/** FR-ALR-2's own honesty requirement, and the fact `:app`'s Settings screen reads directly to
 * show "alerts cannot fire" (functional spec §7.19) rather than staying silent about a denied
 * permission. `NotificationManagerCompat.areNotificationsEnabled()` already folds in both the
 * per-app POST_NOTIFICATIONS runtime state (API 33+) and an app-level notification block on older
 * platforms, so this is the one real check, never re-derived a second way. */
public fun notificationsCanFire(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

/** The behavioural fake (constitution II) — every coordinator/content test uses this rather than a
 * real `NotificationManager`. Records every [dispatch] call verbatim, in order, so a coalescing
 * test can assert both how many firings were recorded and what each one's own [AlertFiring.isRepeat]/
 * [AlertFiring.occurrenceCount] carried. */
public class FakeAlertNotificationDispatcher(private var deliverable: Boolean = true) : AlertNotificationDispatcher {

    public val firings: MutableList<AlertFiring> = mutableListOf()

    override fun canDeliver(): Boolean = deliverable

    override fun dispatch(firing: AlertFiring) {
        firings += firing
    }

    public fun setDeliverable(value: Boolean) {
        deliverable = value
    }
}
