package org.ort.app.ui.recordings

import org.ort.pipeline.capture.ArchiveWriteRateForecast
import org.ort.pipeline.capture.OverAudioBudgetState

/**
 * `Recordings.dc.html` (RC01, design-intent row RC01): view-states for the Recordings destination.
 * Every fact is assembled by [RecordingsPolling]/[RecordingsViewStateMapper] from real sources —
 * `org.ort.pipeline.archive.recordingSessionSummaries`, `org.ort.pipeline.capture
 * .measureStorageAccounting`/`overAudioBudgetState`, `org.ort.pipeline.capture.ArchiveSettingsStore`
 * and `org.ort.pipeline.capture.ArchiveWriteRateForecast` — no artboard example number is ever
 * hardcoded into a screen (constitution I).
 */
public data class OverAudioCardViewState(
    public val usedBytes: Long,
    public val budgetGb: Int?,
    /** `null` when [budgetGb] is `null` — no bar is drawn against a budget that was never set
     * (matches `StorageFooter`'s own existing rule in `Drawer.kt`). */
    public val fractionUsed: Float?,
    /** D40/AC-157: real, recomputed every read — never a one-time event. */
    public val exceeded: Boolean,
)

public data class ArchiveCardViewState(
    public val enabled: Boolean,
    public val usedBytes: Long,
    public val budgetGb: Int,
    public val fractionUsed: Float,
    /** "about 15 GB a month (estimated)" before anything has been measured this session, or the
     * real measured figure once [org.ort.pipeline.capture.ArchiveWriteRateForecast] has one —
     * [isMeasuredRate] is carried alongside so a caller never has to parse this string to tell
     * which it is (constitution VI: never show an estimate as a measurement, or the reverse). */
    public val monthlyRateLabel: String,
    public val isMeasuredRate: Boolean,
)

public data class RecordingsBudgetsViewState(
    public val overAudio: OverAudioCardViewState,
    public val archive: ArchiveCardViewState,
)

/** [RecordingsViewStateMapper.map]'s own budget-side inputs, bundled so that function stays under
 * detekt's `LongParameterList` — the same "bundle the request" shape
 * `org.ort.pipeline.label.TransmissionLabelFields` already uses for the same reason. */
public data class RecordingsBudgetInputs(
    public val overAudioBudget: OverAudioBudgetState,
    public val archiveEnabled: Boolean,
    public val archiveBudgetGb: Int,
    public val archiveRateState: ArchiveWriteRateForecast.State,
)

/** RC01's four chips (design-intent: "Chips filter"). */
public enum class RecordingsFilter { ALL, HAS_FAILURES, LABELLED, ARCHIVE_REMOVED }

public data class RecordingsFilterChipViewState(
    public val filter: RecordingsFilter,
    public val label: String,
    /** `null` only for [RecordingsFilter.ALL], which carries no count of its own on the board. */
    public val count: Int?,
)

/** RC01's own badge vocabulary (`.badges` column, `Recordings.dc.html`) — a closed set so a row
 * never has to be read back out of formatted prose to tell which fact it names. [ARCHIVE_REMOVED]
 * and [OVER_AUDIO_REMOVED] are deliberately distinct kinds: one is the raw continuous archive being
 * pruned by the archive budget, the other is the operator's own over-audio deletion (RC02's
 * `Delete`) — the same distinction the build-plan brief requires RC01/RC02 to carry wherever removed
 * audio is shown, never collapsed into one "removed" badge that would hide which policy did it. */
public enum class RecordingBadgeKind { CAPTURING, FAILED, LABELLED, ARCHIVE_KEPT, ARCHIVE_REMOVED, OVER_AUDIO_REMOVED }

public data class RecordingBadgeViewState(public val kind: RecordingBadgeKind, public val label: String)

public data class RecordingsSessionRowViewState(
    public val id: String,
    public val label: String,
    public val timeRangeLabel: String,
    public val durationLabel: String,
    public val overCount: Int,
    public val stationCount: Int,
    public val gapCount: Int,
    public val failedCount: Int,
    public val labelledCount: Int,
    public val isLive: Boolean,
    public val badges: List<RecordingBadgeViewState>,
    /** The real fact the month section headers (`This week` / `August`) are computed from — never
     * a display-only label the row would otherwise have no reason to carry (mirrors
     * `SessionRowViewState.startedAtUtc`'s own kdoc). */
    public val startedAtUtc: Long,
)

public data class RecordingsViewState(
    public val headline: String,
    public val budgets: RecordingsBudgetsViewState,
    public val filters: List<RecordingsFilterChipViewState>,
    public val selectedFilter: RecordingsFilter,
    /** Already filtered to [selectedFilter] — the screen never re-filters what it is handed. */
    public val sessions: List<RecordingsSessionRowViewState>,
)
