package org.ort.app.analytics

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * R-1123: persists the newest [ProcessExitReasonReporter.ExitReasonSample.timestampMillis] this
 * app has already turned into a tier-1 event. `ActivityManager
 * .getHistoricalProcessExitReasons`'s own rolling history survives across restarts (and reboots),
 * so without this watermark the same historical native crash would be resubmitted on every launch
 * forever — the same "only what is new" discipline [org.ort.telemetry.InstallIdStore] and every
 * other persisted marker in this codebase already follow.
 */
public interface ExitReasonWatermarkStore {
    public fun lastReportedMillis(): Long
    public fun recordReported(millis: Long)
}

/** The real, `SharedPreferences`-backed store. `0L` (the default) means "nothing has ever been
 * reported" — every real exit-reason timestamp Android hands back is a wall-clock millisecond
 * value from well after the epoch, so this can never collide with a genuine sample. */
public class SharedPreferencesExitReasonWatermarkStore(private val prefs: SharedPreferences) :
    ExitReasonWatermarkStore {

    override fun lastReportedMillis(): Long = prefs.getLong(KEY_LAST_REPORTED_MILLIS, 0L)

    override fun recordReported(millis: Long) {
        prefs.edit { putLong(KEY_LAST_REPORTED_MILLIS, millis) }
    }

    public companion object {
        public const val PREFS_NAME: String = "org.ort.app.analytics.exit_reason_watermark"
        private const val KEY_LAST_REPORTED_MILLIS = "last_reported_millis"
    }
}
