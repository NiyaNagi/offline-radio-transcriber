package org.ort.pipeline.archive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase

/**
 * `Recordings.dc.html`'s (RC01) own per-session row — span, counts, and both audio-removal facts
 * (over audio and the raw archive), assembled from existing DAOs plus [archiveSessionStates].
 * Nothing here is a new measurement; this is only the read shape RC01 needs that no existing
 * query already returns in one place.
 *
 * [labelledCount] counts overs *marked for training* ([org.ort.data.entity.TransmissionLabelEntity
 * .markedForTraining]), matching RC02's own "training · good" badge wording and RC01's "Labelled"
 * chip — not merely "has any label row at all". If the artboard's intent turns out to be broader
 * (any labelled field, mark or not) once RC01 is actually built, widening this is a one-line
 * change to [org.ort.data.dao.TransmissionLabelDao.countMarkedForTrainingBySession]'s `WHERE`.
 *
 * [stationCount]/[gapCount] (widened additively, coordinator round: `Recordings.dc.html`'s own
 * row sub-line reads "N overs · N stations · N gaps" — three real, distinct facts, never the over
 * count repeated) mirror `DigestPolling.sessions`'s own real computation exactly:
 * [stationCount] is the distinct, non-null `stationId` count across this session's transmissions
 * (never a station attributed more than once), and [gapCount] is this session's own real
 * [org.ort.data.dao.CaptureGapDao.listBySession] row count (FR-RUN-12 — a gap is data, not
 * silently folded into "listening time").
 */
public data class RecordingSessionSummary(
    public val sessionId: String,
    public val startedAtMillis: Long,
    public val endedAtMillis: Long?,
    public val overCount: Int,
    public val failedCount: Int,
    public val labelledCount: Int,
    public val stationCount: Int,
    public val gapCount: Int,
    public val overAudioRemovedAtMillis: Long?,
    public val archiveState: ArchiveState,
    public val archiveRemovedAtMillis: Long?,
)

/** `Recordings.dc.html` (RC01): every session, newest first (matches
 * [org.ort.data.dao.SessionDao.listAll]'s own order) — every count real, never fabricated
 * (constitution I). */
public suspend fun recordingSessionSummaries(db: OrtDatabase): List<RecordingSessionSummary> =
    withContext(Dispatchers.IO) {
        val archiveStates = archiveSessionStates(db).associateBy { it.sessionId }
        db.sessionDao().listAll().map { session ->
            val transmissions = db.transmissionDao().listBySession(session.id)
            val archive = archiveStates[session.id]
            RecordingSessionSummary(
                sessionId = session.id,
                startedAtMillis = session.startedAt,
                endedAtMillis = session.endedAt,
                overCount = transmissions.size,
                failedCount = transmissions.count { it.processingState == TransmissionState.FAILED },
                labelledCount = db.transmissionLabelDao().countMarkedForTrainingBySession(session.id),
                stationCount = transmissions.mapNotNull { it.stationId }.distinct().size,
                gapCount = db.captureGapDao().listBySession(session.id).size,
                overAudioRemovedAtMillis = session.overAudioRemovedAtMillis,
                archiveState = archive?.state ?: ArchiveState.NONE,
                archiveRemovedAtMillis = archive?.removedAtMillis,
            )
        }
    }
