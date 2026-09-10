package org.ort.app.ui.digest

import org.ort.app.ui.data.HourActivityBucket

/**
 * R-092 (register, FR-DIG-1..6, FR-RUN-12, FR-RUN-16): view-states for `Sessions`, `Session`,
 * `Digest`, `Digest-Item`. Every fact comes from [DigestPolling] reading real DAOs and process-wide
 * holders — no artboard example number is ever hardcoded into a screen.
 */
public data class SessionRowViewState(
    val id: String,
    val label: String,
    val timeRangeLabel: String,
    val overCount: Int,
    val stationCount: Int,
    val gapCount: Int,
    val uncleanEndLabel: String?,
    val tierChipLabel: String?,
    val canBeImproved: Boolean,
    val live: Boolean,
    /** R-144 (register, round 4 System validator): the real fact `Sessions.dc.html`'s month group
     * headers ("September", "August") are computed from — never a display-only label the row would
     * otherwise have no reason to carry. */
    val startedAtUtc: Long,
)

public data class SessionsViewState(val headline: String, val sessions: List<SessionRowViewState>)

public data class SessionGapRowViewState(
    val timeLabel: String,
    val durationLabel: String,
    val causeLabel: String,
    val resumedLabel: String,
)

public data class SessionDetailViewState(
    val id: String,
    val label: String,
    val timeRangeLabel: String,
    val durationLabel: String,
    val uncleanEndLabel: String?,
    val coverage: List<HourActivityBucket>,
    val notListeningLabel: String?,
    val gaps: List<SessionGapRowViewState>,
    val overCount: Int,
    val rejectedCount: Int,
    val failedCount: Int,
    val stationCount: Int,
    val frequencyLabels: List<String>,
    val inputLabel: String,
    val tierLabel: String,
    val audioSizeLabel: String,
    /** E2-G03 (DG04, FR-CAP-13, *amended 2026-09-10*): "<mode operator label> · <room audio |
     * audio by cable | Bluetooth audio>" from the session's own v7 columns, or R-450's original
     * "not tracked per session in this build" for a pre-v7 row — never fabricated for either. */
    val modeLabel: String = NOT_TRACKED_LABEL,
    /** E2-G03: "<transport label>" from the session's own `rigTransport` column, "no rig this
     * session" for a v7-onward session that genuinely ran without one (`LOCAL_MICROPHONE`), or
     * R-450's original not-tracked line for a pre-v7 row. No rig-event history exists in `:data`
     * today to name a stale span from (constitution I) — always just the transport alone. */
    val rigLinkLabel: String = NOT_TRACKED_LABEL,
) {
    public companion object {
        /** R-450's original honest line — reused by [modeLabel]/[rigLinkLabel]'s own defaults so a
         * caller that predates E2-G03 (a fixture, an older test) reads exactly as it always did. */
        public const val NOT_TRACKED_LABEL: String = "not tracked per session in this build"
    }
}

public data class DigestItemViewState(
    val id: String,
    val headline: String,
    val subLine: String,
    val reason: String,
    val ambiguousTone: Boolean,
    val transmissionIds: List<String>,
)

public data class DigestNotKnownItemViewState(val headline: String, val subLine: String)

/**
 * E2-G07 (DG05, FR-DIG-6, FR-DIG-11): one [org.ort.pipeline.digest.ProseSummary] rendered as a
 * card. [subject] is the thread's own station callsign when every over in it came from one
 * station, else the generic "Thread" — this reader has no other real per-thread label to show
 * (constitution I: never invent a headline the deterministic pass did not produce). [detailLine]
 * is the thread's own real over count. [fromMillis]/[toMillis] are [oversRangeLabel]'s own raw
 * values, carried alongside it so `Read the overs` can seed the Log's existing time-window filter
 * without re-parsing display text.
 */
public data class DigestProseCardViewState(
    val subject: String,
    val detailLine: String,
    val text: String,
    val oversRangeLabel: String,
    val fromMillis: Long,
    val toMillis: Long,
)

/** E2-G07: the whole "In their words" section — `null` on [DigestViewState.prose] entirely
 * (FR-DIG-3a) when [org.ort.pipeline.digest.ProseDigestSettings] is disabled or there is nothing
 * generated yet, never an empty section shown anyway. */
public data class DigestProseSectionViewState(
    val cards: List<DigestProseCardViewState>,
    val footnote: String,
)

public data class DigestViewState(
    val sessionId: String,
    val headline: String,
    val timeRangeLabel: String,
    val overCount: Int,
    val stationCount: Int,
    val bandCount: Int,
    val gapCount: Int,
    val items: List<DigestItemViewState>,
    val notKnown: List<DigestNotKnownItemViewState>,
    val attributedPercentLabel: String,
    val rejectedCount: Int,
    /** E2-G07 (DG05): `null` (every caller before this existed) is the honest, common case — the
     * deterministic digest above is byte-for-byte the same whether this is `null` or populated
     * (FR-DIG-3a). */
    val prose: DigestProseSectionViewState? = null,
)
