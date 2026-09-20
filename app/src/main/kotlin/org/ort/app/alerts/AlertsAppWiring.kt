package org.ort.app.alerts

import android.content.Context
import org.ort.pipeline.alerts.AlertNotificationDispatcher
import org.ort.pipeline.alerts.AlertWatchStore
import org.ort.pipeline.alerts.AndroidAlertNotificationDispatcher
import org.ort.pipeline.alerts.FileBackedAlertWatchStore
import org.ort.pipeline.alerts.notificationsCanFire
import java.io.File

/**
 * Build-plan P31 — the composition root for `:app`'s side of the live-alerts feature, the same
 * role `org.ort.app.analytics.AnalyticsAppWiring` plays for the analytics channel: [configureOnce]
 * is called defensively, from inside [SettingsAlertsPolling] itself, never from `OrtApplication`
 * (outside this unit's own file-ownership map, and the channel it would otherwise register there
 * is created lazily inside [AndroidAlertNotificationDispatcher] instead — see that class's own
 * doc comment).
 *
 * [watchStore] points at [FileBackedAlertWatchStore.RELATIVE_PATH] under `context.filesDir` — the
 * exact path a real `:pipeline` production wiring (not yet connected; see this unit's own build
 * report) would need to construct its own instance against, so both sides read and write the one
 * real file, never two independently-scoped copies.
 */
public object AlertsAppWiring {

    public lateinit var watchStore: AlertWatchStore
    public lateinit var dispatcher: AlertNotificationDispatcher
    private var configured = false

    public fun configureOnce(context: Context) {
        if (configured) return
        configured = true
        watchStore = FileBackedAlertWatchStore(
            File(context.applicationContext.filesDir, FileBackedAlertWatchStore.RELATIVE_PATH),
        )
        dispatcher = AndroidAlertNotificationDispatcher(context.applicationContext)
    }

    /** FR-ALR-2's own honesty requirement, read fresh every time — a permission grant/denial can
     * change at any point from the system settings screen the operator may have just visited. */
    public fun notificationsPermissionGranted(context: Context): Boolean = notificationsCanFire(context)

    /** Test-only reset — the same pattern [org.ort.app.analytics.AnalyticsAppWiring.resetForTest]
     * already establishes for the identical "a `lateinit object` otherwise leaks state across
     * Robolectric tests that share one JVM" reason. */
    public fun resetForTest() {
        configured = false
    }
}
