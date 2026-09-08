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
)

public data class DigestItemViewState(
    val id: String,
    val headline: String,
    val subLine: String,
    val reason: String,
    val ambiguousTone: Boolean,
    val transmissionIds: List<String>,
)

public data class DigestNotKnownItemViewState(val headline: String, val subLine: String)

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
)
