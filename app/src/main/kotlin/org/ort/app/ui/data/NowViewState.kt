package org.ort.app.ui.data

import org.ort.core.AttributionState
import java.util.Locale

/**
 * The "Now" home's full render-ready state (ui-conformance-plan WP4, R-030/R-033/R-036/R-037,
 * R-174, `Main.dc.html`/`Now-Idle.dc.html`/`Now-First.dc.html`) —
 * [org.ort.app.ui.screens.NowScreen] is a pure function of one of these, no `Context`.
 *
 * The three artboards are three shapes of the same screen, not three screens: [Idle] (no session
 * running), and [Active] for both `Main.dc.html` (a populated running/ended session) and
 * `Now-First.dc.html` (a session that has just started, nothing heard yet — [Active] with
 * `overCount == 0`). [overCount] (R-174) is what [org.ort.app.ui.screens.NowScreen] switches on to
 * render `Now-First`'s flat chart baseline instead of [activityPattern]'s real hour-by-hour
 * pattern: a session three minutes old genuinely has not listened through the other 23 hours of
 * the day, so [activityPattern] would honestly render them all as the full-height not-listening
 * hatch (FR-UI-12) — accurate, but exactly the alarming full-width hatch `Now-First.dc.html`
 * deliberately does not show this early. The fix is not to fabricate 23 hours of "quiet, but
 * listened-through" (that would be the actual lie), it is to not commit to the real pattern at all
 * yet — see `NowScreen`'s own `FirstSessionChartBaseline`.
 */
public sealed interface NowViewState {

    /** `Now-Idle.dc.html`: no session running right now. */
    public data class Idle(
        val lastSessionSummaryLabel: String?,
        val inputLabel: String?,
        val rigLabel: String?,
        val tierLabel: String?,
        val earlierNights: List<EarlierNightRow>,
        val canGetBetter: CanGetBetterRow?,
    ) : NowViewState

    /** `Main.dc.html` (populated) and `Now-First.dc.html` (`overCount == 0`). */
    public data class Active(
        val sessionTitle: String,
        val summaryLabel: String,
        val overCount: Int,
        val activityPattern: List<HourActivityBucket>,
        val axisStartLabel: String?,
        val axisEndLabel: String?,
        val notListeningLabel: String?,
        val missingModel: MissingModelFacts?,
        val worthKnowing: List<WorthKnowingItem>,
        val stations: NowStationsSection,
        /** E2-G02 (`Main-Room-Audio.dc.html`, FR-CAP-3a/FR-CAP-10): `true` exactly when the
         * current session's capture mode is [org.ort.core.capture.CaptureMode.LOCAL_MICROPHONE] —
         * gates the persistent room-audio disclosure chip under the title. `false` (every caller
         * before this existed) renders exactly as before. */
        val isLocalMicrophone: Boolean = false,
    ) : NowViewState
}

/** `Now-Idle.dc.html`'s "Earlier nights" row (top 3, from `sessionDao`). */
public data class EarlierNightRow(
    val sessionId: String,
    val title: String,
    val subLine: String,
    val gapsLabel: String?,
)

/** `Now-Idle.dc.html`'s "Can get better" row — WP10 owns the Improve destination itself; this is
 * only the row and the fact that routes to it (R-036). */
public data class CanGetBetterRow(val headline: String, val subLine: String)

/** `Feedback.dc.html`'s failed treatment for the missing-model case (R-034), shown on Now when
 * [org.ort.pipeline.capture.AsrAvailability] is not `Available`. */
public data class MissingModelFacts(val title: String, val body: String, val actionLabel: String)

/** `Main.dc.html`'s "Worth knowing" dot is drawn in the CONFIRMED (solid green) or AMBIGUOUS
 * (half-filled amber) shape, but a digest item is not itself an attribution — reusing
 * [org.ort.core.AttributionState] here would claim more than this type means, so this is its own
 * small, closed set (mirrors `Feedback.dc.html`'s own `FailedMarker`, drawn locally for the same
 * reason — see that composable's doc comment in `ui/components/Feedback.kt`). */
public enum class WorthKnowingTone { NOMINAL, DEGRADED }

/** One `WORTH KNOWING` item — only the three kinds tonight's real data can honestly support
 * (R-030): a first-time-heard station, an ambiguous/unknown-attribution count, or a gap. */
public data class WorthKnowingItem(val tone: WorthKnowingTone, val headline: String, val subLine: String)

/** One `STATIONS HEARD` row. [marker] is always CONFIRMED or INFERRED — a station row only exists
 * for a station that was actually attributed at least once this session. */
public data class NowStationRow(
    val stationId: String,
    val marker: AttributionState,
    val callsign: String,
    val countLabel: String,
    val lastTimeLabel: String,
)

/** `Main.dc.html`'s `STATIONS HEARD · All N` section. [unidentifiedLabel] is the honest over count
 * for AMBIGUOUS/UNKNOWN transmissions this session — never a distinct-voice count, since nothing
 * this package reads clusters voiceprints into "voices" (that is FR-SPK's own, unbuilt job). */
public data class NowStationsSection(
    val totalCount: Int,
    val rows: List<NowStationRow>,
    val unidentifiedLabel: String?,
    val emptyMessage: String?,
)

public object NowViewStateMapper {

    /**
     * `Main.dc.html`/`Now-First.dc.html`. [details] and [gaps] are this **session's own**
     * transmissions/gaps only (never "every session" — that aggregate is
     * [org.ort.app.ui.data.ActivityPatternMapper]'s other, station/frequency-scoped caller in
     * `ReaderPolling`). [firstHeardStationIds] names which of [details]'s attributed stations had
     * their [org.ort.data.entity.StationEntity.firstHeardAt] fall inside this session — computed
     * by the caller (a DB read), not here, so this function stays a pure mapping.
     */
    @Suppress("LongParameterList")
    public fun active(
        details: List<TransmissionDetail>,
        gaps: List<GapWindow>,
        sessionStartedAtUtc: Long,
        sessionEndedAtUtc: Long?,
        nowMillis: Long,
        firstHeardStationIds: Set<String>,
        asrAvailable: Boolean,
        missingModel: MissingModelFacts?,
        listeningOnLabel: String?,
    ): NowViewState.Active {
        val overCount = details.size
        val stationCount = details.mapNotNull { it.attribution.stationId }.toSet().size
        val sessionTitle = if (overCount == 0) "Tonight" else "Overnight"
        // Deliberately no "· N bands" segment: a frequency-to-band table does not exist yet
        // (NowSummaryMapper's own long-standing rule, unchanged here) — omitted rather than
        // guessed (constitution I).
        // R-418: "1 overs · 0 stations" — the shared plural rule (ThreadViewData.pluralize,
        // same package, R-163) was never applied here.
        val summaryLabel = if (overCount == 0) {
            "0 overs" + (listeningOnLabel?.let { " · $it" } ?: "")
        } else {
            "${pluralize(overCount, "over")} · ${pluralize(stationCount, "station")}"
        }

        // R-913 (register, halt): this session's own live chart and DG04's own past-session
        // coverage bar (`DigestPolling.sessionDetail`) now derive from the same shared
        // [ActivityPatternMapper.buildSessionElapsedPattern] — see that function's own kdoc for
        // why [ActivityPatternMapper.buildPattern]'s 24 hour-of-day buckets are the wrong shape
        // for one session's own short span.
        val pattern = ActivityPatternMapper.buildSessionElapsedPattern(
            window = SessionWindow(sessionStartedAtUtc, sessionEndedAtUtc, gaps),
            matchingTransmissionTimestamps = details.map { it.startedAtUtcMillis },
            nowMillis = nowMillis,
        )

        return NowViewState.Active(
            sessionTitle = sessionTitle,
            summaryLabel = summaryLabel,
            overCount = overCount,
            activityPattern = pattern,
            // R-414: the real start/end minute, not always ":00" — see hourMinuteLabel's own use
            // elsewhere in this file. A short session (e.g. `first-session`, only minutes old) used
            // to have both ends floor to the same wall-clock hour once the minute was dropped,
            // reading as an elapsed-duration clock ("01:00"/"01:00") rather than the session's real
            // start/end hours ("23:32"/"07:00" — the register's own expected figures).
            axisStartLabel = hourMinuteLabel(sessionStartedAtUtc),
            axisEndLabel = (sessionEndedAtUtc ?: nowMillis).let { hourMinuteLabel(it) },
            notListeningLabel = notListeningLabel(gaps),
            missingModel = if (asrAvailable) null else missingModel,
            worthKnowing = worthKnowing(details, gaps, firstHeardStationIds),
            stations = stationsSection(details),
        )
    }

    /** `Now-Idle.dc.html`. */
    public fun idle(
        lastSessionSummaryLabel: String?,
        inputLabel: String?,
        rigLabel: String?,
        tierLabel: String?,
        earlierNights: List<EarlierNightRow>,
        canGetBetter: CanGetBetterRow?,
    ): NowViewState.Idle = NowViewState.Idle(
        lastSessionSummaryLabel = lastSessionSummaryLabel,
        inputLabel = inputLabel,
        rigLabel = rigLabel,
        tierLabel = tierLabel,
        earlierNights = earlierNights,
        canGetBetter = canGetBetter,
    )

    /**
     * The **legacy bridge** for [org.ort.app.ui.screens.NowScreen]'s deprecated
     * `NowScreen(status, summary, ...)` overload — see that overload's own doc comment for why it
     * still exists (`app/src/main/kotlin/org/ort/app/ui/navigation/OrtNavHost.kt`'s own inline
     * `NowContent`, WP3's file, has not yet been repointed at this package's real
     * `NowContent`/`NowViewState`). Deliberately minimal: an idle-shaped state carrying only what
     * the two legacy parameters actually contain, never inventing the richer facts only the real
     * mapper above can honestly compute.
     */
    public fun legacyFrom(overCount: Int, stationCount: Int, isCapturing: Boolean): NowViewState = if (!isCapturing) {
        NowViewState.Idle(
            lastSessionSummaryLabel = null,
            inputLabel = null,
            rigLabel = null,
            tierLabel = null,
            earlierNights = emptyList(),
            canGetBetter = null,
        )
    } else {
        NowViewState.Active(
            sessionTitle = if (overCount == 0) "Tonight" else "Overnight",
            summaryLabel = "$overCount overs · $stationCount stations",
            overCount = overCount,
            activityPattern = emptyList(),
            axisStartLabel = null,
            axisEndLabel = null,
            notListeningLabel = null,
            missingModel = null,
            worthKnowing = emptyList(),
            stations = NowStationsSection(0, emptyList(), null, "Nothing yet."),
        )
    }

    private fun worthKnowing(
        details: List<TransmissionDetail>,
        gaps: List<GapWindow>,
        firstHeardStationIds: Set<String>,
    ): List<WorthKnowingItem> {
        val items = mutableListOf<WorthKnowingItem>()

        for (stationId in firstHeardStationIds) {
            val forStation = details.filter { it.attribution.stationId == stationId }
            if (forStation.isEmpty()) continue
            val callsign = stationId
            val earliest = forStation.minOf { it.startedAtUtcMillis }
            items += WorthKnowingItem(
                tone = WorthKnowingTone.NOMINAL,
                headline = "$callsign heard for the first time",
                subLine = "first time heard · ${hourMinuteLabel(earliest)} · ${forStation.size} overs",
            )
        }

        val unresolved = details.count {
            it.attribution.state == AttributionState.AMBIGUOUS || it.attribution.state == AttributionState.UNKNOWN
        }
        if (unresolved > 0) {
            items += WorthKnowingItem(
                tone = WorthKnowingTone.DEGRADED,
                headline = "$unresolved over" + (if (unresolved == 1) "" else "s") +
                    " could not be attributed with confidence",
                subLine = "this session",
            )
        }

        val longestGap = gaps.maxByOrNull { (it.endedAt ?: it.startedAt) - it.startedAt }
        if (longestGap != null) {
            val durationSeconds = ((longestGap.endedAt ?: longestGap.startedAt) - longestGap.startedAt) / 1000
            if (durationSeconds > 0) {
                items += WorthKnowingItem(
                    tone = WorthKnowingTone.DEGRADED,
                    headline = "A gap of ${durationLabel(durationSeconds)}",
                    subLine = "not listening · ${hourMinuteLabel(longestGap.startedAt)}",
                )
            }
        }

        return items
    }

    private fun stationsSection(details: List<TransmissionDetail>): NowStationsSection {
        val attributed = details.filter { it.attribution.stationId != null }
        val byStation = attributed.groupBy { it.attribution.stationId!! }
        val rows = byStation.map { (stationId, forStation) ->
            val hasConfirmed = forStation.any { it.attribution.state == AttributionState.CONFIRMED }
            val marker = if (hasConfirmed) AttributionState.CONFIRMED else AttributionState.INFERRED
            val countLabel = if (hasConfirmed) {
                "${forStation.size} over" + if (forStation.size == 1) "" else "s"
            } else {
                "${forStation.size} by voice match"
            }
            val lastTime = forStation.maxOf { it.startedAtUtcMillis }
            NowStationRow(
                stationId = stationId,
                marker = marker,
                callsign = stationId,
                countLabel = countLabel,
                lastTimeLabel = hourMinuteLabel(lastTime),
            )
        }.sortedByDescending { row -> byStation.getValue(row.stationId).size }

        val unidentifiedCount = details.size - attributed.size
        return NowStationsSection(
            totalCount = rows.size,
            rows = rows,
            unidentifiedLabel = if (unidentifiedCount > 0) unidentifiedVoicesLabel(unidentifiedCount) else null,
            emptyMessage = if (rows.isEmpty() && unidentifiedCount == 0) "None yet." else null,
        )
    }

    /**
     * R-176: `Main.dc.html` says "voices", not "overs" — every over from an unattributed
     * transmission is, on the air, one speaker, so counting overs here is the same count the
     * board's wording implies. It is not a distinct-voice/voiceprint-clustering count (this
     * package reads no such signal — [NowStationsSection]'s own doc comment says so), so two
     * unidentified overs from what was actually the same unknown speaker still read as
     * "2 unidentified voices" here, same as the board's own literal wording — a labelling choice
     * made by the design lead (R-176), not a claim this code verifies.
     */
    private fun unidentifiedVoicesLabel(count: Int): String = "$count unidentified voice" + if (count == 1) "" else "s"

    private fun notListeningLabel(gaps: List<GapWindow>): String? {
        val totalSeconds = gaps.sumOf { ((it.endedAt ?: it.startedAt) - it.startedAt) / 1000 }
        return if (totalSeconds > 0) durationLabel(totalSeconds) else null
    }

    private fun durationLabel(totalSeconds: Long): String =
        if (totalSeconds < 60) "${totalSeconds}s" else "${totalSeconds / 60}m ${totalSeconds % 60}s"

    private fun hourMinuteLabel(utcMillis: Long): String {
        val totalMinutes = Math.floorDiv(utcMillis, 60_000L)
        val hour = Math.floorMod(totalMinutes / 60, 24L)
        val minute = Math.floorMod(totalMinutes, 60L)
        return "%02d:%02d".format(Locale.ROOT, hour, minute)
    }
}
