package org.ort.app.ui.data

import android.content.Context
import org.ort.core.Attribution
import org.ort.core.AttributionState
import org.ort.core.SystemClock
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.StationEntity
import org.ort.data.entity.TransmissionEntity
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
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
public object StationPolling {

    /** The "Stations" list (R-070): every station ever heard, most recently heard first. */
    public suspend fun listStations(context: Context): List<StationListEntryViewState> {
        val db = OrtDatabase.create(context.applicationContext)
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
        val db = OrtDatabase.create(context.applicationContext)
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
        val db = OrtDatabase.create(context.applicationContext)
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

        val base = StationViewMapper.detail(stationId, label, details, pattern, dayOfWeekPattern, weekOverWeek)
        return base.copy(
            givenName = station?.userName,
            transmissionCountTonight = tonightCount,
            confirmedCount = confirmed,
            inferredCount = inferred,
            correctedCount = corrected,
            frequenciesSummary = StationViewMapper.frequencySummary(frequencyCounts),
            firstHeardLabel = station?.firstHeardAt?.let { LABEL_FORMAT.format(Instant.ofEpochMilli(it)) },
            lastHeardLabel = station?.lastHeardAt?.let { LABEL_FORMAT.format(Instant.ofEpochMilli(it)) },
            lastHeardSignalLabel = lastEntity?.signalStrength?.let { "S%.0f".format(Locale.ROOT, it) },
        )
    }

    /** `Station-Pattern.dc.html`'s state (R-072, R-075) — every bucket is local-time. */
    public suspend fun stationPattern(context: Context, stationId: String, nowMillis: Long): StationPatternViewState {
        val db = OrtDatabase.create(context.applicationContext)
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
        )
    }

    /**
     * `Station-Identity.dc.html`'s state (R-073). [StationVoiceViewState.nearestOtherStationId] is
     * always `null` today — cross-station voice-distance comparison needs the identity pipeline
     * (M4) to have written comparable embeddings, which it does not yet do (see this package's
     * report); rendered honestly absent rather than computed from an undocumented byte layout.
     */
    public suspend fun stationIdentity(context: Context, stationId: String): StationIdentityViewState {
        val db = OrtDatabase.create(context.applicationContext)
        val station = db.catalogDao().getStation(stationId)
        val entities = db.activityDao().transmissionsForStation(stationId)
        val voiceprints = db.catalogDao().voiceprintsForStation(stationId)
        val clusterOvers = voiceprints.sumOf { it.memberCount }
        val confirmed = entities.count { it.attributionState == AttributionState.CONFIRMED }
        val inferred = entities.count { it.attributionState == AttributionState.INFERRED }
        return StationIdentityViewState(
            stationId = stationId,
            callsign = station?.callsign ?: stationId,
            heardOverCount = confirmed,
            lexiconLabel = station?.ituRegionFromPrefix,
            voice = StationVoiceViewState(
                clusterOverCount = clusterOvers,
                confirmedCount = confirmed,
                inferredCount = inferred,
            ),
            givenByYou = StationGivenByYouViewState(name = station?.userName, note = station?.notes),
        )
    }

    private suspend fun stationRow(
        db: OrtDatabase,
        station: StationEntity,
        latest: SessionEntity?,
    ): StationListEntryViewState {
        val base = StationViewMapper.listEntry(station)
        val tonightTx = if (latest != null) {
            db.transmissionDao().listBySession(latest.id).filter { it.stationId == station.id }
        } else {
            emptyList()
        }
        if (tonightTx.isEmpty() || latest == null) return base

        val confirmed = tonightTx.count { it.attributionState == AttributionState.CONFIRMED }
        val inferred = tonightTx.count { it.attributionState == AttributionState.INFERRED }
        val ambiguous = tonightTx.count { it.attributionState == AttributionState.AMBIGUOUS }
        val corrected = tonightTx.any { it.corrected }
        val dominant = when {
            confirmed > 0 -> AttributionState.CONFIRMED
            inferred > 0 -> AttributionState.INFERRED
            ambiguous > 0 -> AttributionState.AMBIGUOUS
            else -> AttributionState.UNKNOWN
        }
        val bestConfidence = tonightTx.filter { it.attributionState == dominant }
            .mapNotNull { it.attributionConfidence }.maxOrNull()
        val attribution = when (dominant) {
            AttributionState.CONFIRMED -> Attribution.confirmed(station.id, bestConfidence ?: 0.0)
            AttributionState.INFERRED -> Attribution.inferred(station.id, bestConfidence ?: 0.0)
            AttributionState.AMBIGUOUS -> Attribution.ambiguous()
            AttributionState.UNKNOWN -> Attribution.unknown()
        }
        val firstHeardAt = station.firstHeardAt
        val isNew = firstHeardAt != null && firstHeardAt >= latest.startedAt
        val badge = when {
            isNew -> StationListBadge.NEW
            corrected -> StationListBadge.CORRECTED
            else -> null
        }
        return base.copy(
            attribution = attribution,
            countContext = StationViewMapper.countContext(confirmed, inferred),
            badge = badge,
            heardTonight = true,
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
        val db = OrtDatabase.create(context.applicationContext)
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
        val db = OrtDatabase.create(context.applicationContext)
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
        )
    }

    /**
     * `Frequency-Change.dc.html`'s state (R-074) — only built when
     * [NightlyDeparture.isBusierThanUsual] holds. Unlike [frequencyDetail]/[listFrequencies], this
     * compares "tonight" (the most recent session) against every prior session directly, so it
     * needs no `nowMillis` — there is no night-window arithmetic here to anchor.
     */
    public suspend fun frequencyChange(context: Context, frequencyHz: Long): FrequencyChangeViewState {
        val db = OrtDatabase.create(context.applicationContext)
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
                causes.add(FrequencyChangeCause("$stationId · $count over(s) · first time heard"))
            }
        }
        val unidentifiedTonight = tonightTx.filter { it.attributionState == AttributionState.UNKNOWN }
        if (unidentifiedTonight.isNotEmpty()) {
            val distinctVoices = unidentifiedTonight.mapNotNull { it.voiceprintId }.toSet().size
            val voiceLabel = if (distinctVoices > 0) distinctVoices else unidentifiedTonight.size
            causes.add(
                FrequencyChangeCause(
                    "$voiceLabel unidentified voice(s), ${unidentifiedTonight.size} over(s)",
                    isUnidentified = true,
                ),
            )
        }

        val overCount = tonightTx.size
        val usualTotal = usualHourly.sum()
        return FrequencyChangeViewState(
            frequencyHz = frequencyHz,
            label = "%.3f".format(Locale.ROOT, frequencyHz / 1_000_000.0),
            subtitleLabel = "Tonight · $overCount overs where the usual is ${"%.0f".format(Locale.ROOT, usualTotal)}",
            tonightHourly = tonightHourly.toList(),
            usualHourly = usualHourly,
            causes = causes,
            overCount = overCount,
            explanationSentence = "A departure is a finding, not an alarm.",
        )
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
            countContext = "${tx.size} over(s) · $nightCount session(s)",
            lastHeardLabel = tx.maxByOrNull { it.startedAtUtc }?.startedAtUtc?.let {
                HHMM_FORMAT.format(Instant.ofEpochMilli(it))
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

private val HHMM_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC)
