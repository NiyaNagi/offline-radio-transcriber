package org.ort.app.ui.data

import android.content.Context
import org.ort.core.Attribution
import org.ort.core.AttributionState
import org.ort.core.SystemClock
import org.ort.core.Ulid
import org.ort.data.OrtDatabase
import org.ort.data.dao.StationIdentityDao
import org.ort.data.dao.VoiceprintSplitMember
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.StationEntity
import org.ort.data.entity.StationIdentityHistoryEntity
import org.ort.data.entity.TransmissionEntity
import org.ort.data.entity.VoiceprintEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * WP8's own read path (ui-conformance-plan), lifted from `ReaderPolling.stationDetail` /
 * `ReaderPolling.frequencyDetail` / `ReaderPolling.listStationSummaries` /
 * `ReaderPolling.listFrequencySummaries` (WP4's file, not edited here — its own copies stay until
 * WP4 deletes them once `OrtNavHost` is wired to this package's `*Content` composables instead)
 * plus every new aggregation R-070 through R-075 need: tonight's per-station/per-frequency split,
 * the hour x day grid, the 14-night sparkline, the "busier than usual" test, and R-075's local-zone
 * bucketing (`ActivityPatternMapper.buildPattern`/`buildHourByDayPattern`/`buildNightlySequence`
 * always called here with `zone = ZoneId.systemDefault()`, never the UTC default `ReaderPolling`'s
 * copy is stuck with).
 *
 * [ReaderPolling.detailFromEntity] is reused for the transmission-detail mapping — the same
 * pattern [org.ort.app.ui.data.SearchPolling]/[org.ort.app.ui.data.ThreadPolling] already use, so
 * a transmission cannot show different facts on two screens.
 */

/**
 * One [OrtDatabase] instance for this package's own read path (R-272, register, halt, V5 pass 2
 * @8d1456f): every function in [StationPolling]/[FrequencyPolling] used to call
 * `OrtDatabase.create` fresh on every single poll — a brand new Room instance, and therefore a
 * brand new `SQLiteConnectionPool`, never explicitly closed. The halt this produced:
 * `SplitSubScreen`'s own `LaunchedEffect` re-firing while `voiceSplitCandidates` stayed `null`
 * opened a fresh pool on every recomposition, flooding logcat with `SQLiteConnectionPool leaked`
 * warnings. `OrtDatabase.create` is deliberately not memoized inside `:data` itself (other call
 * sites there, including tests, legitimately want a fresh instance) — this cache is process-wide
 * in production but scoped to this package's own two read-path objects, not a `:data`-level
 * change. Keyed by `context.applicationContext` identity, not just "has one been created" —
 * Robolectric hands each test method a fresh `Application`, and reusing a previous test's instance
 * across that boundary would silently read/write the wrong (disposed) database.
 *
 * **Root-cause fix (idle-root task, 2026-09-10): also checks [RoomDatabase.isOpen], not just
 * context identity, before handing back the cached instance.** `OrtDatabase.create`'s own
 * path-keyed cache (`data/src/main/kotlin/org/ort/data/OrtDatabase.kt`) already makes this check —
 * this second, package-local cache did not, so a test that closes its own `OrtDatabase` in
 * `@After` (several `ui/screens` Compose tests do, to stop leaking a writer across the shared
 * on-disk `ort.db` — see those files' own doc comments) could leave this object holding the *same*
 * now-closed instance under the *same* `Application` identity (Robolectric does not always mint a
 * new `Application` for a class that mixes fixture setup across test methods the way a plain
 * `@Test` boundary implies), and a later call in that same JVM fork would hand back a database no
 * caller can use. Falling through to a fresh [OrtDatabase.create] call when the cached instance is
 * no longer open is exactly [OrtDatabase.create]'s own eviction rule, applied here too so this
 * cache can never diverge from it.
 */
private object SharedDatabase {
    @Volatile
    private var cachedContext: Context? = null

    @Volatile
    private var instance: OrtDatabase? = null

    fun get(context: Context): OrtDatabase {
        val appContext = context.applicationContext
        instance?.let { if (cachedContext === appContext && it.isOpen) return it }
        synchronized(this) {
            instance?.let { if (cachedContext === appContext && it.isOpen) return it }
            return OrtDatabase.create(appContext).also {
                instance = it
                cachedContext = appContext
            }
        }
    }
}

public object StationPolling {

    /** The "Stations" list (R-070): every station ever heard, most recently heard first. */
    public suspend fun listStations(context: Context): List<StationListEntryViewState> {
        val db = SharedDatabase.get(context)
        val stations = db.activityDao().listStations()
        val latest = latestSession(db)
        return stations.map { station -> stationRow(db, station, latest) }
    }

    /**
     * R-070's trailing "N unidentified voices · M overs" row. [tonightOnly] `true` scopes to the
     * most recent session (the "Tonight" chip); `false` scopes to every session ever recorded (the
     * "All time" chip) — there is no single `:data` query for "every transmission ever recorded"
     * (deliberately: `TransmissionDao`/`ActivityDao` are both station/frequency/session-scoped), so
     * this folds over every session's own transmissions rather than adding one.
     */
    public suspend fun unidentifiedSummary(context: Context, tonightOnly: Boolean): UnidentifiedVoicesSummary? {
        val db = SharedDatabase.get(context)
        val transmissions = if (tonightOnly) {
            val latest = latestSession(db) ?: return null
            db.transmissionDao().listBySession(latest.id)
        } else {
            db.sessionDao().listAll().flatMap { db.transmissionDao().listBySession(it.id) }
        }
        val unknown = transmissions.filter { it.attributionState == AttributionState.UNKNOWN }
        if (unknown.isEmpty()) return null
        val distinctVoices = unknown.mapNotNull { it.voiceprintId }.toSet()
        return UnidentifiedVoicesSummary(
            voiceCount = if (distinctVoices.isNotEmpty()) distinctVoices.size else null,
            overCount = unknown.size,
        )
    }

    /**
     * Everything heard from [stationId], across every session (FR-UI-9), plus the R-071 facts
     * table figures and its activity pattern (FR-UI-11, in the device's own zone — R-075).
     */
    public suspend fun stationDetail(context: Context, stationId: String, nowMillis: Long): StationDetailViewState {
        val db = SharedDatabase.get(context)
        val zone = ZoneId.systemDefault()
        val entities = db.activityDao().transmissionsForStation(stationId)
        val details = entities.map { ReaderPolling.detailFromEntity(context, it) }
        val timestamps = entities.map { it.startedAtUtc }
        val sessions = everySessionWindow(db)
        val pattern = ActivityPatternMapper.buildPattern(sessions, timestamps, nowMillis, zone)
        val dayOfWeekPattern = ActivityPatternMapper.buildDayOfWeekPattern(sessions, timestamps, nowMillis, zone)
        val weekOverWeek = ActivityPatternMapper.buildWeekOverWeekComparison(sessions, timestamps, nowMillis, zone)
        val station = db.catalogDao().getStation(stationId)
        val label = station?.callsign ?: stationId

        val latest = latestSession(db)
        val tonightCount = latest?.let { s -> entities.count { it.sessionId == s.id } } ?: 0
        val confirmed = entities.count { it.attributionState == AttributionState.CONFIRMED }
        val inferred = entities.count { it.attributionState == AttributionState.INFERRED }
        val corrected = entities.count { it.corrected }
        val frequencyCounts = entities.mapNotNull { it.frequencyHz }
            .groupingBy { it }.eachCount().entries.map { it.key to it.value }
        val lastEntity = entities.maxByOrNull { it.startedAtUtc }

        // R-208 (register, spec, V5 @f8430b8): the same real, all-time dominant state R-206 put on
        // the Stations list row belongs beside this station's own title too — a station's detail
        // screen naming Unknown while its own facts table shows 10/10 CONFIRMED was the same defect
        // in a second place.
        val nightsHeard = entities.map { it.sessionId }.toSet().size
        val totalNights = sessions.size
        val contextSentence = if (totalNights > 0) {
            "Heard ${pluralize(nightsHeard, "night")} of $totalNights"
        } else {
            ""
        }

        val base = StationViewMapper.detail(stationId, label, details, pattern, dayOfWeekPattern, weekOverWeek)
        return base.copy(
            givenName = station?.userName,
            attribution = dominantAttribution(stationId, entities),
            contextSentence = contextSentence,
            transmissionCountTonight = tonightCount,
            confirmedCount = confirmed,
            inferredCount = inferred,
            correctedCount = corrected,
            frequenciesSummary = StationViewMapper.frequencySummary(frequencyCounts),
            firstHeardLabel = station?.firstHeardAt?.let { LOCAL_DATETIME_FORMAT.format(Instant.ofEpochMilli(it)) },
            lastHeardLabel = station?.lastHeardAt?.let { LOCAL_DATETIME_FORMAT.format(Instant.ofEpochMilli(it)) },
            lastHeardSignalLabel = lastEntity?.signalStrength?.let { "S%.0f".format(Locale.ROOT, it) },
        )
    }

    /** The real dominant attribution across every [entities] this station has ever produced
     * (R-206/R-208) — shared by [stationDetail] and [stationRow] so the list row and the detail
     * screen it opens can never disagree about the same station's own state. */
    private fun dominantAttribution(stationId: String, entities: List<TransmissionEntity>): Attribution {
        val confirmed = entities.count { it.attributionState == AttributionState.CONFIRMED }
        val inferred = entities.count { it.attributionState == AttributionState.INFERRED }
        val ambiguous = entities.count { it.attributionState == AttributionState.AMBIGUOUS }
        val dominant = when {
            confirmed > 0 -> AttributionState.CONFIRMED
            inferred > 0 -> AttributionState.INFERRED
            ambiguous > 0 -> AttributionState.AMBIGUOUS
            else -> AttributionState.UNKNOWN
        }
        val bestConfidence = entities.filter { it.attributionState == dominant }
            .mapNotNull { it.attributionConfidence }.maxOrNull()
        return when (dominant) {
            AttributionState.CONFIRMED -> Attribution.confirmed(stationId, bestConfidence ?: 0.0)
            AttributionState.INFERRED -> Attribution.inferred(stationId, bestConfidence ?: 0.0)
            AttributionState.AMBIGUOUS -> Attribution.ambiguous()
            AttributionState.UNKNOWN -> Attribution.unknown()
        }
    }

    /** `Station-Pattern.dc.html`'s state (R-072, R-075) — every bucket is local-time. */
    public suspend fun stationPattern(context: Context, stationId: String, nowMillis: Long): StationPatternViewState {
        val db = SharedDatabase.get(context)
        val zone = ZoneId.systemDefault()
        val entities = db.activityDao().transmissionsForStation(stationId)
        val timestamps = entities.map { it.startedAtUtc }
        val sessions = everySessionWindow(db)
        val hourPattern = ActivityPatternMapper.buildPattern(sessions, timestamps, nowMillis, zone)
        val dayOfWeekPattern = ActivityPatternMapper.buildDayOfWeekPattern(sessions, timestamps, nowMillis, zone)
        val hourByDay = ActivityPatternMapper.buildHourByDayPattern(sessions, timestamps, nowMillis, zone)
        val weekOverWeek = ActivityPatternMapper.buildWeekOverWeekComparison(sessions, timestamps, nowMillis, zone)
        val label = db.catalogDao().getStation(stationId)?.callsign ?: stationId
        return StationPatternViewState(
            subjectId = stationId,
            label = label,
            hourPattern = hourPattern,
            hourByDay = hourByDay,
            weekOverWeekSummary = weekOverWeekSummaryText(weekOverWeek),
            whatThisSays = PatternInsights.build(hourPattern, dayOfWeekPattern),
            nightsSubtitle = nightsSubtitle(sessions, zone),
        )
    }

    /** `Station-Pattern.dc.html`'s own subtitle (R-210) — "14 nights of listening, 25 Aug – 7 Sep",
     * never "Local time" (which named the zone, not the fact an operator actually wants here). The
     * device's own locale, never [Locale.ROOT] — R-170 (register) found `Locale.ROOT` with `MMM`
     * renders a raw numeric month ("M09") instead of a real month name on this JVM. */
    private fun nightsSubtitle(sessions: List<SessionWindow>, zone: ZoneId): String {
        val nights = sessions.map { Instant.ofEpochMilli(it.startedAtUtc).atZone(zone).toLocalDate() }
        if (nights.isEmpty()) return "Not enough listening yet"
        val earliest = nights.min()
        val latest = nights.max()
        val format = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
        return "${pluralize(nights.toSet().size, "night")} of listening, ${format.format(earliest)} – " +
            format.format(latest)
    }

    /**
     * `Station-Identity.dc.html`'s state (R-073). [StationVoiceViewState.nearestOtherStationId] is
     * always `null` today — cross-station voice-distance comparison needs the identity pipeline
     * (M4) to have written comparable embeddings, which it does not yet do (see this package's
     * report); rendered honestly absent rather than computed from an undocumented byte layout.
     * "Given by you" reads the *latest* [org.ort.data.dao.StationIdentityDao] history row for each
     * field — the same source [renameStation]/[updateStationNote] write to and [stationRow] reads
     * for the Stations list, so a rename shows up identically in both places.
     */
    public suspend fun stationIdentity(context: Context, stationId: String): StationIdentityViewState {
        val db = SharedDatabase.get(context)
        val station = db.catalogDao().getStation(stationId)
        val entities = db.activityDao().transmissionsForStation(stationId)
        val confirmed = entities.count { it.attributionState == AttributionState.CONFIRMED }
        val inferred = entities.count { it.attributionState == AttributionState.INFERRED }
        // R-213 (register, spec, V5 @f8430b8): the cluster total must equal the confirmed/inferred
        // breakdown shown right beneath it — a `voiceprintsForStation().sumOf { memberCount }`
        // total once diverged from it (voice-cluster membership, M4's own pipeline, can lag or omit
        // overs the attribution pipeline already resolved), which read as two different, provably
        // inconsistent counts of the same "how many overs" fact on one screen.
        val clusterOvers = confirmed + inferred
        val (name, note) = currentGivenByYou(db, stationId, station?.userName, station?.notes)
        // R-572 (register, polish): the real first-seen date of the *current* bound cluster — the
        // same cluster [voiceSplitCandidates] would split, found the same way (largest voiceprint
        // bound to this station). `null` — the sub-line then omits the clause — when no voiceprint
        // is bound, or its member overs have no timestamp this package can read (should not happen
        // in practice, but never a fabricated date standing in for one).
        val boundCluster = db.catalogDao().voiceprintsForStation(stationId).maxByOrNull { it.memberCount }
        val stableSinceLabel = boundCluster?.let { cluster ->
            entities.filter { it.voiceprintId == cluster.id }.minOfOrNull { it.startedAtUtc }
        }?.let { earliestUtc -> STABLE_SINCE_FORMAT.format(Instant.ofEpochMilli(earliestUtc)) }
        return StationIdentityViewState(
            stationId = stationId,
            callsign = station?.callsign ?: stationId,
            heardOverCount = confirmed,
            lexiconLabel = station?.ituRegionFromPrefix,
            voice = StationVoiceViewState(
                clusterOverCount = clusterOvers,
                confirmedCount = confirmed,
                inferredCount = inferred,
                stableSinceLabel = stableSinceLabel,
            ),
            givenByYou = StationGivenByYouViewState(name = name, note = note),
        )
    }

    /**
     * Renames [stationId] (R-073, FR-SPK-25 — user-supplied, never inferred, never contributed).
     * Records the real previous value from history before writing, so
     * [org.ort.data.dao.StationIdentityDao.stationIdentityHistoryFor] keeps it reachable
     * (constitution III) even though `station.userName` only ever holds the current one.
     */
    public suspend fun renameStation(context: Context, stationId: String, name: String?) {
        val db = SharedDatabase.get(context)
        val station = db.catalogDao().getStation(stationId)
        val (previousName, _) = currentGivenByYou(db, stationId, station?.userName, station?.notes)
        db.stationIdentityDao().renameStation(
            StationIdentityHistoryEntity(
                id = Ulid.generate().toString(),
                stationId = stationId,
                field = StationIdentityDao.FIELD_NAME,
                previousValue = previousName,
                newValue = name,
                changedAt = SystemClock.wallMillis(),
            ),
        )
    }

    /** Adds or edits [stationId]'s note (R-073), versioned the same way as [renameStation]. */
    public suspend fun updateStationNote(context: Context, stationId: String, note: String?) {
        val db = SharedDatabase.get(context)
        val station = db.catalogDao().getStation(stationId)
        val (_, previousNote) = currentGivenByYou(db, stationId, station?.userName, station?.notes)
        db.stationIdentityDao().updateStationNote(
            StationIdentityHistoryEntity(
                id = Ulid.generate().toString(),
                stationId = stationId,
                field = StationIdentityDao.FIELD_NOTE,
                previousValue = previousNote,
                newValue = note,
                changedAt = SystemClock.wallMillis(),
            ),
        )
    }

    /**
     * `Fail-Cluster.dc.html`'s chooser (R-073): the real overs in [stationId]'s largest voiceprint
     * cluster. `null` when the station has no voiceprint bound at all — there is nothing to split.
     * No pre-ticked suggestion: the artboard's "matcher thinks these are least like the rest" needs
     * a per-over voiceprint-distance score this package does not compute (see this package's
     * report) — never a fabricated suggestion standing in for one.
     */
    public suspend fun voiceSplitCandidates(context: Context, stationId: String): StationVoiceSplitViewState? {
        val db = SharedDatabase.get(context)
        val station = db.catalogDao().getStation(stationId)
        val cluster = db.catalogDao().voiceprintsForStation(stationId).maxByOrNull { it.memberCount } ?: return null
        val entities = db.activityDao().transmissionsForStation(stationId)
            .filter { it.voiceprintId == cluster.id }
            .sortedByDescending { it.startedAtUtc }
        if (entities.isEmpty()) return null
        val overs = entities.map { entity ->
            val detail = ReaderPolling.detailFromEntity(context, entity)
            val listEntry = ReaderTransmissionViewStateMapper.listEntry(detail)
            VoiceprintSplitOverViewState(
                transmissionId = entity.id,
                timeLabel = listEntry.timeLabel,
                transcriptText = listEntry.transcriptText,
                attribution = detail.attribution,
                isAnchor = detail.attribution.state == AttributionState.CONFIRMED,
                corrected = entity.corrected,
            )
        }
        return StationVoiceSplitViewState(
            stationId = stationId,
            callsign = station?.callsign ?: stationId,
            fromVoiceprintId = cluster.id,
            overs = overs,
        )
    }

    /**
     * Splits [transmissionIds] away from [fromVoiceprintId] into a new, unbound voiceprint
     * (R-073) — `Station-Identity.dc.html`'s own copy: "they become a new unidentified voice.
     * Every affected over is marked corrected and its old attribution kept." Returns the station's
     * refreshed identity so a caller can re-render without a second round trip. A no-op (identity
     * unchanged) when [transmissionIds] is empty — never a split of nothing.
     */
    public suspend fun splitVoiceprint(
        context: Context,
        stationId: String,
        fromVoiceprintId: String,
        transmissionIds: List<String>,
    ): StationIdentityViewState {
        if (transmissionIds.isNotEmpty()) {
            val db = SharedDatabase.get(context)
            val newVoiceprintId = Ulid.generate().toString()
            db.catalogDao().insert(
                VoiceprintEntity(
                    id = newVoiceprintId,
                    embedding = ByteArray(0),
                    memberCount = 0,
                    centroidUpdatedAt = null,
                    boundStationId = null,
                    bindingConfidence = null,
                    lastConfirmedAt = null,
                    isEnrolled = false,
                    enrolmentObservationCount = 0,
                    enrolmentSessionIds = null,
                    enrolledAt = null,
                    lastMatchedAt = null,
                    bindingSource = null,
                    embeddingModelId = null,
                    embeddingModelVersion = null,
                ),
            )
            val members = transmissionIds.map { VoiceprintSplitMember(it, Ulid.generate().toString()) }
            db.stationIdentityDao().splitVoiceprint(
                fromVoiceprintId = fromVoiceprintId,
                intoVoiceprintId = newVoiceprintId,
                members = members,
                splitAt = SystemClock.wallMillis(),
            )
        }
        return stationIdentity(context, stationId)
    }

    /**
     * The current given name/note, from the *latest* [org.ort.data.dao.StationIdentityDao] history
     * row for each field — [fallbackName]/[fallbackNote] (the plain `station` columns) cover a
     * station renamed before this history table existed, which real fixture/legacy data can still
     * be.
     */
    private suspend fun currentGivenByYou(
        db: OrtDatabase,
        stationId: String,
        fallbackName: String?,
        fallbackNote: String?,
    ): Pair<String?, String?> {
        val history = db.stationIdentityDao().stationIdentityHistoryFor(stationId)
        val name = history.lastOrNull { it.field == StationIdentityDao.FIELD_NAME }?.newValue ?: fallbackName
        val note = history.lastOrNull { it.field == StationIdentityDao.FIELD_NOTE }?.newValue ?: fallbackNote
        return name to note
    }

    /**
     * R-206 (register, halt, V5 @f8430b8): this row's marker and count context are the station's
     * **real dominant state across every night it has ever been heard**, not just the most recent
     * session — the earlier shape here computed both only from tonight's transmissions and fell
     * back to a bare, unearned [Attribution.unknown] for every station not heard in the *single*
     * most recent session, which is most of a 14-night "All time" list on any given night. A
     * station's own detail screen ([stationDetail]) already reads its full history; this row must
     * agree with it, not contradict it with a fabricated Unknown.
     */
    private suspend fun stationRow(
        db: OrtDatabase,
        station: StationEntity,
        latest: SessionEntity?,
    ): StationListEntryViewState {
        val (givenName, _) = currentGivenByYou(db, station.id, station.userName, station.notes)
        val base = StationViewMapper.listEntry(station).copy(givenName = givenName)
        val allTx = db.activityDao().transmissionsForStation(station.id)
        if (allTx.isEmpty()) return base

        val confirmed = allTx.count { it.attributionState == AttributionState.CONFIRMED }
        val inferred = allTx.count { it.attributionState == AttributionState.INFERRED }
        val corrected = allTx.any { it.corrected }
        val attribution = dominantAttribution(station.id, allTx)
        val firstHeardAt = station.firstHeardAt
        val isNew = firstHeardAt != null && latest != null && firstHeardAt >= latest.startedAt
        val badge = when {
            isNew -> StationListBadge.NEW
            corrected -> StationListBadge.CORRECTED
            else -> null
        }
        val heardTonight = latest != null && allTx.any { it.sessionId == latest.id }
        return base.copy(
            attribution = attribution,
            countContext = StationViewMapper.countContext(confirmed, inferred),
            badge = badge,
            heardTonight = heardTonight,
        )
    }

    private suspend fun latestSession(db: OrtDatabase): SessionEntity? = db.sessionDao().listAll().firstOrNull()

    /**
     * The [SessionWindow] for **every session ever recorded**, not only the ones a station or
     * frequency happened to be heard in (FR-UI-12) — identical reasoning to
     * `ReaderPolling.activityPatternForEverySession`'s own doc comment.
     */
    private suspend fun everySessionWindow(db: OrtDatabase): List<SessionWindow> =
        db.sessionDao().listAll().map { session ->
            val gaps = db.captureGapDao().listBySession(session.id).map { gap ->
                GapWindow(startedAt = gap.startedAt, endedAt = gap.endedAt)
            }
            SessionWindow(startedAtUtc = session.startedAt, endedAtUtc = session.endedAt, gaps = gaps)
        }
}

/**
 * `Frequencies.dc.html` / `Frequency.dc.html` / `Frequency-Change.dc.html`'s read path (R-074),
 * companion to [StationPolling] in the same file per this package's file ownership.
 */
public object FrequencyPolling {

    /**
     * The "Frequencies" list (R-074): every frequency ever recorded, with tonight's split and a
     * 14-night sparkline. [nowMillis] defaults to the real clock in production; tests pass a fixed
     * value so a fixture's 14-night window is anchored where the fixture data actually lives.
     */
    public suspend fun listFrequencies(
        context: Context,
        nowMillis: Long = SystemClock.wallMillis(),
    ): List<FrequencyListEntryViewState> {
        val db = SharedDatabase.get(context)
        val zone = ZoneId.systemDefault()
        val latestSession = db.sessionDao().listAll().firstOrNull()
        val sessions = everySessionWindow(db)
        return db.activityDao().listDistinctFrequencies().map { hz ->
            val entities = db.activityDao().transmissionsForFrequency(hz)
            val timestamps = entities.map { it.startedAtUtc }
            val nights = ActivityPatternMapper.buildNightlySequence(sessions, timestamps, nowMillis, 14, zone)
            val tonightTx = latestSession?.let { s -> entities.filter { it.sessionId == s.id } }.orEmpty()
            FrequencyViewMapper.listEntry(hz, entities.size).copy(
                whatItIs = FrequencyViewMapper.whatItIs(hz, entities.mapNotNull { it.mode }),
                tonightCount = tonightTx.size,
                tonightStationCount = tonightTx.mapNotNull { it.stationId }.toSet().size,
                nights = nights.map { it.state },
                busierThanUsual = NightlyDeparture.isBusierThanUsual(nights),
            )
        }
    }

    /** Everything heard on [frequencyHz], across every session (R-074), plus its typical-night pattern and regulars. */
    public suspend fun frequencyDetail(context: Context, frequencyHz: Long, nowMillis: Long): FrequencyDetailViewState {
        val db = SharedDatabase.get(context)
        val zone = ZoneId.systemDefault()
        val entities = db.activityDao().transmissionsForFrequency(frequencyHz)
        val details = entities.map { ReaderPolling.detailFromEntity(context, it) }
        val timestamps = entities.map { it.startedAtUtc }
        val sessions = everySessionWindow(db)
        val pattern = ActivityPatternMapper.buildPattern(sessions, timestamps, nowMillis, zone)
        val dayOfWeekPattern = ActivityPatternMapper.buildDayOfWeekPattern(sessions, timestamps, nowMillis, zone)
        val weekOverWeek = ActivityPatternMapper.buildWeekOverWeekComparison(sessions, timestamps, nowMillis, zone)
        val nights = ActivityPatternMapper.buildNightlySequence(sessions, timestamps, nowMillis, 14, zone)

        val latestSession = db.sessionDao().listAll().firstOrNull()
        val tonightTx = latestSession?.let { s -> entities.filter { it.sessionId == s.id } }.orEmpty()
        val byStation = entities.filter { it.stationId != null }.groupBy { it.stationId!! }
        val regulars = byStation
            .map { (stationId, tx) -> regularRow(db, stationId, tx) }
            .sortedByDescending { row -> byStation.getValue(row.stationId).size }

        val base = FrequencyViewMapper.detail(frequencyHz, details, pattern, dayOfWeekPattern, weekOverWeek)
        return base.copy(
            whatItIs = FrequencyViewMapper.whatItIs(frequencyHz, entities.mapNotNull { it.mode }),
            transmissionCountTonight = tonightTx.size,
            stationCountAllTime = entities.mapNotNull { it.stationId }.toSet().size,
            stationCountTonight = tonightTx.mapNotNull { it.stationId }.toSet().size,
            regulars = regulars,
            nights = nights.map { it.state },
            busierThanUsual = NightlyDeparture.isBusierThanUsual(nights),
            listenedLabel = listenedLabel(sessions, nowMillis, zone),
            // R-431 (register, design): the real distinct-night count `pattern` above was
            // averaged over — the same session set `listenedLabel`'s own "N nights of N" already
            // counts, so the two can never disagree.
            patternNightsCount = sessions.map { Instant.ofEpochMilli(it.startedAtUtc).atZone(zone).toLocalDate() }
                .toSet().size,
            net = netFor(entities, zone),
        )
    }

    /**
     * `Listened` (R-074, R-216): every configured session listens on every configured rig band at
     * once (`AGENTS.md`'s own "the TH-D75A receives on two bands at once" note), so a frequency's
     * own "nights listened" is simply every night a session ever ran — never a per-frequency
     * sub-count this package has no column to derive honestly.
     */
    private fun listenedLabel(sessions: List<SessionWindow>, nowMillis: Long, zone: ZoneId): String {
        if (sessions.isEmpty()) return ""
        val nights = sessions.map { Instant.ofEpochMilli(it.startedAtUtc).atZone(zone).toLocalDate() }.toSet().size
        val totalHours = sessions.sumOf { session ->
            val end = session.endedAtUtc ?: nowMillis
            if (end <= session.startedAtUtc) return@sumOf 0L
            val gapMillis = session.gaps.sumOf { (it.endedAt ?: end) - it.startedAt }
            (end - session.startedAtUtc - gapMillis).coerceAtLeast(0L)
        } / 3_600_000.0
        return "${pluralize(nights, "night")} of $nights · %.0f h total".format(Locale.ROOT, totalHours)
    }

    /**
     * `Nets` (R-074, R-216): a recurring weekly (day-of-week, local hour) slot with real activity
     * in at least half of the calendar weeks this frequency has ever been heard on — [FrequencyNetViewState]'s
     * own doc comment names the heuristic's limits. `null`, never a fabricated net, with fewer than
     * two distinct weeks of data or no slot meeting that bar.
     */
    private fun netFor(entities: List<TransmissionEntity>, zone: ZoneId): FrequencyNetViewState? {
        if (entities.isEmpty()) return null
        fun epochWeek(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay() / 7
        val totalWeeks = entities.map { epochWeek(it.startedAtUtc) }.toSet().size
        if (totalWeeks < 2) return null
        val bySlot = entities.groupBy { entity ->
            val zdt = Instant.ofEpochMilli(entity.startedAtUtc).atZone(zone)
            zdt.dayOfWeek to zdt.hour
        }
        val minWeeks = kotlin.math.ceil(totalWeeks / 2.0).toInt().coerceAtLeast(2)
        return bySlot.entries
            .mapNotNull { (slot, tx) ->
                val weeksSeen = tx.map { epochWeek(it.startedAtUtc) }.toSet().size
                if (weeksSeen < minWeeks) return@mapNotNull null
                val control = tx.filter { it.attributionState == AttributionState.CONFIRMED && it.stationId != null }
                    .groupingBy { it.stationId!! }.eachCount().maxByOrNull { it.value }?.key
                FrequencyNetViewState(slot.first, slot.second, control, weeksSeen, totalWeeks)
            }
            .maxByOrNull { it.weeksSeen }
    }

    /**
     * `Frequency-Change.dc.html`'s state (R-074) — only built when
     * [NightlyDeparture.isBusierThanUsual] holds. Unlike [frequencyDetail]/[listFrequencies], this
     * compares "tonight" (the most recent session) against every prior session directly, so it
     * needs no `nowMillis` — there is no night-window arithmetic here to anchor.
     */
    public suspend fun frequencyChange(context: Context, frequencyHz: Long): FrequencyChangeViewState {
        val db = SharedDatabase.get(context)
        val zone = ZoneId.systemDefault()
        val entities = db.activityDao().transmissionsForFrequency(frequencyHz)
        val latestSession = db.sessionDao().listAll().firstOrNull()
        val tonightTx = latestSession?.let { s -> entities.filter { it.sessionId == s.id } }.orEmpty()
        val priorSessionIds = db.sessionDao().listAll().drop(1).map { it.id }.toSet()
        val priorTx = entities.filter { it.sessionId in priorSessionIds }

        val tonightHourly = IntArray(24)
        tonightTx.forEach { tonightHourly[localHour(it.startedAtUtc, zone)]++ }
        val priorHourlyTotals = IntArray(24)
        priorTx.forEach { priorHourlyTotals[localHour(it.startedAtUtc, zone)]++ }
        val priorSessionCount = priorSessionIds.size.coerceAtLeast(1)
        val usualHourly = priorHourlyTotals.map { it.toDouble() / priorSessionCount }

        val causes = mutableListOf<FrequencyChangeCause>()
        tonightTx.mapNotNull { it.stationId }.toSet().forEach { stationId ->
            val station = db.catalogDao().getStation(stationId)
            val firstHeardAt = station?.firstHeardAt
            if (firstHeardAt != null && latestSession != null && firstHeardAt >= latestSession.startedAt) {
                val count = tonightTx.count { it.stationId == stationId }
                causes.add(
                    FrequencyChangeCause(
                        label = "$stationId · ${pluralize(count, "over")} · first time heard",
                        kind = FrequencyChangeCauseKind.NEW_STATION,
                        subjectId = stationId,
                    ),
                )
            }
        }
        val unidentifiedTonight = tonightTx.filter { it.attributionState == AttributionState.UNKNOWN }
        if (unidentifiedTonight.isNotEmpty()) {
            val distinctVoices = unidentifiedTonight.mapNotNull { it.voiceprintId }.toSet().size
            val voiceLabel = if (distinctVoices > 0) distinctVoices else unidentifiedTonight.size
            causes.add(
                FrequencyChangeCause(
                    "${pluralize(voiceLabel, "unidentified voice")}, ${pluralize(unidentifiedTonight.size, "over")}",
                    isUnidentified = true,
                ),
            )
        }

        val overCount = tonightTx.size
        val usualTotal = usualHourly.sum()
        val usualTotalLabel = "%.0f".format(Locale.ROOT, usualTotal)
        // R-273 (register, design, V5 pass 2 @8d1456f): the subtitle names the real departure
        // window — the contiguous local hours tonight actually ran ahead of their own usual
        // average — not just the bare overall count. `null` (never a fabricated window) when no
        // single hour cleared its own usual, which the overall-count departure test can still flag.
        val window = departureWindowLabel(tonightHourly, usualHourly)
        val subtitleLabel = if (window != null) {
            "Tonight, $window · ${pluralize(overCount, "over")} where the usual is $usualTotalLabel"
        } else {
            "Tonight · ${pluralize(overCount, "over")} where the usual is $usualTotalLabel"
        }
        // R-275/R-563/R-591: the board's full closing paragraph, not just its first sentence, and
        // stating the real reason as a real *narrated* sentence (R-591: "an activation pulled the
        // regulars over", never the raw dotted list-item text R-563's own fix spliced in) —
        // [narrateFrequencyChangeCause] turns the primary (first) cause's own kind into that
        // sentence, or `null` when it cannot be narrated honestly, in which case this falls back
        // to the same "see What made it busy above" pointer R-563 replaced for every narratable
        // cause — a fragment is worse than a pointer, never the other way round.
        val explanationParagraph = buildString {
            append("A departure is a finding, not an alarm.")
            val primaryCause = causes.firstOrNull()
            val narrated = primaryCause?.let { narrateFrequencyChangeCause(it) }
            when {
                narrated != null -> append(" This one has an explanation — $narrated.")
                primaryCause != null -> append(" This one has an explanation — see What made it busy above.")
            }
            append(
                " It will appear in tonight's digest, and will not change what \"usual\" means " +
                    "unless it keeps happening.",
            )
        }
        // R-276: "The N overs" opens the Log filtered to this frequency and this real window —
        // tonight's own session, not the narrower departure window named above (the Log filter is
        // "everything heard tonight", the subtitle's window is "when it spiked").
        val tonightWindow = TimeWindow(
            startMillis = latestSession?.startedAt ?: 0L,
            endMillis = latestSession?.endedAt ?: SystemClock.wallMillis(),
        )
        return FrequencyChangeViewState(
            frequencyHz = frequencyHz,
            label = "%.3f".format(Locale.ROOT, frequencyHz / 1_000_000.0),
            subtitleLabel = subtitleLabel,
            tonightHourly = tonightHourly.toList(),
            usualHourly = usualHourly,
            usualNightsCount = priorSessionIds.size,
            causes = causes,
            overCount = overCount,
            explanationParagraph = explanationParagraph,
            window = tonightWindow,
        )
    }

    /** The contiguous local-hour range tonight ran ahead of its own usual average ("02:00–04:00"),
     * `null` when no hour did (R-273) — never a fabricated window standing in for a real one. */
    private fun departureWindowLabel(tonightHourly: IntArray, usualHourly: List<Double>): String? {
        val elevated = tonightHourly.indices.filter { hour ->
            tonightHourly[hour] > 0 && tonightHourly[hour] > usualHourly.getOrElse(hour) { 0.0 }
        }
        if (elevated.isEmpty()) return null
        val start = elevated.min()
        val end = (elevated.max() + 1) % 24
        return "%02d:00–%02d:00".format(Locale.ROOT, start, end)
    }

    private suspend fun regularRow(
        db: OrtDatabase,
        stationId: String,
        tx: List<TransmissionEntity>,
    ): FrequencyRegularViewState {
        val station = db.catalogDao().getStation(stationId)
        val confirmed = tx.count { it.attributionState == AttributionState.CONFIRMED }
        val inferred = tx.count { it.attributionState == AttributionState.INFERRED }
        val bestConfidence = tx.mapNotNull { it.attributionConfidence }.maxOrNull()
        val attribution = if (confirmed > 0) {
            Attribution.confirmed(stationId, bestConfidence ?: 0.0)
        } else if (inferred > 0) {
            Attribution.inferred(stationId, bestConfidence ?: 0.0)
        } else {
            Attribution.unknown()
        }
        val nightCount = tx.map { it.sessionId }.toSet().size
        return FrequencyRegularViewState(
            stationId = stationId,
            label = station?.callsign ?: stationId,
            attribution = attribution,
            // R-212 (register, design, V5 @f8430b8): the shared plural helper, not a literal
            // "(s)" placeholder — "6 overs · 6 sessions", never "6 over(s) · 6 session(s)".
            countContext = "${pluralize(tx.size, "over")} · ${pluralize(nightCount, "session")}",
            lastHeardLabel = tx.maxByOrNull { it.startedAtUtc }?.startedAtUtc?.let {
                LOCAL_HHMM_FORMAT.format(Instant.ofEpochMilli(it))
            },
        )
    }

    private fun localHour(millis: Long, zone: ZoneId): Int = Instant.ofEpochMilli(millis).atZone(zone).hour

    private suspend fun everySessionWindow(db: OrtDatabase): List<SessionWindow> =
        db.sessionDao().listAll().map { session ->
            val gaps = db.captureGapDao().listBySession(session.id).map { gap ->
                GapWindow(startedAt = gap.startedAt, endedAt = gap.endedAt)
            }
            SessionWindow(startedAtUtc = session.startedAt, endedAtUtc = session.endedAt, gaps = gaps)
        }
}
