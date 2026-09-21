package org.ort.pipeline.passb

import org.ort.data.OrtDatabase
import org.ort.data.entity.WorkQueueItemEntity
import org.ort.lexicon.ConversationInfo
import org.ort.lexicon.RankingContext
import org.ort.lexicon.RecencyInfo
import org.ort.pipeline.threading.RoomThreadRepository
import org.ort.pipeline.threading.ThreadRepository

/**
 * Register R-1124 (constitution I, FR-LEX-9, FR-LEX-25..27, FR-LEX-31): builds the
 * [RankingContext] the seven evidence priors actually read, once for every Pass B transcript that
 * parsed at least one candidate. Before this existed, [PassB] built no context at all —
 * [PassBFactory] wired the priors themselves (P33/R-1109) but nothing ever supplied them
 * anything to evaluate, so every one of them read [RankingContext]'s all-`null` default on every
 * real device capture.
 *
 * Deliberately called *after* [org.ort.lexicon.CallsignGrammar.parse] has produced this
 * transcript's candidates, not before: a database-presence or recency lookup can only be scoped
 * to the callsigns actually under consideration for *this* over, never to "every station this
 * device has ever heard" (`:data`'s `station` table has no query for that shape, and building one
 * would be `:data` schema work this unit does not own — see [DataRankingContextSource]'s own kdoc
 * on why a per-candidate lookup is the correct shape here regardless).
 *
 * `suspend`, not blocking (constitution IV): [DataRankingContextSource] performs real `:data`
 * reads through this call, but it runs inside [PassB]'s own Pass B processing lane — the
 * WorkQueue-driven pass that is already, structurally, off the capture path (`:capture-*` has no
 * compile-time dependency on `:pipeline`/`:lexicon` at all). A slow or stalled context source
 * therefore only ever delays *this* queued item's own processing, never the audio capture loop —
 * see `RankingContextAssemblyDoesNotBlockTest` for the discriminating proof.
 */
public fun interface RankingContextSource {
    /**
     * [candidateCallsigns] is every structurally valid candidate [org.ort.lexicon.CallsignGrammar]
     * parsed from this transcript — never empty (callers only reach this once parsing produced at
     * least one candidate); a lookup scoped to exactly these callsigns is both correct (the priors
     * only ever ask about a candidate in this set) and cheap (no full-catalog scan).
     */
    public suspend fun forCandidates(item: WorkQueueItemEntity, candidateCallsigns: Set<String>): RankingContext
}

/**
 * The real [RankingContextSource] (register R-1124). Reads exactly what is genuinely available
 * on a device today for three of the seven priors — [org.ort.lexicon.DatabasePresencePrior],
 * [org.ort.lexicon.RecencyPrior] (both from `:data`'s `station` table, via the same
 * [org.ort.data.dao.CatalogDao.getStation] every other real caller in this module already uses —
 * no new `:data` query) and [org.ort.lexicon.ConversationContextPrior] (from the thread the
 * current transmission's own frequency/channel continues, via [ThreadRepository] — the identical
 * seam [org.ort.pipeline.threading.ThreadGroupingCoordinator] already reads/writes through).
 *
 * **Left cold, deliberately, and why (constitution I: a plausible-looking value is worse than an
 * honest absence):**
 * - [org.ort.lexicon.FrequencyPrior] (`repeater`): needs a Rig Module repeater/memory-channel
 *   roster mapping a frequency to its expected callsigns. No such data exists anywhere on the
 *   device today — `RigDescriptor` (`:rig`) carries CAT transport/poll specs, not a repeater
 *   database, and FR-LEX-32's "imported WWARA repeater data" seed has not been built. Populating
 *   this from anything else (e.g. which callsigns this session has historically heard on this
 *   frequency) would silently redefine what the prior means — from "the Rig Module authoritatively
 *   expects these callsigns here" to "these callsigns happened to come up here before", a
 *   materially weaker and differently-shaped claim — so this stays `null` rather than guessed.
 * - [org.ort.lexicon.BandPlausibilityPrior] (`propagation`): [org.ort.lexicon.PropagationInputs]
 *   is all-or-nothing and requires `distanceKm` between the operator and the candidate's ITU
 *   allocation. No prefix/country-to-location centroid table exists anywhere in this codebase
 *   (`ItuPrefixTable` carries only prefix → country name/ISO code, no coordinates), so there is no
 *   real distance to compute — band/local-hour/season alone cannot substitute for it.
 * - [org.ort.lexicon.GeographicPrior] (`geographicDistanceKm`): the identical missing centroid
 *   table. [org.ort.data.entity.OperatorLocationEntity] is real, existing schema for the
 *   operator's *own* grid square, but nothing writes it in production yet (no settings screen
 *   exists for it — confirmed by search of `:app`), and even a populated one only gives one side
 *   of the distance calculation. Handing the prior a function that always returns `null` would
 *   look wired while contributing nothing on every call — the exact "wired but always cold"
 *   failure shape this row exists to fix, not repeat — so `geographicDistanceKm` stays `null`
 *   outright instead.
 * - [org.ort.lexicon.MyStationsPrior] (`myStations`, FR-LEX-14): "a user-editable my stations
 *   list" has no storage and no settings surface anywhere in `:app` or `:data` — confirmed by
 *   search. There is nothing to read.
 *
 * **A real, load-bearing caveat on the three priors this class does wire:** [org.ort.data.entity.StationEntity]
 * (the `station` table [databaseHits]/[recency] read) is never inserted by any production code
 * path today — only debug scenarios and tests seed one. And [ConversationInfo] depends on
 * [org.ort.data.entity.TransmissionEntity.stationId], which [org.ort.pipeline.passb.DataPassBResultSink]
 * only ever writes from [org.ort.core.Attribution.stationId] — `null` for every `AMBIGUOUS`/`UNKNOWN`
 * attribution, which is every attribution today without a calibrator (register R-1110). So on a
 * device running today's build, all three reads below will genuinely, structurally return "no
 * data" every time — not because this class is wrong, but because their upstream writers are
 * either entirely unbuilt (the station catalog) or gated by the same uncalibrated-resolver state
 * R-1110/R-1125 already describe. Wiring the *read* side now is still correct and necessary: it is
 * what makes each of the three "come alive" the moment its own writer exists, with no second
 * change to this class required.
 */
public class DataRankingContextSource(
    private val db: OrtDatabase,
    private val threadRepository: ThreadRepository = RoomThreadRepository(db),
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : RankingContextSource {

    override suspend fun forCandidates(item: WorkQueueItemEntity, candidateCallsigns: Set<String>): RankingContext {
        if (candidateCallsigns.isEmpty()) return RankingContext()

        val stations = candidateCallsigns.associateWith { db.catalogDao().getStation(it) }
        val databaseHits = stations.filterValues { it != null }.keys
        val now = clockMillis()
        val recency = stations.mapNotNull { (callsign, station) ->
            val lastHeardAt = station?.lastHeardAt ?: return@mapNotNull null
            callsign to RecencyInfo(secondsSinceLastHeard = ((now - lastHeardAt) / 1000L).coerceAtLeast(0L))
        }.toMap()

        return RankingContext(
            databaseHits = databaseHits,
            recency = recency,
            conversation = conversationFor(item),
        )
    }

    /**
     * Real, not cold, whenever this transmission's own row can be read at all (true for every
     * production over — segment-persist always writes the row before Pass B is ever queued for
     * it): "no other station identified yet" is genuine evidence
     * ([org.ort.lexicon.ConversationContextPrior] does not distinguish "false" from "cold" in its
     * contribution — both are `0f` — but the [org.ort.core.PriorContribution.coldStart] flag this
     * reports matters for [org.ort.lexicon.RankedCandidate.intervalWidening], so it must not be
     * fabricated as cold when a real answer exists).
     */
    private suspend fun conversationFor(item: WorkQueueItemEntity): ConversationInfo {
        val closure = threadRepository.closureFor(item.transmissionId)
            ?: return ConversationInfo(otherStationIdentified = false)
        val prior = threadRepository.priorContextFor(closure)
            ?: return ConversationInfo(otherStationIdentified = false)
        val thread = db.catalogDao().getThread(prior.threadId)
            ?: return ConversationInfo(otherStationIdentified = false)
        val other = thread.participantOrder?.firstOrNull() ?: thread.participantStationIds?.firstOrNull()
        return ConversationInfo(otherStationIdentified = other != null, otherStationCallsign = other)
    }
}
