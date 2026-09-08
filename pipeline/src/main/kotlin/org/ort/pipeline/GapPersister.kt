package org.ort.pipeline

import org.ort.capture.android.GapRecord
import org.ort.core.Clock
import org.ort.core.Ulid
import org.ort.data.dao.CaptureGapDao
import org.ort.data.entity.CaptureGapCause
import org.ort.data.entity.CaptureGapEntity

/**
 * Maps `:capture-android`'s plain [GapRecord] (which cannot itself reference `:data` — module
 * graph, technical design §2) onto the persisted `CaptureGapEntity` (FR-RUN-12 → AC-48, AC-49).
 * Nothing is ever deleted here: every gap that reaches this class is written, never filtered.
 */
public class GapPersister(private val dao: CaptureGapDao, private val clock: Clock) {

    public suspend fun persist(sessionId: String, gap: GapRecord): CaptureGapEntity {
        val entity = CaptureGapEntity(
            id = Ulid.generate(clock).toString(),
            sessionId = sessionId,
            startedAt = gap.startWallMillis,
            endedAt = gap.endWallMillis,
            cause = causeFor(gap.cause),
            recoveredAutomatically = true,
        )
        dao.insert(entity)
        return entity
    }

    private fun causeFor(cause: String): CaptureGapCause = when {
        cause.contains("route", ignoreCase = true) -> CaptureGapCause.ROUTE_CHANGE
        cause.contains("focus", ignoreCase = true) || cause.contains("call", ignoreCase = true) ->
            CaptureGapCause.INTERRUPTION
        // audit F-028: F-010's DroppedSpanCause encoding ("dropped samples: N samples over Xms
        // (stalled consumer or read shortfall)") matched none of these -- neither "device" nor
        // "read error" appears in it -- so every dropped-span gap silently fell to UNKNOWN. It is
        // exactly the AudioRecord/consumer shortfall DEVICE_LOST already means; :pipeline cannot
        // reference capture-android's internal DroppedSpanCause object, so this matches its
        // stable "dropped samples:" prefix directly instead.
        cause.startsWith("dropped samples:", ignoreCase = true) ||
            cause.contains("device", ignoreCase = true) ||
            cause.contains("read error", ignoreCase = true) ->
            CaptureGapCause.DEVICE_LOST
        cause.contains("storage", ignoreCase = true) -> CaptureGapCause.STORAGE
        else -> CaptureGapCause.UNKNOWN
    }
}
