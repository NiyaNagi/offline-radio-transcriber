package org.ort.app.ui.failures

import android.content.Context
import org.ort.app.ui.data.ModelsController
import org.ort.app.ui.data.RoomActiveLexiconStore
import org.ort.app.ui.data.StagedActivation
import org.ort.core.SystemClock
import org.ort.data.OrtDatabase
import org.ort.data.entity.CaptureGapEntity
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.ShedStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus

/**
 * [FailureHost]'s own read path (guide's "polling and I/O stay in `ui/data`" rule — this package
 * owns the whole `ui/failures` directory, so its polling stays here rather than reaching into
 * `ui/data`, which WP4 owns; see this package's report). A snapshot, not a stream: every
 * process-wide holder plus, at most, the newest [org.ort.data.entity.CaptureGapEntity], the
 * current session's own start time and transmission count (register R-126) for the current
 * session, read once per tick so [FailureMapper.map] sees one consistent instant (matching
 * `LiveBarPolling.current`'s own shape and cadence).
 *
 * FR-AST-4 (coordinator-directed, once WP10's `ModelsController.stagedActivation` guard landed on
 * main): one deliberate exception to the "polling/I/O stays out of `ui/data`" line above —
 * [ModelsController]/[RoomActiveLexiconStore] are `ui/data` real signals this package's own
 * `AssetSwap`/F21 presentation genuinely needs, with no `:pipeline`/`:data` equivalent to read
 * instead (see [FailureMapper.map]'s own kdoc).
 */
public object FailureSignalsPolling {

    /** Register R-149: how far back [current] keeps a `ShedStatus.backlog` sample — `Fail-Backlog.dc.html`'s
     * own "last 30 minutes" chart. */
    public const val BACKLOG_HISTORY_WINDOW_MILLIS: Long = 30 * 60_000L

    /** A defensive cap on [storageHistory]'s size — one transition per stage, so this never grows
     * unbounded even across an implausibly long process lifetime. */
    private const val STORAGE_HISTORY_MAX_ENTRIES = 20

    private val storageHistory = mutableListOf<StorageForecastSample>()
    private val backlogHistory = mutableListOf<BacklogSample>()

    public suspend fun current(context: Context, sessionId: String?): FailureSignals {
        var newestGap: CaptureGapEntity? = null
        var sessionStartedAtMillis: Long? = null
        var sessionTransmissionCount = 0
        if (sessionId != null) {
            val db = OrtDatabase.create(context.applicationContext)
            newestGap = db.captureGapDao().listBySession(sessionId).maxByOrNull { it.startedAt }
            sessionStartedAtMillis = db.sessionDao().getById(sessionId)?.startedAt
            sessionTransmissionCount = db.transmissionDao().listBySession(sessionId).size
        }
        val now = SystemClock.wallMillis()
        val storageState = StorageForecast.state
        val backlog = ShedStatus.backlog
        recordStorageTransition(storageState, now)
        recordBacklogSample(backlog, sessionTransmissionCount, now)
        // FR-AST-4 (register R-448 follow-up, coordinator-directed): the one real cross-package
        // read this object makes — `ModelsController.refreshStagedActivation` re-reads whatever
        // `StagedActivationStore` persisted from an earlier process, so a fresh process that opened
        // straight into `ReaderActivity` (never visited Settings-Assets) still reports a real, not
        // stale-null, staged fact. The active-label lookup only runs while something is actually
        // staged — real Room/file I/O, deliberately not paid on every otherwise-clean tick.
        val appContext = context.applicationContext
        ModelsController.refreshStagedActivation(appContext)
        val stagedActivation = ModelsController.stagedActivation.value
        val stagedActivationActiveLabel = stagedActivation?.let { activeAssetLabelFor(appContext, it) }
        return FailureSignals(
            captureState = CaptureState.state,
            inputStatus = InputStatus.state,
            levelStatus = LevelStatus.state,
            thermalStatus = ThermalStatus.state,
            rigStatus = RigStatus.state,
            storageForecast = storageState,
            shedLevel = ShedStatus.currentLevel,
            shedBacklog = backlog,
            newestGap = newestGap,
            nowMillis = now,
            debugOverride = DebugFailureOverride.activeOverride,
            sessionStartedAtMillis = sessionStartedAtMillis,
            sessionTransmissionCount = sessionTransmissionCount,
            storageForecastHistory = storageHistory.toList(),
            backlogHistory = backlogHistory.toList(),
            stagedActivation = stagedActivation,
            stagedActivationActiveLabel = stagedActivationActiveLabel,
        )
    }

    /** FR-AST-4: the real, currently-*active* counterpart to [staged] — [RoomActiveLexiconStore]'s
     * own real version for a staged lexicon swap, or the currently-installed checksum prefix
     * ([ModelsController.currentState]'s own [org.ort.app.ui.data.ModelRowViewState.checksumPrefix],
     * the same real fact `Settings-Assets` itself shows) for a staged model — never a fabricated
     * "active" fact when nothing real is installed yet. */
    private fun activeAssetLabelFor(context: Context, staged: StagedActivation): String =
        if (staged.assetId == ModelsController.CALLSIGN_LEXICON_ASSET_ID) {
            RoomActiveLexiconStore(context).current()?.let { "${it.version} · ${it.recordCount} records active" }
                ?: "no lexicon active yet"
        } else {
            val row = ModelsController.currentState(context).rows.firstOrNull { it.id.name == staged.assetId }
            row?.checksumPrefix?.let { "$it active" } ?: "not yet installed"
        }

    /** Register R-149: one entry per distinct `StorageForecast.State` subtype actually observed,
     * never a repeat of the same stage on every tick — `Fail-Storage.dc.html`'s own timeline is a
     * list of *moments something changed*, not a sample per second. */
    private fun recordStorageTransition(state: StorageForecast.State, atMillis: Long) {
        val last = storageHistory.lastOrNull()
        if (last == null || last.state::class != state::class) {
            storageHistory += StorageForecastSample(state, atMillis)
        }
        while (storageHistory.size > STORAGE_HISTORY_MAX_ENTRIES) storageHistory.removeAt(0)
    }

    /** Register R-149/R-254: one sample per tick, trimmed to [BACKLOG_HISTORY_WINDOW_MILLIS] — a
     * real, bounded rolling window, never a fabricated full 30 minutes before the process has run
     * one. [transmissionCount] rides along so `FailureMapper.backlogRateLabel` can derive the Rate
     * row's "Band"/"Pass B" split honestly — see that function's own kdoc. */
    private fun recordBacklogSample(backlog: Int, transmissionCount: Int, atMillis: Long) {
        backlogHistory += BacklogSample(backlog, atMillis, transmissionCount)
        backlogHistory.removeAll { atMillis - it.atMillis > BACKLOG_HISTORY_WINDOW_MILLIS }
    }

    /** This object's own history is process-lifetime state, the same shape as every holder it
     * reads (`ShedStatus.reset()`, `StorageForecast.reset()`, ...) — a scenario reload
     * (`Scenarios.kt`'s `resetProcessWideFacets`) must clear it too, or a previous scenario's
     * timeline would leak into the next one's F6/F8 boards. */
    public fun reset() {
        storageHistory.clear()
        backlogHistory.clear()
    }

    /** [RecoveryAnnouncer]'s "N overs can be improved" figure — read only at the moment a tier
     * recovery is actually detected (never on every tick; this is the one query this object runs
     * beyond the plain holder reads above). */
    public suspend fun improvableCount(context: Context, sessionId: String?): Int {
        if (sessionId == null) return 0
        val db = OrtDatabase.create(context.applicationContext)
        return db.transmissionDao().listBySession(sessionId).count { it.isReprocessCandidate }
    }
}
