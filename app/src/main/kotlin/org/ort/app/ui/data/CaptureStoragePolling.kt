package org.ort.app.ui.data

import android.content.Context
import org.ort.app.ui.settings.SharedPreferencesSettingsStore
import org.ort.pipeline.capture.ArchiveWriteRateForecast
import org.ort.pipeline.capture.measureStorageAccounting
import org.ort.pipeline.capture.overAudioBudgetState
import java.io.File

/**
 * `Capture.dc.html` (N08): the real read path behind [CaptureStorageMapper] — every fact is either
 * `:pipeline`'s own storage accounting/budget functions or the shared `SharedPreferencesSettingsStore`
 * archive settings, never a value this package invents. Follows
 * [org.ort.app.ui.recordings.RecordingsPolling]'s own idiom exactly (confirmed by reading that file
 * before writing this) — `measureStorageAccounting`/`getDatabasePath`/`overAudioBudgetState`, so
 * Capture and Recordings can never disagree about how the same real bytes are measured, and
 * [setArchiveEnabled] writes through the identical shared preferences file `:pipeline`'s own
 * `ArchiveSettingsStore` reads (see that interface's kdoc), so `RealCaptureService` sees a change
 * made here on its own very next tick — no `:pipeline` call needed from this package either.
 */
public object CaptureStoragePolling {

    public suspend fun current(context: Context): CaptureStorageViewState {
        val appContext = context.applicationContext
        val dbFile = appContext.getDatabasePath(org.ort.data.OrtDatabase.DATABASE_NAME)
        val databaseFiles = listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm"))
        val accounting = measureStorageAccounting(appContext.filesDir, databaseFiles)

        val prefs = appContext.getSharedPreferences(SharedPreferencesSettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
        val settings = SharedPreferencesSettingsStore(prefs)
        val overAudioBudget = overAudioBudgetState(accounting.audioBytes, settings.audioBudgetGb)

        return CaptureStorageMapper.from(
            overAudioBudget = overAudioBudget,
            archiveEnabled = settings.archiveEnabled,
            archiveBudgetGb = settings.archiveBudgetGb,
            archiveUsedBytes = accounting.archiveBytes,
            archiveRateState = ArchiveWriteRateForecast.state,
        )
    }

    /** The Storage row's own `Turn off`/`Turn on` control (D39/FR-STO-3f, AC-158/159) — see class
     * kdoc for why this writes straight through the shared preferences file. */
    public suspend fun setArchiveEnabled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(SharedPreferencesSettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
        SharedPreferencesSettingsStore(prefs).archiveEnabled = enabled
    }
}
