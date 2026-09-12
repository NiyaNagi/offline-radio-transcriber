package org.ort.pipeline.capture

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * WPARC (FR-SEG-9, FR-STO-3d, D39): the continuous archive's own settings, independent of the
 * over-audio budget (`SettingsStore.audioBudgetGb`, `:app`). `:pipeline` cannot depend on `:app`
 * (module graph, constitution VII) — the same reason
 * [org.ort.pipeline.rig.CaptureConfigurationStore] exists as its own type here rather than
 * importing `:app`'s `SettingsStore` — so this is the `:pipeline`-side half of a **shared,
 * file-backed contract**: [SharedPreferencesArchiveSettingsStore] and `:app`'s
 * `SharedPreferencesSettingsStore` open the *same* preferences file
 * ([org.ort.app.ui.settings.SharedPreferencesSettingsStore.PREFS_NAME]) under the *same* key
 * names, so a value either side writes is immediately visible to the other — exactly the pattern
 * [org.ort.pipeline.capture.RealCaptureService.Dependencies.captureConfigurationStore]'s own kdoc
 * documents for `CaptureConfigurationStore`. The UI package (WP10 and successors) reads/writes
 * through `:app`'s own `SettingsStore` type; `RealCaptureService` reads through this one.
 *
 * **Defaults match D39 exactly**: [archiveEnabled] defaults `true` (the product owner's reversal:
 * "the continuous archive defaults ON"), [archiveBudgetGb] defaults `60` (D39's stated budget) —
 * unlike [org.ort.app.ui.settings.SettingsStore.audioBudgetGb], which defaults to "no budget set"
 * (`null`), the archive budget is never in that third state: D39 always has a real, positive
 * default.
 */
public interface ArchiveSettingsStore {
    public var archiveEnabled: Boolean
    public var archiveBudgetGb: Int
}

/** The real, `SharedPreferences`-backed [ArchiveSettingsStore] — see the interface's own kdoc for
 * why this opens the same file `:app`'s `SharedPreferencesSettingsStore` does. */
public class SharedPreferencesArchiveSettingsStore(private val prefs: SharedPreferences) : ArchiveSettingsStore {

    override var archiveEnabled: Boolean
        get() = prefs.getBoolean(KEY_ARCHIVE_ENABLED, DEFAULT_ARCHIVE_ENABLED)
        set(value) = prefs.edit { putBoolean(KEY_ARCHIVE_ENABLED, value) }

    override var archiveBudgetGb: Int
        get() = if (prefs.contains(KEY_ARCHIVE_BUDGET_GB)) {
            prefs.getInt(KEY_ARCHIVE_BUDGET_GB, DEFAULT_ARCHIVE_BUDGET_GB)
        } else {
            DEFAULT_ARCHIVE_BUDGET_GB
        }
        set(value) = prefs.edit { putInt(KEY_ARCHIVE_BUDGET_GB, value) }

    public companion object {
        /** [org.ort.app.ui.settings.SharedPreferencesSettingsStore.PREFS_NAME], duplicated rather
         * than imported — see the interface's own kdoc for why the filesystem is the contract. */
        public const val PREFS_NAME: String = "org.ort.app.settings"
        public const val KEY_ARCHIVE_ENABLED: String = "archive_enabled"
        public const val KEY_ARCHIVE_BUDGET_GB: String = "archive_budget_gb"

        /** D39: "the continuous archive (FR-SEG-9) defaults ON". */
        public const val DEFAULT_ARCHIVE_ENABLED: Boolean = true

        /** D39: "budgeted at 60 GB". */
        public const val DEFAULT_ARCHIVE_BUDGET_GB: Int = 60
    }
}

/** The behavioural fake (constitution II) — a plain in-memory [ArchiveSettingsStore], matching
 * [org.ort.app.ui.settings.InMemorySettingsStore]'s shape. */
public class InMemoryArchiveSettingsStore(
    override var archiveEnabled: Boolean = SharedPreferencesArchiveSettingsStore.DEFAULT_ARCHIVE_ENABLED,
    override var archiveBudgetGb: Int = SharedPreferencesArchiveSettingsStore.DEFAULT_ARCHIVE_BUDGET_GB,
) : ArchiveSettingsStore
