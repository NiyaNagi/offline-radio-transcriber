package org.ort.pipeline.alerts

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Build-plan P31 (FR-ALR-2, FR-ALR-4): where a matched watch actually becomes something the
 * operator sees. [canDeliver] lets a caller (this package's own coordinator, or `:app`'s Settings
 * screen) ask honestly whether anything will actually happen before pretending it did — POST_NOTIFICATIONS
 * may have been denied at setup (functional spec §7.19's own "handle that honestly").
 *
 * [dispatch] returns whether it actually posted — `false` means the platform's own permission
 * state made delivery impossible (constitution I: an attribution/outcome without its true state
 * is a bug). A caller MUST NOT treat a `false` as if the alert had fired.
 */
public interface AlertNotificationDispatcher {
    public fun canDeliver(): Boolean
    public fun dispatch(firing: AlertFiring): Boolean
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

    /**
     * Honours [canDeliver] (the FR-ALR-2 honesty check this class previously exposed but never
     * consulted at its own call site) *and* carries a second, literal
     * `ContextCompat.checkSelfPermission` guard immediately ahead of the `notify()` call.
     * `canDeliver()`/`NotificationManagerCompat.areNotificationsEnabled()` is correct at runtime
     * but is an opaque method call as far as lint's `MissingPermission` data-flow analysis is
     * concerned, so it cannot discharge `NotificationManagerCompat.notify`'s own
     * `@RequiresPermission(POST_NOTIFICATIONS)` obligation (confirmed against the compiled
     * `androidx.core:core:1.13.1` annotation directly — it carries no `conditional` element, so
     * lint treats it as a plain revocable-permission requirement, satisfied only by a manifest
     * declaration *and* a literal, traceable `checkSelfPermission`/`==PERMISSION_GRANTED` guard it
     * can follow itself — never by an indirection through a differently-named method, however
     * equivalent). The guard below is that literal check, not a second opinion offered for its own
     * sake — see this package's own `AndroidManifest.xml` for the matching `<uses-permission>`,
     * without which lint reports the permission missing outright rather than merely unguarded.
     *
     * Below API 33 (`Build.VERSION_CODES.TIRAMISU`) `POST_NOTIFICATIONS` is not a runtime
     * permission at all — there is nothing to request and nothing to deny — so the guard is
     * skipped there entirely rather than calling `checkSelfPermission` for a concept that does not
     * exist yet on that platform.
     */
    override fun dispatch(firing: AlertFiring): Boolean {
        if (!canDeliver()) return false
        val postNotificationsDenied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        if (postNotificationsDenied) return false
        ensureChannel()
        val content = AlertNotificationContentBuilder.build(firing)
        val notification = build(content)
        NotificationManagerCompat.from(context).notify(notificationIdFor(firing.watch.id), notification)
        return true
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
 * real `NotificationManager`. Records every *delivered* [dispatch] call verbatim, in order, so a
 * coalescing test can assert both how many firings were recorded and what each one's own
 * [AlertFiring.isRepeat]/[AlertFiring.occurrenceCount] carried. When [setDeliverable] has set
 * `false`, [dispatch] mirrors [AndroidAlertNotificationDispatcher]'s own honesty: it records
 * nothing and reports `false`, rather than pretending an alert fired that could not have. */
public class FakeAlertNotificationDispatcher(private var deliverable: Boolean = true) : AlertNotificationDispatcher {

    public val firings: MutableList<AlertFiring> = mutableListOf()

    override fun canDeliver(): Boolean = deliverable

    override fun dispatch(firing: AlertFiring): Boolean {
        if (!deliverable) return false
        firings += firing
        return true
    }

    public fun setDeliverable(value: Boolean) {
        deliverable = value
    }
}
