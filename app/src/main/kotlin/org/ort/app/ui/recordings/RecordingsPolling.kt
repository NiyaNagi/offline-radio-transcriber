package org.ort.app.ui.recordings

import android.content.Context
import org.ort.app.ui.settings.SharedPreferencesSettingsStore
import org.ort.core.SystemClock
import org.ort.data.OrtDatabase
import org.ort.pipeline.archive.recordingSessionSummaries
import org.ort.pipeline.capture.ArchiveWriteRateForecast
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.measureStorageAccounting
import org.ort.pipeline.capture.overAudioBudgetState
import java.io.File

/**
 * `Recordings.dc.html` (RC01): the real read path — every fact is either
 * `org.ort.pipeline.archive.recordingSessionSummaries`, `:pipeline`'s own storage accounting/budget
 * functions, or the shared `SharedPreferencesSettingsStore` archive settings — never a value this
 * package invents. Follows `SettingsPolling.storage`'s own idiom for
 * `measureStorageAccounting`/`getDatabasePath` exactly (confirmed by reading that function before
 * writing this), so the two screens can never disagree about how the same real bytes are measured.
 */
public object RecordingsPolling {

    /** RC01's own "Turn off"/"Turn on" control (D39/FR-STO-3f) — writes straight through the same
     * shared preferences file `:pipeline`'s own `ArchiveSettingsStore` reads (see that interface's
     * kdoc), so `RealCaptureService` sees the change on its own very next read; no separate
     * `:pipeline`-side call is needed. */
    public suspend fun setArchiveEnabled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(SharedPreferencesSettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
        SharedPreferencesSettingsStore(prefs).archiveEnabled = enabled
    }

    public suspend fun state(context: Context, selectedFilter: RecordingsFilter): RecordingsViewState {
        val appContext = context.applicationContext
        val db = OrtDatabase.create(appContext)
        val summaries = recordingSessionSummaries(db)

        val dbFile = appContext.getDatabasePath(OrtDatabase.DATABASE_NAME)
        val databaseFiles = listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm"))
        val accounting = measureStorageAccounting(appContext.filesDir, databaseFiles)

        val prefs = appContext.getSharedPreferences(SharedPreferencesSettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
        val settings = SharedPreferencesSettingsStore(prefs)
        val overAudioBudget = overAudioBudgetState(accounting.audioBytes, settings.audioBudgetGb)

        val liveSessionId = if (CaptureState.isCapturing) CaptureState.sessionId else null

        return RecordingsViewStateMapper.map(
            summaries = summaries,
            accounting = accounting,
            budgetInputs = RecordingsBudgetInputs(
                overAudioBudget = overAudioBudget,
                archiveEnabled = settings.archiveEnabled,
                archiveBudgetGb = settings.archiveBudgetGb,
                archiveRateState = ArchiveWriteRateForecast.state,
            ),
            selectedFilter = selectedFilter,
            liveSessionId = liveSessionId,
            nowMillis = SystemClock.wallMillis(),
        )
    }
}
