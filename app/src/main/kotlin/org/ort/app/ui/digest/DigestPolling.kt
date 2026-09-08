package org.ort.app.ui.digest

import android.content.Context
import org.ort.app.ui.data.ActivityPatternMapper
import org.ort.app.ui.data.GapWindow
import org.ort.app.ui.data.NightlyDeparture
import org.ort.app.ui.data.SessionWindow
import org.ort.core.AttributionState
import org.ort.core.SystemClock
import org.ort.core.Tier
import org.ort.core.TransmissionState
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
 * `ui/data/ActivityPattern.kt`), and the AMBIGUOUS/UNKNOWN counts. **Long-thread and "regular
 * absent" items are not computed** — no query lists a session's threads or a station's day-of-week
 * regularity yet (see this package's report); a digest with fewer item *kinds* than the artboard,
 * each one real, is the honest choice over inventing the missing ones.
 */
public object DigestPolling {

    private const val MAX_TIER_ORDINAL = 3
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
            )
        }
        return SessionsViewState(
            headline = "${sessions.size} session(s) · ${totalHours.toInt()} h listened · $totalOvers overs",
            sessions = rows,
        )
    }

    public suspend fun sessionDetail(context: Context, sessionId: String): SessionDetailViewState? {
        val db = OrtDatabase.create(context.applicationContext)
        val session = db.sessionDao().getById(sessionId) ?: return null
        val transmissions = db.transmissionDao().listBySession(sessionId)
        val gapEntities = db.captureGapDao().listBySession(sessionId)
        val end = session.endedAt

        val window = SessionWindow(
            startedAtUtc = session.startedAt,
            endedAtUtc = end,
            gaps = gapEntities.map { GapWindow(startedAt = it.startedAt, endedAt = it.endedAt) },
        )
        val coverage = ActivityPatternMapper.buildPattern(
            sessions = listOf(window),
            matchingTransmissionTimestamps = transmissions.map { it.startedAtUtc },
            nowMillis = SystemClock.wallMillis(),
        )
        val notListeningSeconds = gapEntities.sumOf { ((it.endedAt ?: SystemClock.wallMillis()) - it.startedAt) / 1000 }

        return SessionDetailViewState(
            id = session.id,
            label = sessionLabel(session),
            timeRangeLabel = timeRangeLabel(session.startedAt, session.endedAt),
            durationLabel = durationLabel(session.startedAt, end ?: SystemClock.wallMillis()),
            uncleanEndLabel = uncleanEndLabel(session),
            coverage = coverage,
            notListeningLabel = if (gapEntities.isNotEmpty()) {
                "${gapEntities.size} gap(s) · ${notListeningSeconds}s"
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
            inputLabel = "see Settings › Input and level",
            tierLabel = session.deviceTier ?: "current",
            audioSizeLabel = audioSizeLabel(context, sessionId),
        )
    }

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
            headline = "${seconds}s at $clock — ${causeProse(gap.cause)}",
            subLine = "anything transmitted then is not in the record",
        )
    }

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
            durationLabel = "${durationSeconds / 60}m ${durationSeconds % 60}s",
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
