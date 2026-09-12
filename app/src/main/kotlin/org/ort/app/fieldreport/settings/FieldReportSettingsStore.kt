package org.ort.app.fieldreport.settings

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * FR-OBS-10's "visible Settings switch", persisted. Follows
 * [org.ort.app.ui.settings.SettingsStore]'s own shape (interface + `SharedPreferences` impl + an
 * in-memory fake) — kept in this package rather than that one because `ui/settings/SettingsStore.kt`
 * is outside this round's own file-ownership map; this is small, additive, dependency-free
 * infrastructure this package owns outright instead.
 *
 * [publicDestinationGuardEnabled] defaults `true` — the *safe* position (per this round's own
 * brief: "Default it to the safe position"). `true` means the guard is active: FR-OBS-10's gated
 * categories are refused against a public destination. The operator must explicitly turn this
 * switch off (`false`) to allow them.
 */
public interface FieldReportSettingsStore {
    public var publicDestinationGuardEnabled: Boolean
}

/** The real, `SharedPreferences`-backed [FieldReportSettingsStore]. */
public class SharedPreferencesFieldReportSettingsStore(private val prefs: SharedPreferences) :
    FieldReportSettingsStore {

    override var publicDestinationGuardEnabled: Boolean
        get() = prefs.getBoolean(KEY_PUBLIC_DESTINATION_GUARD_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_PUBLIC_DESTINATION_GUARD_ENABLED, value) }

    public companion object {
        public const val PREFS_NAME: String = "org.ort.app.fieldreport.settings"
        public const val KEY_PUBLIC_DESTINATION_GUARD_ENABLED: String = "public_destination_guard_enabled"
    }
}

/** Constitution II's behavioural fake — a plain in-memory [FieldReportSettingsStore] for tests,
 * defaulted to the identical safe position the real store uses. */
public class InMemoryFieldReportSettingsStore(override var publicDestinationGuardEnabled: Boolean = true) :
    FieldReportSettingsStore
