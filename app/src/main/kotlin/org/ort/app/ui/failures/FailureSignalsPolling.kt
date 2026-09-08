package org.ort.app.ui.failures

import android.content.Context
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
 */
public object FailureSignalsPolling {

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
        return FailureSignals(
            captureState = CaptureState.state,
            inputStatus = InputStatus.state,
            levelStatus = LevelStatus.state,
            thermalStatus = ThermalStatus.state,
            rigStatus = RigStatus.state,
            storageForecast = StorageForecast.state,
            shedLevel = ShedStatus.currentLevel,
            shedBacklog = ShedStatus.backlog,
            newestGap = newestGap,
            nowMillis = SystemClock.wallMillis(),
            debugOverride = DebugFailureOverride.activeOverride,
            sessionStartedAtMillis = sessionStartedAtMillis,
            sessionTransmissionCount = sessionTransmissionCount,
        )
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
