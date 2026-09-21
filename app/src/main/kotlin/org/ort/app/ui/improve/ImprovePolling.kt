package org.ort.app.ui.improve

import android.content.Context
import org.ort.core.Tier
import org.ort.data.OrtDatabase
import org.ort.pipeline.capture.ShedStatus
import org.ort.pipeline.capture.ThermalStatus

/**
 * R-091/R-107 (register, P12, FR-REP-1..11): the real read path behind `Improve`. Groups by
 * [org.ort.data.entity.SessionEntity.deviceTier] — R-107's finding was that this field is written
 * (`"T1"` in the `field-tier1` scenario) and read nowhere; this is that reader.
 *
 * **R-1111 correction**: this KDoc used to claim `deviceTier` "is only ever non-null for a session
 * captured below full capability", describing `RealCaptureService.buildSessionEntity` writing
 * `null` for every full-capability session. That was never true in production —
 * `buildSessionEntity` wrote `null` *unconditionally*, for every session regardless of tier, which
 * made this entire reprocessing surface reachable only from seeded debug scenarios. Now fixed:
 * `buildSessionEntity` writes the device's real tier (`Tier.entries[tierFromShedLevel()].name`,
 * the identical `(MAX_TIER - ShedStatus.currentLevel)` formula this file's own
 * [currentTierOrdinal] and `ReprocessRunner.currentTierFromShedLevel()` already compute) for
 * every session, T3 (max) included — a T3 session still gets a real, non-null label, it simply
 * never becomes "qualifying" below because [root]'s own `tier.ordinal < currentTierOrdinal` check
 * requires strictly less. A session qualifying by tier is therefore real, never a guess — but a
 * session fact alone cannot say whether any *particular* transmission in it still needs improving
 * (see [root]'s own doc comment for the per-transmission half of that, R-1064).
 *
 * No reprocess/"Pass B/C" scheduling mechanism exists anywhere in `:pipeline` (no `WorkManager`,
 * no `CoroutineWorker`; grepped the whole tree before writing this) — [ImproveRunner]/
 * [FakeImproveRunner] in this package are the seam a real one would replace; see this package's
 * report for exactly what is missing.
 */
public object ImprovePolling {

    private const val MAX_TIER_ORDINAL: Int = 3

    /**
     * Register R-1064 (coordinator round, WPIMPROVE, FR-REP-2/FR-REP-9): [root] used to count and
     * list *every* transmission in a session whose [org.ort.data.entity.SessionEntity.deviceTier]
     * is below the current tier — real at the moment of capture, but permanent, since a session's
     * own `deviceTier` never changes. After a completed Improve run left all twelve of a session's
     * transmissions with `isReprocessCandidate = false` in the real database, this screen still
     * said "12 overs can get better" — stating something false (constitution I), not a harmless
     * stub, once a real reprocessing engine existed to make the claim checkable at all.
     *
     * **Why the fix reads [org.ort.data.dao.TransmissionDao.idsBelowProcessedTier], not
     * `isReprocessCandidate`:** `isReprocessCandidate` is never set `true` anywhere in production
     * code (grepped `:pipeline` and `:app` before writing this) — FR-TIER-4's own "mark affected
     * records as candidates" is not wired to it, and `spec/technical-design.md` §3.4 describes it as
     * a cache of a fingerprint-staleness computation ("no reprocess/Pass B/C scheduling mechanism
     * exists" above) that is not built either; the column's only production writer clears it to
     * `false` on completion ([ReprocessRunner]'s own `setReprocessCandidate(id, false)`). What the
     * real engine *does* stamp on every completed or rejected outcome is
     * [org.ort.data.entity.TransmissionEntity.processedTier] (FR-REP-2: "every stored result SHALL
     * record ... tier that produced it, so staleness is computable"; FR-REP-9: reprocess "candidates
     * at the current tier") — and `idsBelowProcessedTier` is the real, already-written read query
     * `:data` exposes for exactly this ("every transmission not yet processed at any tier at or
     * above the caller's target"). `org.ort.pipeline.reprocess.ReprocessRunner`'s own doc comment
     * names this precise gap outright: "`ImprovePolling`'s own grouping ... still does not subtract
     * out records this class has already brought current — that is WP11d's read-path change to make
     * with the two write/read primitives above" — this is that change.
     *
     * A transmission belonging to a tier-qualifying session is therefore only a real candidate
     * while it is `IN (idsBelowProcessedTier(belowTiers))` — never processed at all
     * (`processedTier IS NULL`, the ordinary state for anything the operator has not run Improve
     * over yet) or last processed strictly below the current tier. A session with none of its
     * transmissions left in that set contributes no group at all, and a completed run over every
     * qualifying transmission leaves the honest empty state (`totalOverCount == 0`), never a stale
     * "N overs can get better" once none remain.
     */
    public suspend fun root(context: Context): ImproveRootViewState {
        val db = OrtDatabase.create(context.applicationContext)
        val sessions = db.sessionDao().listAll()
        val currentTierOrdinal = currentTierOrdinal()
        val qualifying = sessions.mapNotNull { session ->
            val tier = session.deviceTier?.let { runCatching { Tier.valueOf(it) }.getOrNull() }
            if (tier != null && tier.ordinal < currentTierOrdinal) session to tier else null
        }
        val belowTiers = Tier.entries.filter { it.ordinal < currentTierOrdinal }
        val stillOutstanding = if (belowTiers.isEmpty()) {
            emptySet()
        } else {
            db.transmissionDao().idsBelowProcessedTier(belowTiers).toSet()
        }
        val groups = qualifying.groupBy { it.second }
            .entries
            .sortedBy { it.key.ordinal }
            .mapNotNull { (tier, entries) ->
                val perSessionIds = entries.map { (session, _) ->
                    db.transmissionDao().listBySession(session.id).map { tx -> tx.id }.filter { it in stillOutstanding }
                }
                val txIds = perSessionIds.flatten()
                if (txIds.isEmpty()) return@mapNotNull null
                val contributingSessions = perSessionIds.count { it.isNotEmpty() }
                ImproveGroupViewState(
                    id = "tier-${tier.name}",
                    // R-141: the board's "Captured at tier 1 on the field phone" names a specific
                    // device this build has no field for (`SessionEntity` carries no device-name
                    // column) — inventing one would fabricate a fact (constitution I), so this
                    // states the one real thing the row has: the tier number itself.
                    headline = "Captured at tier ${tier.ordinal}",
                    subLine = "${Plurals.count(contributingSessions, "session")} · " +
                        Plurals.count(txIds.size, "over"),
                    overCount = txIds.size,
                    transmissionIds = txIds,
                    tierOrdinal = tier.ordinal,
                )
            }
        val allQualifyingIds = groups.flatMap { it.transmissionIds }.distinct()
        var everyOver = 0
        sessions.forEach { everyOver += db.transmissionDao().listBySession(it.id).size }
        return ImproveRootViewState(
            totalOverCount = allQualifyingIds.size,
            allTransmissionIds = allQualifyingIds,
            // R-445 (register, Reviewer D): was "T$currentTierOrdinal" — a raw token ("tier T3"
            // once prefixed at either call site below), not the shared "tier N" label this exact
            // file's own `headline` above ("Captured at tier ${tier.ordinal}") already uses.
            currentTierLabel = "$currentTierOrdinal",
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
