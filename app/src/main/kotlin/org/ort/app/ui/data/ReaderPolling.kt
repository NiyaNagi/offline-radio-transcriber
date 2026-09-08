package org.ort.app.ui.data

import android.content.Context
import android.os.PowerManager
import org.ort.app.status.StatusViewState
import org.ort.app.status.StatusViewStateMapper
import org.ort.capture.android.heartbeat.FileHeartbeatStore
import org.ort.core.Attribution
import org.ort.core.AttributionState
import org.ort.core.SystemClock
import org.ort.core.TransmissionId
import org.ort.data.OrtDatabase
import org.ort.data.entity.TransmissionEntity
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

    /**
     * The real reader read path (build-plan P14, FR-UI-1): every transmission in [sessionId],
     * **newest first**, each carrying its real current transcript (or an honest `null` when Pass
     * B has not produced one yet — [org.ort.app.ui.data.ReaderTransmissionViewStateMapper] turns
     * that into "captured, not yet transcribed"), its real attribution, and every superseded
     * transcript version so a revision is visible rather than silently replaced (AC-31's
     * append-only guarantee, made visible here rather than just enforced at the data layer).
     */
    public suspend fun currentTransmissionDetails(context: Context, sessionId: String): List<TransmissionDetail> {
        val db = OrtDatabase.create(context.applicationContext)
        // listBySession orders ascending by samplePosition (capture order) — reversed here for
        // "newest first" (FR-UI-1) rather than adding a second, differently-ordered DAO query.
        return db.transmissionDao().listBySession(sessionId).asReversed().map { entity ->
            detailFrom(context, db, entity)
        }
    }

    /** One transmission's full detail, for the drill-in screen (FR-UI-5, FR-UI-8's non-lattice half). */
    public suspend fun transmissionDetail(context: Context, transmissionId: String): TransmissionDetail? {
        val db = OrtDatabase.create(context.applicationContext)
        val entity = db.transmissionDao().getById(transmissionId) ?: return null
        return detailFrom(context, db, entity)
    }

    private suspend fun detailFrom(context: Context, db: OrtDatabase, entity: TransmissionEntity): TransmissionDetail {
        val versions = db.transcriptDao().getAllVersions(entity.id)
        val current = versions.firstOrNull { it.isCurrent }
        val superseded = versions.filter { !it.isCurrent }.sortedBy { it.createdAt }.map { it.text }
        val audioFile = File(context.filesDir, entity.audioPath())
        return TransmissionDetail(
            id = entity.id,
            startedAtUtcMillis = entity.startedAtUtc,
            frequencyHz = entity.frequencyHz,
            durationMs = entity.durationMs,
            signalStrength = entity.signalStrength,
            attribution = attributionFrom(entity),
            currentTranscriptText = current?.text,
            supersededTranscriptTexts = superseded,
            hasAudio = audioFile.isFile,
        )
    }

    /**
     * Reconstructs the type-safe [Attribution] the constitution requires (Principle I) from the
     * entity's raw columns. Falls back to [Attribution.unknown] rather than throwing if a
     * `CONFIRMED`/`INFERRED` row is ever missing the station or confidence its own factory
     * requires — a display concern must never crash the reader over a data inconsistency it did
     * not cause, but it must also never *invent* a station that was not actually written.
     */
    private fun attributionFrom(entity: TransmissionEntity): Attribution {
        val stationId = entity.stationId
        val confidence = entity.attributionConfidence
        return when (entity.attributionState) {
            AttributionState.CONFIRMED ->
                if (stationId != null && confidence != null) {
                    Attribution.confirmed(stationId, confidence)
                } else {
                    Attribution.unknown()
                }

            AttributionState.INFERRED ->
                if (stationId != null && confidence != null) {
                    Attribution.inferred(stationId, confidence, sourceId(entity))
                } else {
                    Attribution.unknown()
                }

            AttributionState.AMBIGUOUS -> Attribution.ambiguous()
            AttributionState.UNKNOWN -> Attribution.unknown()
        }
    }

    private fun sourceId(entity: TransmissionEntity): TransmissionId? =
        entity.attributionSourceTransmissionId?.let { runCatching { TransmissionId.parse(it) }.getOrNull() }

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
