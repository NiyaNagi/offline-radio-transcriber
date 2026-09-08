package org.ort.app.ui.improve

import android.content.Context
import org.ort.core.Tier
import org.ort.data.OrtDatabase
import org.ort.pipeline.capture.ShedStatus
import org.ort.pipeline.capture.ThermalStatus

/**
 * R-091/R-107 (register, P12, FR-REP-1..11): the real read path behind `Improve`. Groups by
 * [org.ort.data.entity.SessionEntity.deviceTier] — R-107's finding was that this field is written
 * (`"T1"` in the `field-tier1` scenario) and read nowhere; this is that reader. `deviceTier` is
 * only ever non-null for a session captured below full capability (see
 * `RealCaptureService.runCaptureFlow`, which writes `null` for every full-capability session), so
 * every session this groups is, by definition, a real reprocessing candidate — never a guess.
 *
 * No reprocess/"Pass B/C" scheduling mechanism exists anywhere in `:pipeline` (no `WorkManager`,
 * no `CoroutineWorker`; grepped the whole tree before writing this) — [ImproveRunner]/
 * [FakeImproveRunner] in this package are the seam a real one would replace; see this package's
 * report for exactly what is missing.
 */
public object ImprovePolling {

    private const val MAX_TIER_ORDINAL: Int = 3

    public suspend fun root(context: Context): ImproveRootViewState {
        val db = OrtDatabase.create(context.applicationContext)
        val sessions = db.sessionDao().listAll()
        val currentTierOrdinal = currentTierOrdinal()
        val qualifying = sessions.mapNotNull { session ->
            val tier = session.deviceTier?.let { runCatching { Tier.valueOf(it) }.getOrNull() }
            if (tier != null && tier.ordinal < currentTierOrdinal) session to tier else null
        }
        val groups = qualifying.groupBy { it.second }
            .entries
            .sortedBy { it.key.ordinal }
            .map { (tier, entries) ->
                val txIds = entries.flatMap { (session, _) ->
                    db.transmissionDao().listBySession(session.id).map { tx -> tx.id }
                }
                ImproveGroupViewState(
                    id = "tier-${tier.name}",
                    headline = "Captured at ${tier.name}",
                    subLine = "${entries.size} session(s) · ${txIds.size} overs",
                    overCount = txIds.size,
                    transmissionIds = txIds,
                )
            }
        val allQualifyingIds = groups.flatMap { it.transmissionIds }.distinct()
        var everyOver = 0
        sessions.forEach { everyOver += db.transmissionDao().listBySession(it.id).size }
        return ImproveRootViewState(
            totalOverCount = allQualifyingIds.size,
            allTransmissionIds = allQualifyingIds,
            currentTierLabel = "T$currentTierOrdinal",
            groups = groups,
            everythingElseCount = (everyOver - allQualifyingIds.size).coerceAtLeast(0),
        )
    }

    public suspend fun select(context: Context, group: ImproveGroupViewState): ImproveSelectViewState {
        val db = OrtDatabase.create(context.applicationContext)
        var totalDurationMs = 0L
        var corrections = 0
        group.transmissionIds.forEach { id ->
            totalDurationMs += db.transmissionDao().getById(id)?.durationMs ?: 0L
            corrections += db.correctionDao().correctionsFor(id).size
        }
        val rtf = ThermalStatus.state.realTimeFactor
        val estimatedSeconds = rtf?.let { (totalDurationMs / 1000.0 * it).toLong() }
        return ImproveSelectViewState(
            group = group,
            estimatedSeconds = estimatedSeconds,
            correctionsToReapply = corrections,
        )
    }

    private fun currentTierOrdinal(): Int = (MAX_TIER_ORDINAL - ShedStatus.currentLevel).coerceIn(0, MAX_TIER_ORDINAL)
}
