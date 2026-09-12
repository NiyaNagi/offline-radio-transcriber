package org.ort.pipeline.archive

import org.ort.capture.android.archive.ArchiveHole
import org.ort.core.Clock
import org.ort.core.Ulid
import org.ort.data.dao.ArchiveGapDao
import org.ort.data.entity.ArchiveGapEntity

/**
 * WPARC (FR-RUN-12, constitution IV): maps `:capture-android`'s plain [ArchiveHole] (which cannot
 * itself reference `:data` — module graph) onto the persisted [ArchiveGapEntity]. Nothing is ever
 * filtered here: every hole that reaches this class is written, never dropped a second time.
 */
public class ArchiveGapPersister(private val dao: ArchiveGapDao, private val clock: Clock) {

    public suspend fun persist(sessionId: String, hole: ArchiveHole): ArchiveGapEntity {
        val entity = ArchiveGapEntity(
            id = Ulid.generate(clock).toString(),
            sessionId = sessionId,
            startSample = hole.startSample,
            sampleCount = hole.sampleCount.toLong(),
            reason = hole.reason,
            recordedAtMillis = clock.wallMillis(),
        )
        dao.insert(entity)
        return entity
    }

    /** [ContinuousArchiveAttachment][org.ort.pipeline.capture.ContinuousArchiveAttachment]'s own
     * `onFailure` shape — an unexpected exception, never a structured [ArchiveHole] (which only
     * ever comes from a FLAC verification failure). Recorded with the exception's class name as
     * the reason (constitution II: never a caught exception's free-text message). */
    public suspend fun persistFailure(sessionId: String, startSample: Long, sampleCount: Int, reason: String) {
        persist(sessionId, ArchiveHole(startSample, sampleCount, reason))
    }
}
