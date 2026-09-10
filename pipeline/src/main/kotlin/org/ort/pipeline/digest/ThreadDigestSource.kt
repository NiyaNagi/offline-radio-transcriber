package org.ort.pipeline.digest

import org.ort.core.AttributionState
import org.ort.data.OrtDatabase

/** One thread [ThreadDigestSource.pendingThreads] found still needing a prose summary. */
public data class PendingThreadDigest(
    val threadId: String,
    val input: ThreadDigestInput,
    val sourceTransmissionIds: List<String>,
)

/**
 * FR-DIG-3, AC-87: the real "which threads need a prose summary" query — every thread whose
 * session has **ended** (a live session is not yet a finished digest, and FR-DIG-5 forbids
 * generation during capture regardless — [ProseDigestGate]/[ProseDigestWorkRunner] enforce that
 * independently) and which [store] has no summary for yet. Reads only through DAOs `:data`
 * already exposes — no new query added to any DAO this package does not own.
 *
 * Resolved callsigns come only from `CONFIRMED`/`INFERRED` attributions (FR-DIG-12's closed field
 * list starts from *resolved* entities, never a raw station id, never an `AMBIGUOUS`/`UNKNOWN`
 * guess); a thread with no resolved callsign at all still gets summarized — a summary is about
 * what was said, not gated on who said it.
 */
public class ThreadDigestSource(private val db: OrtDatabase, private val store: ProseSummaryStore) {

    public suspend fun pendingThreads(): List<PendingThreadDigest> {
        val endedSessionIds = db.sessionDao().listAll().filter { it.endedAt != null }.map { it.id }
        if (endedSessionIds.isEmpty()) return emptyList()

        val transmissionsByThread = endedSessionIds
            .flatMap { db.transmissionDao().listBySession(it) }
            .filter { it.threadId != null }
            .groupBy { it.threadId!! }
        if (transmissionsByThread.isEmpty()) return emptyList()

        val alreadySummarized = store.forThreads(transmissionsByThread.keys).map { it.threadId }.toSet()
        val pending = transmissionsByThread.filterKeys { it !in alreadySummarized }

        return pending.map { (threadId, transmissions) ->
            val resolvedCallsigns = transmissions
                .filter {
                    it.attributionState == AttributionState.CONFIRMED ||
                        it.attributionState == AttributionState.INFERRED
                }
                .mapNotNull { it.stationId }
                .distinct()
                .mapNotNull { db.catalogDao().getStation(it)?.callsign }
                .toSet()
            val transcripts = transmissions.sortedBy { it.startedAtUtc }.mapNotNull { transmission ->
                db.transcriptDao().getCurrent(transmission.id)?.let {
                    TimestampedTranscript(transmission.startedAtUtc, it.text)
                }
            }
            PendingThreadDigest(
                threadId = threadId,
                input = ThreadDigestInput(threadId, resolvedCallsigns, transcripts),
                sourceTransmissionIds = transmissions.map { it.id },
            )
        }
    }
}
