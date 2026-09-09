package org.ort.app.ui.data

import org.ort.core.Attribution
import org.ort.data.entity.StationEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// -------------------------------------------------------------------------------------------
// Stations list (R-070, FR-UI-9) — Stations.dc.html.
// -------------------------------------------------------------------------------------------

/** The four chips atop `Stations.dc.html` (R-070). */
public enum class StationsFilter { TONIGHT, ALL_TIME, NAMED, UNIDENTIFIED }

/** A `NEW`/`CORRECTED` badge on a stations-list row (R-070, guide §6.14) — never the only copy of the fact. */
public enum class StationListBadge { NEW, CORRECTED }

/**
 * One row of the "Stations" list (R-070) — the station's **dominant attribution state tonight**
 * (the most recent session; [org.ort.app.ui.components.AttributionRow] draws it at 9dp), an
 * honest count context built only from what the split of confirmed/inferred overs actually shows
 * ("12 by voice match · 3 heard") — never a fabricated "net control" this data cannot support —
 * plus any badge and the operator-given name shown beside the callsign.
 */
public data class StationListEntryViewState(
    val stationId: String,
    val label: String,
    val transmissionCount: Int,
    val lastHeardLabel: String?,
    val attribution: Attribution = Attribution.unknown(),
    /** "48 overs · net control" or "12 by voice match · 3 heard" — see this file's mapper doc comment. */
    val countContext: String = "",
    val badge: StationListBadge? = null,
    val givenName: String? = null,
    /** Whether this station was heard at all in the most recent session — the "Tonight" chip's filter. */
    val heardTonight: Boolean = false,
)

/**
 * The trailing "N unidentified voices · M overs" row (R-070) — a real aggregate, never a station
 * row. [voiceCount] is `null` — never `overCount`'s fallback stand-in — when the voice-clustering
 * pipeline (M4) has not written any `transmission.voiceprintId` for these overs yet: an unknown
 * over count is real without it, but a distinct-voice count is not (constitution I).
 */
public data class UnidentifiedVoicesSummary(val voiceCount: Int?, val overCount: Int)

/**
 * `StationsListScreen`'s own fetched state (R-070/R-207) — folded into one holder (rather than
 * four separate parameters) so that screen's own parameter count stays under detekt's
 * `LongParameterList` limit. [heardAllTimeCount]/[heardTonightCount] are the real, unfiltered
 * totals — see `StationsContent`'s own doc comment for why they are computed there rather than
 * derived from [stations] (already filtered to the selected chip).
 */
public data class StationsListState(
    val stations: List<StationListEntryViewState>,
    val unidentified: UnidentifiedVoicesSummary? = null,
    val heardAllTimeCount: Int = 0,
    val heardTonightCount: Int = 0,
)

// -------------------------------------------------------------------------------------------
// Station-Pattern (R-072, R-075) — Station-Pattern.dc.html.
// -------------------------------------------------------------------------------------------

/** The `By hour` / `Hour × day` / `Change over time` toggle on `Station-Pattern.dc.html` (R-072). */
public enum class PatternMode { BY_HOUR, HOUR_BY_DAY, CHANGE_OVER_TIME }

/** `Station-Pattern.dc.html`'s whole state (R-072, R-075) — every bucketing here is in local time. */
public data class StationPatternViewState(
    val subjectId: String,
    val label: String,
    val hourPattern: List<HourActivityBucket>,
    val hourByDay: List<HourByDayActivityCell>,
    val weekOverWeekSummary: List<String>,
    val whatThisSays: List<String>,
    /** "14 nights of listening, 25 Aug – 7 Sep" (R-210) — `Station-Pattern.dc.html`'s own subtitle,
     * never "Local time" (a fact about the zone, not the one an operator actually wants here). */
    val nightsSubtitle: String = "",
)

// -------------------------------------------------------------------------------------------
// Station detail (R-071, FR-UI-9) — Station.dc.html.
// -------------------------------------------------------------------------------------------

/**
 * Which sub-screen `StationDetailContent` opens on — `Station-Pattern` (reached from "By day"),
 * `Station-Identity` (reached from the header's kebab) and `Split` (reached from
 * Station-Identity's own "Split" action), or `NONE` for the drill-in's own root. Exposed (not
 * `private`) so a caller can seed `StationDetailContent`'s `initialSubScreen` directly — WP12's
 * screenshot tour (`ScenarioReaderActivity`'s `nav_open_station_sub_screen` extra, via WP3's
 * `NavSeed.openStationSubScreen`) captures ST03/ST04 this way, without a real user tap.
 */
public enum class StationSubScreen { NONE, PATTERN, IDENTITY, SPLIT }

/**
 * Everything heard from one station, across every session (FR-UI-9), plus its activity pattern
 * (FR-UI-11 — see [ActivityPatternMapper] for FR-UI-12's not-heard/not-listening distinction,
 * which this view state renders rather than re-derives) and the facts-table figures R-071 asks
 * for: the confirmed/inferred/corrected split, which frequencies this station uses and how often,
 * and first/last heard.
 */
public data class StationDetailViewState(
    val stationId: String,
    val label: String,
    val givenName: String? = null,
    /** The real dominant attribution across every session this station has ever been heard in
     * (R-208) — the same figure [StationListEntryViewState.attribution] carries on the list row
     * that opened this screen, so the two can never disagree. */
    val attribution: Attribution = Attribution.unknown(),
    /** "Heard 14 nights of 14" (R-208) — never a fabricated net-control role this package cannot
     * honestly derive; see this package's report. */
    val contextSentence: String = "",
    val transmissionCount: Int,
    val transmissionCountTonight: Int = 0,
    val confirmedCount: Int = 0,
    val inferredCount: Int = 0,
    val correctedCount: Int = 0,
    /** "145.230 almost always · 146.960 twice" — see [StationViewMapper.frequencySummary]. */
    val frequenciesSummary: String = "",
    val firstHeardLabel: String? = null,
    val lastHeardLabel: String? = null,
    val lastHeardSignalLabel: String? = null,
    val activityPattern: List<HourActivityBucket>,
    /** FR-UI-11's "by day of week" half (audit F-019). */
    val dayOfWeekPattern: List<DayOfWeekActivityBucket> = emptyList(),
    /** FR-UI-11's "and how that has changed" half (audit F-019) — one line per weekday, honest text only. */
    val weekOverWeekSummary: List<String> = emptyList(),
    /** R-071's one-sentence pattern summary ("Peaks 21:00–23:00.") — [PatternInsights]'s first line. */
    val patternSummarySentence: String = "",
    val transmissions: List<TransmissionListEntryViewState>,
)

// -------------------------------------------------------------------------------------------
// Frequencies list (R-074, FR-UI-10) — Frequencies.dc.html.
// -------------------------------------------------------------------------------------------

/**
 * One row of the "Frequencies" list (R-074). [whatItIs] is band/mode **where known, never
 * guessed** — repeater-vs-simplex has no supporting column anywhere in `:data` today (this
 * package's report names the gap), so it is omitted rather than asserted.
 */
public data class FrequencyListEntryViewState(
    val frequencyHz: Long,
    val label: String,
    val transmissionCount: Int,
    val whatItIs: String = "",
    val tonightCount: Int = 0,
    val tonightStationCount: Int = 0,
    /** `Frequencies.dc.html`'s 14-night sparkline (R-074) — [org.ort.app.ui.components.Sparkline]. */
    val nights: List<HourActivityState> = emptyList(),
    /** The same test [FrequencyChangeViewState] uses to decide whether tonight gets its own screen. */
    val busierThanUsual: Boolean = false,
)

// -------------------------------------------------------------------------------------------
// Frequency detail (R-074, FR-UI-10) — Frequency.dc.html.
// -------------------------------------------------------------------------------------------

/** One `REGULARS` row on `Frequency.dc.html` (R-074). */
public data class FrequencyRegularViewState(
    val stationId: String,
    val label: String,
    val attribution: Attribution,
    /** "612 overs · 14 of 14 nights" or "201 overs · Tuesdays only". */
    val countContext: String,
    val lastHeardLabel: String?,
)

/**
 * `Frequency.dc.html`'s `Nets` row (R-074, R-216) — a recurring weekly time slot this package can
 * honestly call a net: the same (day-of-week, local hour) has real activity in at least half of
 * the calendar weeks this frequency has been heard on, with one station's `CONFIRMED` count
 * dominant in that slot often enough to name as control. `null` from
 * [FrequencyPolling.frequencyDetail] — never a fabricated one — when no such recurring pattern
 * exists (see this package's report for the heuristic's own limits).
 */
public data class FrequencyNetViewState(
    val dayOfWeek: DayOfWeek,
    val hourOfDay: Int,
    val controlStationId: String?,
    val weeksSeen: Int,
    val weeksTotal: Int,
)

/** Everything heard on one frequency, across every session (FR-UI-10), plus its activity pattern (FR-UI-11). */
public data class FrequencyDetailViewState(
    val frequencyHz: Long,
    val label: String,
    val whatItIs: String = "",
    val transmissionCount: Int,
    val transmissionCountTonight: Int = 0,
    val stationCountAllTime: Int = 0,
    val stationCountTonight: Int = 0,
    val activityPattern: List<HourActivityBucket>,
    /** FR-UI-11's "by day of week" half (audit F-019). */
    val dayOfWeekPattern: List<DayOfWeekActivityBucket> = emptyList(),
    /** FR-UI-11's "and how that has changed" half (audit F-019) — one line per weekday, honest text only. */
    val weekOverWeekSummary: List<String> = emptyList(),
    /** R-074's typical-night one-sentence summary — [PatternInsights]'s first line. */
    val patternSummarySentence: String = "",
    /**
     * R-431 (register, design): how many real distinct nights [activityPattern] was averaged
     * over — the same session count `listenedLabel` already names, never the board's own literal
     * "14" hardcoded (this frequency may honestly have more or fewer). Used only by the
     * "no hatch: always listening here" caption, `FrequencyHeaderSection`'s own doc comment names
     * why that caption exists at all.
     */
    val patternNightsCount: Int = 0,
    val regulars: List<FrequencyRegularViewState> = emptyList(),
    val transmissions: List<TransmissionListEntryViewState>,
    val nights: List<HourActivityState> = emptyList(),
    val busierThanUsual: Boolean = false,
    /** `Frequency.dc.html`'s `Listened` row (R-074, R-216) — "14 nights of 14 · 96 h total". */
    val listenedLabel: String = "",
    /** `Frequency.dc.html`'s `Nets` row (R-074, R-216) — absent, never fabricated, when this
     * package finds no recurring weekly pattern (see [FrequencyNetViewState]'s own doc comment). */
    val net: FrequencyNetViewState? = null,
)

// -------------------------------------------------------------------------------------------
// Frequency-Change (R-074) — Frequency-Change.dc.html.
// -------------------------------------------------------------------------------------------

/** One `WHAT MADE IT BUSY` line on `Frequency-Change.dc.html` (R-074) — honestly derived only. */
public data class FrequencyChangeCause(val label: String, val isUnidentified: Boolean = false)

/**
 * A real, honestly-derived time window (R-276, register) — `FrequencyChangeScreen`'s "The N
 * overs" action hands this to `onOpenOvers` alongside the frequency, so the host (WP3) can filter
 * the Log to just this frequency and this window rather than everything ever heard on it.
 */
public data class TimeWindow(val startMillis: Long, val endMillis: Long)

/**
 * Which sub-screen `FrequencyDetailContent` opens on. Exposed (not `private`) so a caller —
 * `OrtNavHost` (WP3) reopening this drill-in after a round trip through the Log, R-276's
 * `onOpenOvers` destination — can seed `FrequencyDetailContent`'s `initialView` and land the
 * operator back where they left, on `Frequency-Change`, rather than always on the drill-in's own
 * root.
 */
public enum class FrequencyDetailView { Detail, Change }

/**
 * `Frequency-Change.dc.html`'s state (R-074): tonight's per-hour counts plotted as bars over the
 * usual per-hour average as a line, and the causes this package can honestly derive — a first-time
 * station heard tonight on this frequency, and any weak/unidentified activity. Cross-frequency
 * migration ("5 regulars moved here from 145.230") needs a thread/session-wide correlation this
 * package's read path does not build; left out rather than guessed (see this package's report).
 */
public data class FrequencyChangeViewState(
    val frequencyHz: Long,
    val label: String,
    val subtitleLabel: String,
    val tonightHourly: List<Int>,
    val usualHourly: List<Double>,
    /** "usual, N nights" (R-274) — how many prior nights the usual-hourly average was folded over. */
    val usualNightsCount: Int = 0,
    val causes: List<FrequencyChangeCause>,
    val overCount: Int,
    /** The board's full closing paragraph (R-275) — never just its first sentence. */
    val explanationParagraph: String,
    /** Tonight's own session window (R-276) — real, not the narrower departure window
     * [subtitleLabel] names; "The N overs" opens the Log to everything this frequency heard in
     * this window, not just the hours that spiked. */
    val window: TimeWindow = TimeWindow(0L, 0L),
)

// -------------------------------------------------------------------------------------------
// Station-Identity (R-073) — Station-Identity.dc.html.
// -------------------------------------------------------------------------------------------

/** `Station-Identity.dc.html`'s "Voice" section (R-073) — real where the identity pipeline writes data. */
public data class StationVoiceViewState(
    val clusterOverCount: Int,
    val confirmedCount: Int,
    val inferredCount: Int,
    /**
     * Null when no cross-station voice-distance comparison exists — the identity/voiceprint
     * pipeline does not yet compute or store one (M4, not built); rendered as "not computed" per
     * this file's own mapper, never a fabricated number (constitution I).
     */
    val nearestOtherStationId: String? = null,
    val nearestOtherDistance: Double? = null,
    /**
     * R-572 (register, polish): `Station-Identity.dc.html`'s own Voiceprint sub-line ends "…
     * stable since <date>" — the real first-seen date of the over that is earliest in the
     * *current* bound cluster (the same cluster [StationPolling.voiceSplitCandidates] would
     * split), not a fabricated "since enrolment" or "since first heard at all" figure this
     * package cannot honestly derive from one voiceprint alone. `null` — the sub-line then omits
     * the clause entirely — when no voiceprint is bound to this station at all.
     */
    val stableSinceLabel: String? = null,
)

/** `Station-Identity.dc.html`'s "Given by you" section (R-073) — `StationEntity.userName`/`notes`. */
public data class StationGivenByYouViewState(val name: String?, val note: String?)

/** `Station-Identity.dc.html`'s whole state (R-073, FR-SPK-10, constitution III). */
public data class StationIdentityViewState(
    val stationId: String,
    val callsign: String,
    val heardOverCount: Int,
    val lexiconLabel: String?,
    val voice: StationVoiceViewState,
    val givenByYou: StationGivenByYouViewState,
)

// -------------------------------------------------------------------------------------------
// Split (R-073) — Fail-Cluster.dc.html, reached from Station-Identity's "Split" action.
// -------------------------------------------------------------------------------------------

/**
 * One over in `Fail-Cluster.dc.html`'s chooser (R-073). [isAnchor] is true exactly when
 * [attribution] is `CONFIRMED` — the callsign was heard directly in this over, so per the
 * artboard's own copy ("Overs where the callsign was heard cannot be moved — those are the
 * anchor") its checkbox is never interactive.
 */
public data class VoiceprintSplitOverViewState(
    val transmissionId: String,
    val timeLabel: String,
    val transcriptText: String,
    val attribution: Attribution,
    val isAnchor: Boolean,
    /** The real `transmission.corrected` column — true if an earlier correction already touched this over. */
    val corrected: Boolean,
)

/**
 * `Fail-Cluster.dc.html`'s whole state (R-073) — the real overs in [fromVoiceprintId]'s cluster,
 * oldest-write-first is not required here (unlike the artboard's own scoring-sorted list, this
 * package has no real per-over voiceprint-distance score to sort by — never a fabricated "least
 * like the rest" pre-tick; see this package's report). Ticking a checkbox and confirming calls
 * [org.ort.app.ui.data.StationPolling.splitVoiceprint] with the chosen [VoiceprintSplitOverViewState.transmissionId]s.
 */
public data class StationVoiceSplitViewState(
    val stationId: String,
    val callsign: String,
    val fromVoiceprintId: String,
    val overs: List<VoiceprintSplitOverViewState>,
)

public object StationViewMapper {

    public fun listEntry(station: StationEntity): StationListEntryViewState = StationListEntryViewState(
        stationId = station.id,
        label = station.callsign ?: station.id,
        transmissionCount = station.transmissionCount,
        lastHeardLabel = dateTimeLabel(station.lastHeardAt),
        givenName = station.userName,
    )

    public fun detail(
        stationId: String,
        label: String,
        transmissions: List<TransmissionDetail>,
        activityPattern: List<HourActivityBucket>,
        dayOfWeekPattern: List<DayOfWeekActivityBucket> = emptyList(),
        weekOverWeekComparison: List<WeekOverWeekBucket> = emptyList(),
    ): StationDetailViewState = StationDetailViewState(
        stationId = stationId,
        label = label,
        transmissionCount = transmissions.size,
        activityPattern = activityPattern,
        dayOfWeekPattern = dayOfWeekPattern,
        weekOverWeekSummary = weekOverWeekSummaryText(weekOverWeekComparison),
        patternSummarySentence = PatternInsights.build(activityPattern, dayOfWeekPattern).firstOrNull().orEmpty(),
        transmissions = transmissions.map { ReaderTransmissionViewStateMapper.listEntry(it) },
    )

    /**
     * "12 by voice match · 3 heard" (`Stations.dc.html`'s own worked example, R-070) from a real
     * confirmed/inferred split — never "net control", which has no supporting data anywhere in
     * `:data` (this package's report names the gap; the brief itself only asks for it "if data
     * supports it"). A confirmed-only count reads as plain overs ("48 overs"); once the split is
     * mixed, the confirmed share is phrased as "heard" — CONFIRMED means literally heard in that
     * transmission, which is exactly the distinction this sentence exists to carry.
     */
    public fun countContext(confirmedCount: Int, inferredCount: Int): String = when {
        confirmedCount == 0 && inferredCount == 0 -> "0 overs"
        inferredCount == 0 -> pluralOvers(confirmedCount)
        confirmedCount == 0 -> "$inferredCount by voice match"
        else -> "$inferredCount by voice match · $confirmedCount heard"
    }

    private fun pluralOvers(count: Int): String = if (count == 1) "1 over" else "$count overs"

    /** "145.230 almost always · 146.960 twice" — [frequencyCounts] most-used first. */
    public fun frequencySummary(frequencyCounts: List<Pair<Long, Int>>): String {
        if (frequencyCounts.isEmpty()) return ""
        val total = frequencyCounts.sumOf { it.second }
        val sorted = frequencyCounts.sortedByDescending { it.second }
        return sorted.joinToString(" · ") { (hz, count) ->
            val label = "%.3f".format(Locale.ROOT, hz / 1_000_000.0)
            val share = if (total > 0) count.toDouble() / total else 0.0
            val qualifier = when {
                share >= 0.85 -> "almost always"
                count == 1 -> "once"
                count == 2 -> "twice"
                else -> "$count times"
            }
            "$label $qualifier"
        }
    }
}

public object FrequencyViewMapper {

    public fun listEntry(frequencyHz: Long, transmissionCount: Int): FrequencyListEntryViewState =
        FrequencyListEntryViewState(
            frequencyHz = frequencyHz,
            label = frequencyLabel(frequencyHz),
            transmissionCount = transmissionCount,
        )

    public fun detail(
        frequencyHz: Long,
        transmissions: List<TransmissionDetail>,
        activityPattern: List<HourActivityBucket>,
        dayOfWeekPattern: List<DayOfWeekActivityBucket> = emptyList(),
        weekOverWeekComparison: List<WeekOverWeekBucket> = emptyList(),
    ): FrequencyDetailViewState = FrequencyDetailViewState(
        frequencyHz = frequencyHz,
        label = frequencyLabel(frequencyHz),
        transmissionCount = transmissions.size,
        activityPattern = activityPattern,
        dayOfWeekPattern = dayOfWeekPattern,
        weekOverWeekSummary = weekOverWeekSummaryText(weekOverWeekComparison),
        patternSummarySentence = PatternInsights.build(activityPattern, dayOfWeekPattern).firstOrNull().orEmpty(),
        transmissions = transmissions.map { ReaderTransmissionViewStateMapper.listEntry(it) },
    )

    /**
     * "Repeater · 2 m · FM" where `:data` supports each part, and only that part — R-074's own
     * "never guessed" rule. Band comes from [org.ort.data.Band.of]; mode from the most common
     * non-null `transmission.mode` on this frequency; repeater-vs-simplex has no column anywhere
     * in `:data` today and is never asserted (this package's report names the gap).
     */
    public fun whatItIs(frequencyHz: Long, modes: List<String>): String {
        val band = org.ort.data.Band.of(frequencyHz)
        val bandLabel = band?.let { bandShortLabel(it) }
        val modeLabel = modes.filter { it.isNotBlank() }
            .groupingBy { it }.eachCount().entries
            .maxByOrNull { it.value }?.key
        return listOfNotNull(bandLabel, modeLabel).joinToString(" · ")
    }

    private fun bandShortLabel(band: org.ort.data.Band): String = when (band) {
        org.ort.data.Band.HF_160M -> "160 m"
        org.ort.data.Band.HF_80M -> "80 m"
        org.ort.data.Band.HF_60M -> "60 m"
        org.ort.data.Band.HF_40M -> "40 m"
        org.ort.data.Band.HF_30M -> "30 m"
        org.ort.data.Band.HF_20M -> "20 m"
        org.ort.data.Band.HF_17M -> "17 m"
        org.ort.data.Band.HF_15M -> "15 m"
        org.ort.data.Band.HF_12M -> "12 m"
        org.ort.data.Band.HF_10M -> "10 m"
        org.ort.data.Band.VHF_6M -> "6 m"
        org.ort.data.Band.VHF_2M -> "2 m"
        org.ort.data.Band.VHF_1_25M -> "1.25 m"
        org.ort.data.Band.UHF_70CM -> "70 cm"
        org.ort.data.Band.UHF_33CM -> "33 cm"
        org.ort.data.Band.UHF_23CM -> "23 cm"
    }

    private fun frequencyLabel(frequencyHz: Long): String = "%.3f MHz".format(Locale.ROOT, frequencyHz / 1_000_000.0)
}

/**
 * Local-zone, no zone-name suffix (R-207/R-208, register, V5 @f8430b8): every timestamp this app
 * shows is already local (R-075), so a literal "UTC" on some of them was simply wrong, not a
 * missing label — and R-210 found that even spelling out "local" reads as clutter once every
 * timestamp is consistently local; the absence of a suffix *is* the convention. Internal (not
 * private) so [StationPolling]/[FrequencyPolling], in their own file, format the same way.
 */
internal val LOCAL_DATETIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
    .withZone(ZoneId.systemDefault())

/** The Stations/Frequencies list row's mono last-heard column (R-207) — a bare local "HH:mm". */
internal val LOCAL_HHMM_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    .withZone(ZoneId.systemDefault())

/** `Station-Identity.dc.html`'s own Voiceprint "stable since <date>" clause (R-572, register) —
 * [Locale.getDefault], never [Locale.ROOT] (R-170, register: `Locale.ROOT` renders a raw numeric
 * month, "M09", on this JVM instead of a real month name — the same fix [StationPolling]'s own
 * `nightsSubtitle` already carries). */
internal val STABLE_SINCE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    .withZone(ZoneId.systemDefault())

private fun dateTimeLabel(utcMillis: Long?): String? =
    utcMillis?.let { LOCAL_HHMM_FORMAT.format(Instant.ofEpochMilli(it)) }

/**
 * Turns FR-UI-11's "how that has changed" comparison into the one honest sentence per weekday
 * (audit F-019) — text only, never a chart, since a single-week-over-week sample is too thin to
 * plot as a trend line without implying more precision than it has.
 */
public fun dayOfWeekShortLabel(dayOfWeek: java.time.DayOfWeek): String =
    dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ROOT)

internal fun weekOverWeekSummaryText(buckets: List<WeekOverWeekBucket>): List<String> = buckets.map { bucket ->
    val day = bucket.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ROOT)
    when (bucket.trend) {
        WeekTrend.NO_DATA -> "$day: no data"
        WeekTrend.UP -> "$day: up (${bucket.currentHeardCount} vs ${bucket.previousHeardCount} last week)"
        WeekTrend.DOWN -> "$day: down (${bucket.currentHeardCount} vs ${bucket.previousHeardCount} last week)"
        WeekTrend.FLAT -> "$day: flat (${bucket.currentHeardCount})"
    }
}
