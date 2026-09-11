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
     * audio by cable | Bluetooth audio>" from the session's own v7 columns, or (R-914 amendment)
     * "not recorded for this session" for a row whose own `captureMode` column is null — never
     * fabricated for either. */
    val modeLabel: String = NOT_TRACKED_LABEL,
    /** E2-G03: "<transport label>" from the session's own `rigTransport` column, "no rig this
     * session" for a v7-onward session that genuinely ran without one (`LOCAL_MICROPHONE`), or
     * (R-914 amendment) the same "not recorded for this session" line for a row with no `rigTransport`
     * column at all. No rig-event history exists in `:data` today to name a stale span from
     * (constitution I) — always just the transport alone.
     * **R-833 amendment**: for a *currently live* session ([live]), leads with the rig's own real
     * name read live off `RigStatus` (WPC2) — the same "prefer live, fall back to the session
     * column" pattern N04/F9 already use — since `:data` itself has no persisted rig-descriptor id
     * to fall back to for a past session (only the transport kind). */
    val rigLinkLabel: String = NOT_TRACKED_LABEL,
    /** R-824 (register, halt): `true` exactly when this is the session `CaptureState` itself
     * reports as currently capturing — the identical fact [SessionRowViewState.live] already
     * carries for the row list, computed the same way. A live session's own header must say so and
     * never claim an end time or a clean/unclean termination it has not reached yet. */
    val live: Boolean = false,
    /** R-844 (register, DG04 coverage bar, guide §8): the coverage chart's own real start/end mono
     * clock labels — guide §8 requires an axis label at each end of every activity chart, and
     * [org.ort.app.ui.components.ActivityPatternChart] already supports `axisStart`/`axisEnd`, just
     * unused by this screen's own call (so a session with no not-listening hours — a solid green
     * block — rendered no axis row at all, that composable's own gating). `null` (every caller
     * before this existed) renders exactly as before. */
    val coverageStartLabel: String? = null,
    val coverageEndLabel: String? = null,
) {
    public companion object {
        /** R-914 (register, spec): R-450's original line claimed the *build* cannot track this,
         * which stopped being true once schema v7 shipped — a v10 build genuinely does record
         * `captureMode`/`audioRouteKind`/`rigTransport` at session start, so a `null` column on a
         * real session row means that session's own row never recorded it (a pre-v7 row migrated
         * forward, or a genuine gap), never a build-wide limitation. Reused by
         * [modeLabel]/[rigLinkLabel]'s own defaults so a caller that predates E2-G03 (a fixture, an
         * older test) still reads a real, honest line — just this corrected one. */
        public const val NOT_TRACKED_LABEL: String = "not recorded for this session"
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
 * station, else R-931's (register, polish) honest "unnamed thread · N stations" — a real
 * multi-station QSO has no single station that gets naming rights over it, and this schema tracks
 * no net name to fall back to; never the bare word "Thread" (constitution I: never invent a
 * headline the deterministic pass did not produce). [detailLine]
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
public data class DigestProseSectionViewState(val cards: List<DigestProseCardViewState>, val footnote: String)

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
