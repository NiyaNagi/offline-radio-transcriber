package org.ort.app.ui.recordings

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ort.app.ui.data.CorrectionPolling
import org.ort.core.SystemClock
import org.ort.core.capture.CaptureMode
import org.ort.data.OrtDatabase
import org.ort.pipeline.archive.SessionAudioDeletionRefusal
import org.ort.pipeline.archive.SessionAudioDeletionResult
import org.ort.pipeline.archive.SessionAudioDeletionService
import org.ort.pipeline.archive.SessionAudioExport
import org.ort.pipeline.archive.SessionAudioExportRefusal
import org.ort.pipeline.archive.SessionAudioExportTarget
import org.ort.pipeline.archive.SessionAudioExportWriteResult
import org.ort.pipeline.archive.SessionAudioTarget
import org.ort.pipeline.archive.recordingSessionSummaries
import org.ort.pipeline.label.TransmissionLabelFields
import org.ort.pipeline.label.TransmissionLabelRepository
import java.io.File
import java.io.OutputStream

/**
 * `Recording-Session.dc.html` (RC02): the real read/write path — every fact is either
 * `org.ort.pipeline.archive.recordingSessionSummaries`, this session's own transmission/transcript/
 * catalog/gap/work-queue DAOs, or one of the three pipeline services RC02 consumes rather than
 * reimplements: [SessionAudioDeletionService] (Delete), [SessionAudioExport] (Export) and
 * [TransmissionLabelRepository] (Label). [org.ort.app.ui.data.CorrectionPolling.retryFailedPass]
 * is reused verbatim for Retry — the identical mechanism `TransmissionDetailContent`'s own
 * `onRetryPass` already uses, not a second implementation of the same requeue.
 */
public object RecordingSessionPolling {

    /** `null` for an unknown [sessionId] — the content composable renders its own not-found state
     * for that case (R-1051's own "never a fabricated default" discipline, applied to a 404 rather
     * than a slow load). [playingTransmissionId] is the transport controller's own loaded
     * transmission id, if any — passed straight through to the mapper so the coverage strip's
     * playhead is real (see [RecordingSessionMapperInput.playingTransmissionId]'s own doc comment). */
    public suspend fun state(
        context: Context,
        sessionId: String,
        playingTransmissionId: String?,
    ): RecordingSessionViewState? = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val db = OrtDatabase.create(appContext)
        val summary = recordingSessionSummaries(db).firstOrNull { it.sessionId == sessionId }
            ?: return@withContext null
        val session = db.sessionDao().getById(sessionId) ?: return@withContext null

        val transmissions = db.transmissionDao().listBySession(sessionId)
        val failedQueueItemsByTransmission = db.workQueueDao().selectFailed(pass = null, lastErrorPrefix = null)
            .associateBy { it.transmissionId }

        val overs = transmissions.map { transmission ->
            val transcript = db.transcriptDao().getCurrent(transmission.id)
            val station = transmission.stationId?.let { db.catalogDao().getStation(it) }
            val callsign = station?.callsign?.takeIf { it.isNotBlank() } ?: transmission.stationId
            val label = db.transmissionLabelDao().getByTransmissionId(transmission.id)
            val audioFile = File(appContext.filesDir, transmission.audioPath())
            RecordingSessionOverInput(
                transmission = transmission,
                transcriptText = transcript?.text,
                callsign = callsign,
                label = label,
                failedAttemptCount = failedQueueItemsByTransmission[transmission.id]?.attemptCount,
                hasAudioFile = audioFile.isFile,
            )
        }

        val gaps = db.captureGapDao().listBySession(sessionId)
        val deletePreview = SessionAudioDeletionService.preview(db, appContext.filesDir, sessionId)
        val exportRefusal = SessionAudioExport.canExport(db, sessionId, SessionAudioExportTarget.BOTH)

        RecordingSessionViewStateMapper.map(
            RecordingSessionMapperInput(
                sessionId = sessionId,
                startedAtMillis = summary.startedAtMillis,
                endedAtMillis = summary.endedAtMillis,
                captureMode = session.captureMode?.let { runCatching { CaptureMode.valueOf(it) }.getOrNull() },
                // D50 (Q22, FR-SEG-10, AC-162): the session's own recorded detector, read straight
                // off the full entity already loaded above — never re-derived from a transmission.
                vadDetector = session.vadDetector,
                overs = overs,
                gaps = gaps,
                failedCount = summary.failedCount,
                stationCount = summary.stationCount,
                deleteFreesBytes = deletePreview?.bytesFor(SessionAudioTarget.BOTH) ?: 0L,
                exportAvailable = exportRefusal == null,
                playingTransmissionId = playingTransmissionId,
                nowMillis = SystemClock.wallMillis(),
            ),
        )
    }

    // -- Delete (SessionAudioTarget.BOTH — the artboard offers one Delete, not a per-target picker) --

    public suspend fun deletePreview(context: Context, sessionId: String): RecordingSessionDeleteState =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val db = OrtDatabase.create(appContext)
            val refusal = SessionAudioDeletionService.canDelete(db, sessionId)
            if (refusal != null) return@withContext RecordingSessionDeleteState.Refused(refusal)
            val preview = SessionAudioDeletionService.preview(db, appContext.filesDir, sessionId)
                ?: return@withContext RecordingSessionDeleteState.Refused(SessionAudioDeletionRefusal.SessionNotFound)
            RecordingSessionDeleteState.Preview(
                bytesToFree = preview.bytesFor(SessionAudioTarget.BOTH),
                overAudioBytes = preview.overAudioBytes,
                overAudioAlreadyRemoved = preview.overAudioAlreadyRemoved,
                archiveBytes = preview.archiveBytes,
                archiveState = preview.archiveState,
            )
        }

    public suspend fun delete(context: Context, sessionId: String): RecordingSessionDeleteState =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val db = OrtDatabase.create(appContext)
            when (
                val result = SessionAudioDeletionService.delete(
                    db,
                    appContext.filesDir,
                    sessionId,
                    SessionAudioTarget.BOTH,
                )
            ) {
                is SessionAudioDeletionResult.Refused -> RecordingSessionDeleteState.Refused(result.reason)
                is SessionAudioDeletionResult.Deleted -> RecordingSessionDeleteState.Deleted(
                    bytesFreed = result.bytesFreed,
                    overAudioRemovedAtMillis = result.overAudioRemovedAtMillis,
                    archiveRemovedAtMillis = result.archiveRemovedAtMillis,
                )
            }
        }

    // -- Export (SessionAudioExportTarget.BOTH, same single-target shape as Delete) --

    public suspend fun exportPreview(context: Context, sessionId: String): RecordingSessionExportState =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val db = OrtDatabase.create(appContext)
            val refusal = SessionAudioExport.canExport(db, sessionId, SessionAudioExportTarget.BOTH)
            if (refusal != null) return@withContext RecordingSessionExportState.Refused(refusal)
            val preview = SessionAudioExport.preview(db, appContext.filesDir, sessionId, SessionAudioExportTarget.BOTH)
                ?: return@withContext RecordingSessionExportState.Refused(SessionAudioExportRefusal.SessionNotFound)
            // Round 2 (coordinator review): `canExport` refuses only against the two *marked* facts
            // (over audio removed, archive state) -- a session neither flag forbids can still have
            // genuinely zero real files on disk (this build's own `recordings-budget-exceeded`
            // fixture: real DB byte accounting, no real file ever written). `preview` answers "what
            // would this write, right now" honestly (its own kdoc) rather than refusing -- so RC02's
            // own sheet, not the service, is where "nothing real to export" becomes its own refusal,
            // never a `Preview(0, 0L, ...)` with Save left enabled over nothing (constitution I, II).
            if (preview.files.isEmpty()) {
                return@withContext RecordingSessionExportState.Refused(
                    SessionAudioExportRefusal.NothingToExport(
                        "nothing to export for session $sessionId: no audio files exist on disk for this session",
                    ),
                )
            }
            RecordingSessionExportState.Preview(
                fileCount = preview.files.size,
                totalBytes = preview.totalBytes,
                suggestedFileName = SessionAudioExport.suggestedFileName(sessionId, SessionAudioExportTarget.BOTH),
            )
        }

    /** Writes through [out] off the caller's own main thread ([Dispatchers.IO]) — the caller
     * (`RecordingSessionContent`) opens [out] from the Storage Access Framework `Uri` it was
     * handed, the same shape `SettingsExportSubScreen` already uses. */
    public suspend fun exportWrite(
        context: Context,
        sessionId: String,
        out: OutputStream,
        onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit,
    ): RecordingSessionExportState = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val db = OrtDatabase.create(appContext)
        when (
            val result = SessionAudioExport.write(
                db = db,
                filesDir = appContext.filesDir,
                sessionId = sessionId,
                target = SessionAudioExportTarget.BOTH,
                out = out,
                onProgress = onProgress,
            )
        ) {
            is SessionAudioExportWriteResult.Refused -> RecordingSessionExportState.Refused(result.reason)
            is SessionAudioExportWriteResult.Written ->
                RecordingSessionExportState.Written(result.fileCount, result.totalBytes)
        }
    }

    // -- Label (TransmissionLabelRepository — never an attribution) --

    public suspend fun labelSheetState(
        context: Context,
        transmissionId: String,
        callsignLabel: String,
    ): RecordingSessionLabelSheetViewState = withContext(Dispatchers.IO) {
        val db = OrtDatabase.create(context.applicationContext)
        val existing = TransmissionLabelRepository.get(db, transmissionId)
        RecordingSessionLabelSheetViewState(
            transmissionId = transmissionId,
            callsignLabel = callsignLabel,
            markedForTraining = existing?.markedForTraining ?: false,
            rating = existing?.rating,
        )
    }

    public suspend fun setMarkedForTraining(context: Context, transmissionId: String, markedForTraining: Boolean) {
        withContext(Dispatchers.IO) {
            val db = OrtDatabase.create(context.applicationContext)
            TransmissionLabelRepository.setMarkedForTraining(db, transmissionId, markedForTraining)
        }
    }

    /** Sets [rating] alone, preserving every other already-recorded label field exactly as it was
     * — the same "flip one field, keep the rest" discipline
     * [TransmissionLabelRepository.setMarkedForTraining]'s own kdoc states for its own field. */
    public suspend fun setRating(context: Context, transmissionId: String, rating: String) {
        withContext(Dispatchers.IO) {
            val db = OrtDatabase.create(context.applicationContext)
            val existing = TransmissionLabelRepository.get(db, transmissionId)
            TransmissionLabelRepository.label(
                db,
                transmissionId,
                TransmissionLabelFields(
                    markedForTraining = existing?.markedForTraining ?: true,
                    outcome = existing?.outcome,
                    doubled = existing?.doubled ?: false,
                    truthCallsign = existing?.truthCallsign,
                    callsignCertainty = existing?.callsignCertainty,
                    tacticalCallsign = existing?.tacticalCallsign,
                    note = existing?.note,
                    rating = rating,
                ),
            )
        }
    }

    // -- Retry (the identical mechanism TransmissionDetailContent.onRetryPass already uses) --

    /** `false` when no matching `FAILED` work-queue item exists any more for [transmissionId] —
     * never throws for a stale button press (mirrors
     * [org.ort.app.ui.data.CorrectionPolling.retryFailedPass]'s own contract). */
    public suspend fun retry(context: Context, transmissionId: String): Boolean = withContext(Dispatchers.IO) {
        val db = OrtDatabase.create(context.applicationContext)
        val failed = db.workQueueDao().selectFailed(pass = null, lastErrorPrefix = null)
            .firstOrNull { it.transmissionId == transmissionId }
            ?: return@withContext false
        CorrectionPolling.retryFailedPass(context, transmissionId, failed.pass)
    }
}
