package org.ort.app.ui.data

import android.content.Context
import android.os.PowerManager
import org.ort.app.status.StatusViewState
import org.ort.app.status.StatusViewStateMapper
import org.ort.app.transmissions.TransmissionRow
import org.ort.capture.android.heartbeat.FileHeartbeatStore
import org.ort.core.Attribution
import org.ort.core.SystemClock
import org.ort.data.OrtDatabase
import org.ort.pipeline.CaptureStatusRepository
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.shed.ShedController
import org.ort.pipeline.shed.ShedSignals
import java.io.File

/**
 * The same v0 smoke-test data path [org.ort.app.status.StatusActivity] and
 * [org.ort.app.transmissions.TransmissionListActivity] poll (build-plan P8/P11) — reused here,
 * not reinvented, so the new Compose screens (build-plan P13) show the identical facts the plain-
 * view surfaces already show ("no behaviour change"). Those two Activities are owned by a
 * concurrent session (P12) and are left untouched; this is a new, independent read path over the
 * same repository types.
 *
 * A real reader (M5, P14 on) replaces polling with a `Flow` observed straight from `:data` and
 * `:pipeline`; this stays a poll for the same reason the Activities it mirrors do — it is not
 * this prompt's job to change that wiring, only to prove Compose can render what it produces.
 */
public object ReaderPolling {

    public fun uncleanEndBanner(context: Context): String? {
        val repository = statusRepository(context)
        val uncleanEnd = repository.uncleanEndFromPreviousLaunch() ?: return null
        return "The previous session ended unexpectedly. Last heartbeat: ${uncleanEnd.lastHeartbeatWallMillis}."
    }

    public suspend fun currentStatus(context: Context, sessionId: String, startedAtWallMillis: Long): StatusViewState {
        val db = OrtDatabase.create(context.applicationContext)
        val repository = statusRepository(context)
        val count = db.transmissionDao().listBySession(sessionId).size
        val gaps = db.captureGapDao().listBySession(sessionId).size
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val status = repository.current(
            sessionId = sessionId,
            isCapturing = CaptureState.isCapturing,
            elapsedMillis = SystemClock.wallMillis() - startedAtWallMillis,
            transmissionCount = count,
            gapCount = gaps,
            isIgnoringBatteryOptimizationsDiagnosticOnly = pm.isIgnoringBatteryOptimizations(context.packageName),
        )
        val base = StatusViewStateMapper.from(status)
        val failure = CaptureState.failureReason
        return if (failure != null) base.copy(stateLabel = "${base.stateLabel} — $failure") else base
    }

    public suspend fun currentTransmissions(context: Context, sessionId: String): List<TransmissionRow> {
        val db = OrtDatabase.create(context.applicationContext)
        return db.transmissionDao().listBySession(sessionId).map { entity ->
            TransmissionRow(
                id = entity.id,
                transcript = "(captured, not yet transcribed)",
                attribution = Attribution.unknown(),
            )
        }
    }

    private fun statusRepository(context: Context): CaptureStatusRepository {
        val heartbeatStore = FileHeartbeatStore(File(context.filesDir, "heartbeat.txt"))
        // Same neutral, always-nominal shed signals as the v0 Activities — no real shed telemetry
        // is wired for this smoke path (see StatusActivity's identical comment).
        val shedController = ShedController(
            object : ShedSignals {
                override fun batteryPercent(): Int = 100
                override fun isCharging(): Boolean = true
                override fun queueBacklog(): Int = 0
                override fun freeStorageBytes(): Long = Long.MAX_VALUE
            },
            SystemClock,
        )
        return CaptureStatusRepository(heartbeatStore, shedController, SystemClock)
    }
}
