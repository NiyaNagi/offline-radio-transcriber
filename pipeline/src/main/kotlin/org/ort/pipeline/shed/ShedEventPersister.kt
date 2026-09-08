package org.ort.pipeline.shed

import org.ort.core.Clock
import org.ort.core.Ulid
import org.ort.data.dao.ShedEventDao
import org.ort.data.entity.ShedEventEntity
import org.ort.data.entity.ShedTrigger

/**
 * audit F-007: maps [ShedController.ShedEvent] (which cannot itself reference `:data` — `:pipeline`
 * does not depend on it — see [ShedEventEntity]'s own KDoc) onto the persisted `shed_event` row
 * (F-021's table; FR-RUN-3, FR-RUN-5). Same shape as [org.ort.pipeline.GapPersister]: nothing is
 * ever filtered here, every event that reaches this class is written (constitution III).
 *
 * [levelBefore] is passed in rather than read from the controller because by the time an event is
 * persisted the controller has already moved past it — the caller (`ShedEventRelay`) is the one
 * that knows what the level was immediately before this particular transition.
 */
public class ShedEventPersister(private val dao: ShedEventDao, private val clock: Clock) {

    public suspend fun persist(
        sessionId: String,
        levelBefore: Int,
        event: ShedController.ShedEvent,
        samplePosition: Long,
    ): ShedEventEntity {
        val entity = ShedEventEntity(
            id = Ulid.generate(clock).value,
            sessionId = sessionId,
            levelBefore = levelBefore,
            levelAfter = event.level,
            trigger = inferTrigger(event.reason),
            reason = event.reason,
            atWallMillis = event.atWallMillis,
            atMonotonicNanos = clock.monotonicNanos(),
            samplePosition = samplePosition,
        )
        dao.insert(entity)
        return entity
    }

    public companion object {
        /**
         * [ShedController.sample] only ever writes two reason shapes today — `"battery ..."` for
         * the battery-critical override and `"backlog ..."` for a threshold crossing (see its
         * source) — so a prefix match is exact, not a guess. Anything else (there is no third
         * shape yet) is classified [ShedTrigger.STORAGE], which is where a future storage-floor
         * transition belongs once one is ever recorded through this same path.
         */
        internal fun inferTrigger(reason: String): ShedTrigger = when {
            reason.startsWith("battery") -> ShedTrigger.BATTERY
            reason.startsWith("backlog") -> ShedTrigger.BACKLOG
            else -> ShedTrigger.STORAGE
        }
    }
}
