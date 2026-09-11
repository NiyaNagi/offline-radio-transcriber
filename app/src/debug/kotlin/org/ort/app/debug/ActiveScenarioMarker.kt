package org.ort.app.debug

import android.content.Context

/**
 * R-873 (register, validator V10, `results/ui-audit/README.md`'s own R-853 section): the one small
 * piece of persisted state [ActiveScenarioRepublishProvider] needs to know which scenario, if any,
 * to re-load at the next debug process start. A plain `SharedPreferences` file, not a `SetupStore`/
 * `CaptureConfigurationStore` field — this marker is orthogonal to every real store a scenario itself
 * seeds, and must survive exactly the same events those do (a real relaunch) and be wiped by exactly
 * the same event that wipes them (`adb shell pm clear`, `install.ps1 -Clear`'s own mechanism, which
 * deletes every `SharedPreferences` file this app owns, this one included).
 */
internal object ActiveScenarioMarker {
    private const val PREFS_NAME = "org.ort.app.debug.active_scenario"
    private const val KEY_NAME = "name"

    /** Called once, at the end of a successful [Scenarios.load] — records which scenario is now
     * active so a later debug process start ([ActiveScenarioRepublishProvider]) can re-publish its
     * process-wide holders. */
    fun write(context: Context, name: String) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NAME, name)
            .apply()
    }

    /** `null` on a fresh install, or after `pm clear` — [ActiveScenarioRepublishProvider] does
     * nothing in either case. */
    fun read(context: Context): String? =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_NAME, null)
}
