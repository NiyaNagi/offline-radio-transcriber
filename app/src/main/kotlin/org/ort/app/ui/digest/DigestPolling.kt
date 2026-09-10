package org.ort.app.ui.digest

import android.content.Context
import org.ort.app.ui.data.ActivityPatternMapper
import org.ort.app.ui.data.GapWindow
import org.ort.app.ui.data.HourActivityBucket
import org.ort.app.ui.data.HourActivityState
import org.ort.app.ui.data.NightlyDeparture
import org.ort.app.ui.data.RoomSessionRouteFactsReader
import org.ort.app.ui.data.SessionRouteFacts
import org.ort.app.ui.data.SessionWindow
import org.ort.app.ui.improve.Plurals
import org.ort.core.AttributionState
import org.ort.core.SystemClock
import org.ort.core.Tier
import org.ort.core.TransmissionState
import org.ort.core.capture.AudioRouteKind
import org.ort.core.capture.BluetoothAudioProfile
import org.ort.core.capture.RigTransportKind
import org.ort.data.OrtDatabase
import org.ort.data.entity.CaptureGapCause
import org.ort.data.entity.CaptureGapEntity
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TerminationReason
import org.ort.data.entity.TransmissionEntity
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.ShedStatus
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * R-092 (register, FR-DIG-1..6, FR-RUN-11/12/16): the real read path behind `Sessions`, `Session`,
 * `Digest`, `Digest-Item`. `SessionDao` has no aggregate span/count/gap query (confirmed by reading
 * it before writing this) — every fact here is computed in Kotlin from the same three DAOs
 * `StationPolling`/`FrequencyPolling` already read, following their exact idiom.
 *
 * Digest items this reads real data for: stations first heard this session, a frequency departing
 * from its usual pattern (reusing [NightlyDeparture], WP8's public function — no edit to
 * `ui/data/ActivityPattern.kt`), the AMBIGUOUS/UNKNOWN counts, an unusually long thread (round 3 —
 * see [longThreadItems]), and a regular station's first absence on its usual weekday (round 3 —
 * see [regularAbsentItems]). Both round-3 additions are bounded, real Kotlin computations over
 * [org.ort.data.dao.TransmissionDao]/[org.ort.data.dao.SessionDao] rows — no new query was added to
 * `:data` for either; grouping by `threadId` and by session weekday is done in this file, the same
 * way [org.ort.app.ui.data.ThreadListMapper] already groups by `threadId` for the Threads
 * destination (confirmed by reading that file first) and [firstHeardItems] already walks per-station
 * history via `ActivityDao.transmissionsForStation`.
 */
public object DigestPolling {

    private const val MAX_TIER_ORDINAL = 3

    /** [longThreadItems]: a thread must be at least this multiple of the historical average
     * thread length, and at least [MIN_LONG_THREAD_OVERS] overs regardless, to be flagged. */
    private const val LONG_THREAD_MULTIPLIER = 2.0
    private const val MIN_LONG_THREAD_OVERS = 5

    /** [regularAbsentItems]: how many past same-weekday sessions are considered, and the minimum
     * that must exist before anyone can be called "a regular" at all. */
    private const val SAME_WEEKDAY_WINDOW = 4
    private const val MIN_SAME_WEEKDAY_HISTORY = 3

    private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC)
    private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM").withZone(ZoneOffset.UTC)

    public suspend fun sessions(context: Context): SessionsViewState {
        val db = OrtDatabase.create(context.applicationContext)
        val sessions = db.sessionDao().listAll()
        val currentTierOrdinal = currentTierOrdinal()
        var totalHours = 0.0
        var totalOvers = 0
        val rows = sessions.map { session ->
            val transmissions = db.transmissionDao().listBySession(session.id)
            val gaps = db.captureGapDao().listBySession(session.id)
            val end = session.endedAt ?: SystemClock.wallMillis()
            totalHours += (end - session.startedAt) / 3_600_000.0
            totalOvers += transmissions.size
            val tier = session.deviceTier?.let { runCatching { Tier.valueOf(it) }.getOrNull() }
            val canBeImproved = tier != null && tier.ordinal < currentTierOrdinal
            SessionRowViewState(
                id = session.id,
                label = sessionLabel(session),
                timeRangeLabel = timeRangeLabel(session.startedAt, session.endedAt),
                overCount = transmissions.size,
                stationCount = transmissions.mapNotNull { it.stationId }.distinct().size,
                gapCount = gaps.size,
                uncleanEndLabel = uncleanEndLabel(session),
                tierChipLabel = tier?.name,
                canBeImproved = canBeImproved,
                live = CaptureState.isCapturing && CaptureState.sessionId == session.id,
                startedAtUtc = session.startedAt,
            )
        }
        return SessionsViewState(
            headline = "${Plurals.count(sessions.size, "session")} · ${totalHours.toInt()} h listened · " +
                Plurals.count(totalOvers, "over"),
            sessions = rows,
        )
    }

    public suspend fun sessionDetail(context: Context, sessionId: String): SessionDetailViewState? {
        val db = OrtDatabase.create(context.applicationContext)
        val session = db.sessionDao().getById(sessionId) ?: return null
        val transmissions = db.transmissionDao().listBySession(sessionId)
        val gapEntities = db.captureGapDao().listBySession(sessionId)
        val end = session.endedAt
        // E2-G03 (DG04, FR-CAP-13): the WPF seam — see that type's own kdoc for why this reads the
        // v7 columns rather than any live process-wide holder (this is a *past* session's own row).
        val routeFacts = RoomSessionRouteFactsReader(context).forSession(sessionId)

        val window = SessionWindow(
            startedAtUtc = session.startedAt,
            endedAtUtc = end,
            gaps = gapEntities.map { GapWindow(startedAt = it.startedAt, endedAt = it.endedAt) },
        )
        // R-449 (register, Reviewer D): `ActivityPatternMapper.buildPattern` folds onto the 24
        // *hour-of-day* buckets its own doc comment names (correct for `Station`/`Frequencies`'
        // multi-night patterns, which is what it was written for) — reused here for a *single*
        // session's own timeline, every hour-of-day this one session never happened to touch (most
        // of the clock, for a session lasting a few hours) folded to `NOT_LISTENING` by that
        // function's own honest "never captured at all reads as not-listening" rule, which is
        // exactly correct for "at 3am, across every night" and exactly wrong for "this session's
        // own 3-hour span" — the real bug the register's screenshot shows (nearly every bar
        // hatched for a session that ran continuously). [sessionCoverageBuckets] instead buckets
        // by *elapsed* hour within this session's own real span — only as many bars as the session
        // actually ran, each hatched only when a *real*, recorded gap actually covers it, never
        // because nothing was heard.
        val coverage = sessionCoverageBuckets(window, transmissions.map { it.startedAtUtc }, SystemClock.wallMillis())
        val notListeningSeconds = gapEntities.sumOf { ((it.endedAt ?: SystemClock.wallMillis()) - it.startedAt) / 1000 }

        return SessionDetailViewState(
            id = session.id,
            label = sessionLabel(session),
            timeRangeLabel = timeRangeLabel(session.startedAt, session.endedAt),
            durationLabel = durationLabel(session.startedAt, end ?: SystemClock.wallMillis()),
            uncleanEndLabel = uncleanEndLabel(session),
            coverage = coverage,
            notListeningLabel = if (gapEntities.isNotEmpty()) {
                "${Plurals.count(gapEntities.size, "gap")} · ${secondsLabel(notListeningSeconds)}"
            } else {
                null
            },
            gaps = gapEntities.map { gapRow(it) },
            overCount = transmissions.size,
            rejectedCount = transmissions.count { it.processingState == TransmissionState.REJECTED },
            failedCount = transmissions.count { it.processingState == TransmissionState.FAILED },
            stationCount = transmissions.mapNotNull { it.stationId }.distinct().size,
            frequencyLabels = transmissions.mapNotNull { it.frequencyHz }.distinct().sorted()
                .map { "%.3f".format(Locale.ROOT, it / 1_000_000.0) },
            // R-450 (register, Reviewer D) / E2-G03 (*amended 2026-09-10*, FR-CAP-13): retired for
            // any session that actually carries the v7 columns — [routeFacts] below — and kept as
            // R-450's own honest line for a pre-v7 row, which genuinely has nothing to read
            // (`SessionDetailViewState.NOT_TRACKED_LABEL`'s own default, applied here explicitly
            // rather than relied on, so this stays correct if that default is ever narrowed later).
            inputLabel = inputLabel(routeFacts),
            tierLabel = sessionTierLabel(transmissions),
            audioSizeLabel = audioSizeLabel(context, sessionId),
            modeLabel = modeLabel(routeFacts),
            rigLinkLabel = rigLinkLabel(routeFacts),
        )
    }

    /** E2-G03 (DG04): "<mode operator label> · <room audio | audio by cable | Bluetooth audio>" —
     * the same three-way route disclosure N04's own Input sub-line uses
     * ([org.ort.app.ui.data.CaptureStatusMapper]), so the two screens never name a route
     * differently for the same session. */
    private fun modeLabel(routeFacts: SessionRouteFacts): String {
        val mode = routeFacts.captureMode ?: return SessionDetailViewState.NOT_TRACKED_LABEL
        val route = when (routeFacts.audioRouteKind) {
            AudioRouteKind.BUILT_IN_MIC -> "room audio"
            AudioRouteKind.USB, AudioRouteKind.WIRED_HEADSET -> "audio by cable"
            AudioRouteKind.BLUETOOTH_SCO -> "Bluetooth audio"
            AudioRouteKind.UNKNOWN, null -> null
        }
        return listOfNotNull(mode.operatorLabel, route).joinToString(" · ")
    }

    /** E2-G03 (DG04, FR-CAP-13): "<route label> · <type> · room audio | radio audio[ · <Bluetooth
     * profile>]" — the two-way room-vs-radio distinction FR-CAP-13 exists to record, plus the
     * Bluetooth profile when the route genuinely was Bluetooth SCO (FR-CAP-11). */
    private fun inputLabel(routeFacts: SessionRouteFacts): String {
        val mode = routeFacts.captureMode ?: return SessionDetailViewState.NOT_TRACKED_LABEL
        val typeLabel = when (routeFacts.audioRouteKind) {
            AudioRouteKind.BUILT_IN_MIC -> "built-in mic"
            AudioRouteKind.USB -> "USB"
            AudioRouteKind.WIRED_HEADSET -> "wired"
            AudioRouteKind.BLUETOOTH_SCO -> "Bluetooth"
            AudioRouteKind.UNKNOWN, null -> null
        }
        val roomOrRadio = if (mode == org.ort.core.capture.CaptureMode.LOCAL_MICROPHONE) "room audio" else "radio audio"
        val profileLabel = routeFacts.bluetoothProfile?.let { bluetoothProfileLabel(it) }
        return listOfNotNull(routeFacts.audioRouteLabel, typeLabel, roomOrRadio, profileLabel).joinToString(" · ")
    }

    private fun bluetoothProfileLabel(profile: BluetoothAudioProfile): String = when (profile) {
        BluetoothAudioProfile.HFP_MSBC -> "Bluetooth (wideband)"
        BluetoothAudioProfile.HFP_CVSD -> "Bluetooth (narrowband)"
        BluetoothAudioProfile.UNKNOWN -> "Bluetooth (profile unknown)"
    }

    /**
     * E2-G03 (DG04, FR-RIG-14/FR-RIG-15): the transport alone — no rig-event history is persisted
     * anywhere in `:data` today (checked before writing this) to name a stale span from, so "stale
     * spans from the session's rig events if recorded" never fires yet; this is an honest, reported
     * limitation (constitution I), not silently dropped functionality.
     */
    private fun rigLinkLabel(routeFacts: SessionRouteFacts): String {
        if (routeFacts.captureMode == null) return SessionDetailViewState.NOT_TRACKED_LABEL
        val transport = routeFacts.rigTransport ?: return "no rig this session"
        return when (transport) {
            RigTransportKind.USB_SERIAL -> "USB serial"
            RigTransportKind.BLUETOOTH_SPP -> "Bluetooth SPP"
        }
    }

    /** R-450 (register): the real per-transmission [TransmissionEntity.processedTier] (schema v4)
     * — `SessionEntity.deviceTier` (the previous source) is the *live-capture* tier, which
     * `RealCaptureService` never actually writes for a real session (`deviceTier = null` on every
     * insert, checked before writing this), so it read as the placeholder "current" for every real
     * session regardless of truth. `processedTier` is itself `null` until a reprocess pass first
     * completes for a transmission (that field's own doc comment) — so a session nothing has ever
     * reprocessed honestly reads "not yet reprocessed", never a fabricated tier number. */
    private fun sessionTierLabel(transmissions: List<TransmissionEntity>): String {
        val processedTiers = transmissions.mapNotNull { it.processedTier }.distinct()
        return when {
            processedTiers.isEmpty() -> "not yet reprocessed"
            processedTiers.size == 1 -> "tier ${processedTiers.single().ordinal}"
            else -> "mixed · up to tier ${processedTiers.maxOf { it.ordinal }}"
        }
    }

    /**
     * R-449 (register, Reviewer D): `Session`'s own coverage chart, bucketed by *elapsed* hour
     * within [window]'s own real span — see the doc comment at this function's own call site for
     * why `ActivityPatternMapper.buildPattern`'s hour-*of-day* folding (correct for `Station`/
     * `Frequencies`, wrong reused here) hatched nearly every bar. Exactly as many buckets as the
     * session's own real duration spans (never a fixed 24).
     *
     * R-540 (register, Reviewer D, tour run 3): the first fix above (only hatching a bucket the
     * *majority* of whose span a real gap covered, and only when nothing was heard in it at all)
     * swallowed a real, recorded 22-minute gap entirely — the bucket also had real overs elsewhere
     * in the same hour, so `heardCount > 0` always won, and 22 minutes never reached the old ≥50%
     * floor either way. The board's own rule (`design/canvas/Session.dc.html`'s coverage chart,
     * cf. its own gap list) is simpler and unconditional: **any** real, recorded
     * [SessionWindow.gaps] overlap inside an hour hatches that hour's bar — real overs elsewhere in
     * the same hour never hide it. `ActivityPatternChart` (the shared, out-of-ownership renderer)
     * draws one of exactly three states per bucket, never a sub-bar split proportional to how much
     * of the hour the gap actually covered — that honest, hour-granularity limit is unchanged, only
     * which state wins a real conflict.
     */
    private fun sessionCoverageBuckets(
        window: SessionWindow,
        matchingTransmissionTimestamps: List<Long>,
        nowMillis: Long,
    ): List<HourActivityBucket> {
        val sessionEnd = window.endedAtUtc ?: nowMillis
        if (sessionEnd <= window.startedAtUtc) return emptyList()
        val totalHours = ((sessionEnd - window.startedAtUtc + HOUR_MILLIS - 1) / HOUR_MILLIS)
            .toInt()
            .coerceAtLeast(1)
        return (0 until totalHours).map { hourIndex ->
            val bucketStart = window.startedAtUtc + hourIndex * HOUR_MILLIS
            val bucketEnd = minOf(bucketStart + HOUR_MILLIS, sessionEnd)
            val heardCount = matchingTransmissionTimestamps.count { it in bucketStart until bucketEnd }
            val hasRealGap = window.gaps.any { gap ->
                val overlapStart = maxOf(gap.startedAt, bucketStart)
                val overlapEnd = minOf(gap.endedAt ?: sessionEnd, bucketEnd)
                overlapEnd > overlapStart
            }
            val state = when {
                hasRealGap -> HourActivityState.NOT_LISTENING
                heardCount > 0 -> HourActivityState.HEARD
                else -> HourActivityState.SILENT_WHILE_LISTENING
            }
            HourActivityBucket(hourOfDayUtc = hourIndex, state = state, heardCount = heardCount)
        }
    }

    private const val HOUR_MILLIS = 3_600_000L

    public suspend fun digest(context: Context, sessionId: String): DigestViewState? {
        val db = OrtDatabase.create(context.applicationContext)
        val session = db.sessionDao().getById(sessionId) ?: return null
        val transmissions = db.transmissionDao().listBySession(sessionId)
        val allSessions = db.sessionDao().listAll()
        val allWindows = allSessions.map { s ->
            SessionWindow(
                startedAtUtc = s.startedAt,
                endedAtUtc = s.endedAt,
                gaps = db.captureGapDao().listBySession(s.id).map { GapWindow(it.startedAt, it.endedAt) },
            )
        }

        val items = mutableListOf<DigestItemViewState>()
        items += firstHeardItems(db, sessionId, transmissions)
        items += busierThanUsualItems(db, allWindows, transmissions)
        items += longThreadItems(db, transmissions)
        items += regularAbsentItems(db, session, allSessions, transmissions)
        ambiguousItem(transmissions)?.let { items += it }

        val notKnown = mutableListOf<DigestNotKnownItemViewState>()
        unidentifiedVoicesItem(transmissions)?.let { notKnown += it }
        val gaps = db.captureGapDao().listBySession(sessionId)
        notKnown += gaps.map { gapNotKnownItem(it) }

        val rejected = transmissions.count { it.processingState == TransmissionState.REJECTED }
        val attributedCount = transmissions.count { it.attributionState != AttributionState.UNKNOWN }
        val attributedPercent = if (transmissions.isNotEmpty()) (attributedCount * 100 / transmissions.size) else 0

        return DigestViewState(
            sessionId = sessionId,
            headline = sessionLabel(session),
            timeRangeLabel = timeRangeLabel(session.startedAt, session.endedAt),
            overCount = transmissions.size,
            stationCount = transmissions.mapNotNull { it.stationId }.distinct().size,
            bandCount = transmissions.mapNotNull { it.frequencyHz }.distinct().size,
            gapCount = gaps.size,
            items = items,
            notKnown = notKnown,
            attributedPercentLabel = "$attributedPercent%",
            rejectedCount = rejected,
        )
    }

    private fun currentTierOrdinal(): Int = (MAX_TIER_ORDINAL - ShedStatus.currentLevel).coerceIn(0, MAX_TIER_ORDINAL)

    private suspend fun firstHeardItems(
        db: OrtDatabase,
        sessionId: String,
        transmissions: List<TransmissionEntity>,
    ): List<DigestItemViewState> {
        val stationIdsTonight = transmissions.mapNotNull { it.stationId }.distinct()
        return stationIdsTonight.mapNotNull { stationId ->
            val everyTransmission = db.activityDao().transmissionsForStation(stationId)
            val first = everyTransmission.firstOrNull() ?: return@mapNotNull null
            if (first.sessionId != sessionId) return@mapNotNull null
            val overs = everyTransmission.count { it.sessionId == sessionId }
            DigestItemViewState(
                id = "first-$stationId",
                headline = "$stationId heard for the first time",
                subLine = "first time heard · $overs over(s) this session",
                reason = "first time heard",
                ambiguousTone = false,
                transmissionIds = everyTransmission.filter { it.sessionId == sessionId }.map { it.id },
            )
        }
    }

    /** Reuses WP8's public [NightlyDeparture], unedited — a frequency departing from its own usual
     * nightly count, never a fabricated comparison. */
    private suspend fun busierThanUsualItems(
        db: OrtDatabase,
        allWindows: List<SessionWindow>,
        transmissions: List<TransmissionEntity>,
    ): List<DigestItemViewState> {
        val frequencies = transmissions.mapNotNull { it.frequencyHz }.distinct()
        return frequencies.mapNotNull { frequencyHz ->
            val timestamps = db.activityDao().transmissionsForFrequency(frequencyHz).map { it.startedAtUtc }
            val nights = ActivityPatternMapper.buildNightlySequence(
                sessions = allWindows,
                matchingTransmissionTimestamps = timestamps,
                nowMillis = SystemClock.wallMillis(),
            )
            if (!NightlyDeparture.isBusierThanUsual(nights)) return@mapNotNull null
            val tonightCount = transmissions.count { it.frequencyHz == frequencyHz }
            val usual = NightlyDeparture.usualAverage(nights)
            DigestItemViewState(
                id = "busy-$frequencyHz",
                headline = "%.3f was busier than usual".format(Locale.ROOT, frequencyHz / 1_000_000.0),
                subLine = "$tonightCount overs against a usual %.0f".format(Locale.ROOT, usual),
                reason = "departure from usual pattern",
                ambiguousTone = true,
                transmissionIds = transmissions.filter { it.frequencyHz == frequencyHz }.map { it.id },
            )
        }
    }

    /**
     * FR-DIG-2a "unusually long threads": groups this session's transmissions by
     * [TransmissionEntity.threadId] (a real, populated column since M6 — confirmed non-null on
     * real rows by reading [org.ort.app.ui.data.ThreadListMapper] before writing this, the same
     * grouping key that object already uses for the `Threads` destination) and flags a thread
     * whose over count clears both a fixed floor and a multiple of the *historical* average thread
     * length across every session ever recorded — never a threshold invented from tonight's data
     * alone, which would flag every night's longest thread regardless of whether any thread that
     * night was actually unusual.
     */
    private suspend fun longThreadItems(
        db: OrtDatabase,
        transmissions: List<TransmissionEntity>,
    ): List<DigestItemViewState> {
        val threadsTonight = transmissions.filter { it.threadId != null }.groupBy { it.threadId!! }
        if (threadsTonight.isEmpty()) return emptyList()
        val allThreads = db.transmissionDao().listAll().filter { it.threadId != null }.groupBy { it.threadId!! }
        val averageLength = allThreads.values.map { it.size }.average()
        if (averageLength.isNaN()) return emptyList()
        val threshold = (averageLength * LONG_THREAD_MULTIPLIER).coerceAtLeast(MIN_LONG_THREAD_OVERS.toDouble())
        return threadsTonight.mapNotNull { (threadId, group) ->
            if (group.size < threshold) return@mapNotNull null
            val participants = group.mapNotNull { it.stationId }.distinct().size
            val participantsNote = if (participants > 0) " with $participants participant(s)" else ""
            DigestItemViewState(
                id = "thread-$threadId",
                headline = "A thread ran ${group.size} over(s)$participantsNote",
                subLine = "unusually long · usual is %.0f over(s)".format(Locale.ROOT, averageLength),
                reason = "unusually long thread",
                ambiguousTone = false,
                transmissionIds = group.map { it.id },
            )
        }
    }

    /**
     * FR-DIG-2a "a regular... absent": a station qualifies as a regular for tonight's day of week
     * when it was heard in every one of the last [SAME_WEEKDAY_WINDOW] sessions that started on
     * that same UTC weekday (excluding tonight) — real session/transmission rows only, requiring at
     * least [MIN_SAME_WEEKDAY_HISTORY] of them to exist before calling anyone "a regular" at all, so
     * a small or new log never produces a false "absent" claim. A qualifying regular not heard
     * tonight is reported as absent for the first time in that window.
     */
    private suspend fun regularAbsentItems(
        db: OrtDatabase,
        session: SessionEntity,
        allSessions: List<SessionEntity>,
        transmissionsTonight: List<TransmissionEntity>,
    ): List<DigestItemViewState> {
        val tonightDayOfWeek = Instant.ofEpochMilli(session.startedAt).atZone(ZoneOffset.UTC).dayOfWeek
        val heardTonight = transmissionsTonight.mapNotNull { it.stationId }.toSet()
        val sameWeekdaySessions = allSessions
            .filter { it.id != session.id }
            .filter { Instant.ofEpochMilli(it.startedAt).atZone(ZoneOffset.UTC).dayOfWeek == tonightDayOfWeek }
            .sortedByDescending { it.startedAt }
            .take(SAME_WEEKDAY_WINDOW)
        if (sameWeekdaySessions.size < MIN_SAME_WEEKDAY_HISTORY) return emptyList()

        val stationsPerWeek = sameWeekdaySessions.map { s ->
            db.transmissionDao().listBySession(s.id).mapNotNull { it.stationId }.toSet()
        }
        val everyWeekStationIds = stationsPerWeek.reduce { a, b -> a intersect b }
        val dayName = tonightDayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        return everyWeekStationIds.filter { it !in heardTonight }.map { stationId ->
            DigestItemViewState(
                id = "absent-$stationId",
                headline = "$stationId was absent for the first time in ${sameWeekdaySessions.size} weeks",
                subLine = "a regular · heard every $dayName for the last ${sameWeekdaySessions.size} weeks",
                reason = "a regular station absent",
                ambiguousTone = false,
                transmissionIds = emptyList(),
            )
        }
    }

    private fun ambiguousItem(transmissions: List<TransmissionEntity>): DigestItemViewState? {
        val ambiguous = transmissions.filter { it.attributionState == AttributionState.AMBIGUOUS }
        if (ambiguous.isEmpty()) return null
        return DigestItemViewState(
            id = "ambiguous",
            headline = "${ambiguous.size} over(s) could not be attributed with confidence",
            subLine = "ambiguous attribution this session",
            reason = "high ambiguous rate",
            ambiguousTone = true,
            transmissionIds = ambiguous.map { it.id },
        )
    }

    private fun unidentifiedVoicesItem(transmissions: List<TransmissionEntity>): DigestNotKnownItemViewState? {
        val unknown = transmissions.count { it.attributionState == AttributionState.UNKNOWN }
        if (unknown == 0) return null
        return DigestNotKnownItemViewState(
            headline = "$unknown over(s) from unidentified voices",
            subLine = "no callsign heard, no voice matched — they stay findable",
        )
    }

    private fun gapNotKnownItem(gap: CaptureGapEntity): DigestNotKnownItemViewState {
        val seconds = ((gap.endedAt ?: gap.startedAt) - gap.startedAt) / 1000
        val clock = CLOCK_FORMAT.format(Instant.ofEpochMilli(gap.startedAt))
        return DigestNotKnownItemViewState(
            headline = "${secondsLabel(seconds)} at $clock — ${causeProse(gap.cause)}",
            subLine = "anything transmitted then is not in the record",
        )
    }

    /** R-145 (register, round 4 System validator): the one place a gap's duration in seconds
     * becomes "22m 0s" prose — [notListeningLabel] and [gapNotKnownItem] used to print the raw
     * second count ("1320s"); [gapRow] already formatted it this way, so this pulls that formatting
     * out for all three instead of leaving two of them raw. */
    private fun secondsLabel(totalSeconds: Long): String = "${totalSeconds / 60}m ${totalSeconds % 60}s"

    private fun sessionLabel(session: SessionEntity): String {
        val instant = Instant.ofEpochMilli(session.startedAt)
        val live = CaptureState.isCapturing && CaptureState.sessionId == session.id
        return if (live) "Tonight" else DAY_FORMAT.format(instant)
    }

    private fun timeRangeLabel(startedAt: Long, endedAt: Long?): String {
        val start = CLOCK_FORMAT.format(Instant.ofEpochMilli(startedAt))
        val end = endedAt?.let { CLOCK_FORMAT.format(Instant.ofEpochMilli(it)) } ?: "–"
        return "$start – $end"
    }

    private fun durationLabel(startedAt: Long, end: Long): String {
        val minutes = (end - startedAt) / 60_000
        return "${minutes / 60} h ${minutes % 60} m"
    }

    private fun uncleanEndLabel(session: SessionEntity): String? {
        if (session.endedAt == null) return null
        return when (session.terminationReason) {
            null, TerminationReason.USER -> null
            TerminationReason.CRASH -> "ended unclean — the app crashed"
            TerminationReason.KILLED -> "ended unclean — the phone stopped the app"
            TerminationReason.STORAGE -> "ended — storage exhausted"
            TerminationReason.UNKNOWN -> "ended unexpectedly"
        }
    }

    private fun gapRow(gap: CaptureGapEntity): SessionGapRowViewState {
        val durationSeconds = ((gap.endedAt ?: gap.startedAt) - gap.startedAt) / 1000
        return SessionGapRowViewState(
            timeLabel = CLOCK_FORMAT.format(Instant.ofEpochMilli(gap.startedAt)),
            durationLabel = secondsLabel(durationSeconds),
            causeLabel = causeProse(gap.cause),
            resumedLabel = if (gap.recoveredAutomatically) "resumed automatically" else "recovered manually",
        )
    }

    private fun causeProse(cause: CaptureGapCause): String = when (cause) {
        CaptureGapCause.CALL -> "incoming call took the microphone"
        CaptureGapCause.INPUT_LOST -> "input lost"
        CaptureGapCause.OS_STOPPED -> "stopped by the OS"
        CaptureGapCause.ROUTE_LOST -> "route lost"
        CaptureGapCause.INTERRUPTION -> "interrupted"
        CaptureGapCause.ROUTE_CHANGE -> "route changed"
        CaptureGapCause.DEVICE_LOST -> "device lost"
        CaptureGapCause.STORAGE -> "storage exhausted"
        CaptureGapCause.UNKNOWN -> "unknown cause"
    }

    private fun audioSizeLabel(context: Context, sessionId: String): String {
        val dir = java.io.File(context.filesDir, "audio/$sessionId")
        val bytes = if (dir.isDirectory) dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
        val gb = bytes / 1_000_000_000.0
        return if (gb >= 0.1) "%.2f GB".format(Locale.ROOT, gb) else "%.0f MB".format(Locale.ROOT, bytes / 1_000_000.0)
    }
}
