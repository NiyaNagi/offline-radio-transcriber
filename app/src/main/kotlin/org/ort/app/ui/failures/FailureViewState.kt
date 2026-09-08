package org.ort.app.ui.failures

/**
 * WP11b (spec/ui-conformance-plan.md, register R-100/R-101): one [FailureId] per artboard this
 * package owns (functional spec §12's F-numbering), and one immutable, pure view-state per id —
 * every `Fail*` composable in this package is a function of exactly one of these, never a
 * `Context` and never a live holder read directly (that reading happens once, in
 * [FailureMapper.map], so the composables stay trivially testable and match guide §"Every screen
 * composable is a pure function of a view-state").
 *
 * `F1`/`F16`/`F6` (at the storage floor) are full-screen takeovers (constitution IV: "never
 * continue silently"); `F2`/`F3`/`F5`/`F6` (the early warning)/`F7`/`F8`/`F9`/`F15` are banners
 * over whatever destination is current; `F14`/`F17` are small informational cards (green, not
 * amber — they report something that was handled correctly, not a degradation); `F19`/`F20`/
 * `F21`/`F22` are full standalone screens reachable only through [DebugFailureOverride] today —
 * see that file's kdoc for exactly why.
 */
public enum class FailureId {
    F1_ROUTE,
    F2_DISCONNECT,
    F3_LEVEL,
    F5_KILLED,
    F6_STORAGE_WARNING,
    F6_STORAGE_HALT,
    F7_THERMAL,
    F8_BACKLOG,
    F9_RIG,
    F14_CLOCK,
    F15_CALL,
    F16_USB,
    F17_INTERRUPTED,
    F19_RECONCILE,
    F20_MIGRATION,
    F21_ASSET_SWAP,
    F22_CALIBRATION,
}

// -------------------------------------------------------------------------------------------
// F1 — Fail-Route (takeover). InputStatus.State.Mismatch.
// -------------------------------------------------------------------------------------------

/** `Fail-Route.dc.html`. [expectedLabel]/[actualLabel] come straight off
 * [org.ort.pipeline.capture.InputStatus.State.Mismatch]. [elapsedLabel] and [oversKeptCount] are
 * the session's own facts (register R-126) — "H:MM:SS" since the session started, and the
 * transmission count for it — never invented when [sessionElapsedKnown] is false (no session to
 * measure against, e.g. the mismatch fired before any session ever opened). */
public data class RouteViewState(
    public val expectedLabel: String,
    public val actualLabel: String,
    public val sinceLabel: String,
    public val elapsedLabel: String,
    public val oversKeptCount: Int,
    public val sessionElapsedKnown: Boolean = true,
)

// -------------------------------------------------------------------------------------------
// F2 — Fail-Disconnect (banner). InputStatus.State.Lost.
// -------------------------------------------------------------------------------------------

/** `Fail-Disconnect.dc.html`. */
public data class DisconnectViewState(public val deviceLabel: String, public val sinceLabel: String)

// -------------------------------------------------------------------------------------------
// F3 — Fail-Level (banner). LevelStatus.State.Measured out of band.
// -------------------------------------------------------------------------------------------

public enum class LevelProblem { QUIET, CLIPPING }

/** `Fail-Level.dc.html`. [peakDbfs] is [org.ort.pipeline.capture.LevelStatus.State.Measured.peakDbfs]
 * verbatim — never rounded to a fabricated figure. */
public data class LevelViewState(
    public val problem: LevelProblem,
    public val peakDbfs: Float,
    public val sinceLabel: String,
)

// -------------------------------------------------------------------------------------------
// F5 — Fail-Killed (banner). The newest CaptureGapEntity with cause OS_STOPPED, closed.
// -------------------------------------------------------------------------------------------

/** `Fail-Killed.dc.html`. */
public data class KilledViewState(public val stoppedAtLabel: String, public val gapDurationLabel: String)

// -------------------------------------------------------------------------------------------
// F6 — Fail-Storage. StorageForecast.State.{ThreeNightsLeft,OneNightLeft} (warning, banner) and
// StorageForecast.State.AtFloor + CaptureState.State.Failed (halt, takeover).
// -------------------------------------------------------------------------------------------

/** `Fail-Storage.dc.html`'s "How this unfolded" timeline entry — [reached] draws the filled vs.
 * hollow dot the board itself uses for a stage that has/has not happened yet. */
public data class StorageTimelineStage(public val label: String, public val detail: String, public val reached: Boolean)

/** `Fail-Storage.dc.html`'s warning stage — "warned at N nights left". Register R-149: [timeline]
 * is built from every `StorageForecast` transition `FailureSignalsPolling` has actually observed
 * this process's lifetime (`FailureMapper.storageTimeline`) — never a fabricated multi-day
 * history; a night that has not been observed is not in the list. */
public data class StorageWarningViewState(
    public val nightsLeftLabel: String,
    public val freeLabel: String,
    public val timeline: List<StorageTimelineStage> = emptyList(),
)

/** `Fail-Storage.dc.html`'s hard-floor stage — capture has actually stopped. */
public data class StorageHaltViewState(public val freeLabel: String, public val floorLabel: String)

/** The exact "audio paused, transcripts continue" narrative `Fail-Storage.dc.html`'s title tells —
 * see [DebugFailureOverride]'s kdoc for why nothing in `:pipeline` can produce this today. */
public data class StorageAudioPausedViewState(
    public val freeLabel: String,
    public val pausedAtLabel: String,
    public val oversSinceCount: Int,
)

// -------------------------------------------------------------------------------------------
// F7 — Fail-Thermal (banner). ThermalStatus.State.{Warm,Hot} + ShedStatus.currentLevel.
// -------------------------------------------------------------------------------------------

/** `Fail-Thermal.dc.html`. [tier] is `3 - shedLevel`, matching `LiveBarPolling`'s own arithmetic. */
public data class ThermalViewState(
    public val tier: Int,
    public val sinceLabel: String,
    public val realTimeFactor: Double?,
)

// -------------------------------------------------------------------------------------------
// F8 — Fail-Backlog (banner). ShedStatus.backlog, once past a "growing" threshold.
// -------------------------------------------------------------------------------------------

/** `Fail-Backlog.dc.html`. Register R-149: [queueHistory] is the real, in-process-observed
 * backlog depth for up to the last 30 minutes (`FailureSignalsPolling`'s own rolling sample,
 * normalised `0f..1f` against the highest depth seen in the window — never a fabricated 30
 * minutes when the process has run for less), oldest first. [growthRateLabel] is measured from
 * that same history (Δbacklog/Δtime), `null` until two samples exist to compare. [captureLabel]
 * is `CaptureState`'s own honest summary — never a dropped-sample or ring-buffer figure this
 * codebase has no signal for. */
public data class BacklogViewState(
    public val waitingCount: Int,
    public val queueHistory: List<Float> = emptyList(),
    public val oldestOverLabel: String? = null,
    public val growthRateLabel: String? = null,
    public val captureLabel: String = "Nominal",
)

// -------------------------------------------------------------------------------------------
// F9 — Fail-Rig (banner). RigStatus.State.Stale.
// -------------------------------------------------------------------------------------------

/** `Fail-Rig.dc.html`. */
public data class RigViewState(public val deviceLabel: String, public val sinceLabel: String)

// -------------------------------------------------------------------------------------------
// F14 — Fail-Clock (informational card, no runtime signal — DebugFailureOverride only).
// -------------------------------------------------------------------------------------------

/** `Fail-Clock.dc.html`'s "Around the change" log row. */
public data class ClockLogRow(
    public val timeLabel: String,
    public val offsetLabel: String,
    public val callsign: String?,
    public val text: String,
)

/** `Fail-Clock.dc.html`. Register R-147: [nightLabel]/[windowLabel]/[logRows] are what turn the
 * small info card ([FailClockCard], still used wherever a future Session-detail screen embeds
 * this inline — a WP10 follow-up, not built by this package) into the board's own full screen
 * ([FailClockScreen]) — defaulted so the card's existing call sites need no change. */
public data class ClockViewState(
    public val offsetChangeLabel: String,
    public val ranForLabel: String,
    public val startedLabel: String,
    public val endedLabel: String,
    public val nightLabel: String = "",
    public val windowLabel: String = "",
    public val logRows: List<ClockLogRow> = emptyList(),
)

// -------------------------------------------------------------------------------------------
// F15 — Fail-Call (dismissable banner). The newest CaptureGapEntity with cause CALL, closed and recent.
// -------------------------------------------------------------------------------------------

/** `Fail-Call.dc.html`. */
public data class CallViewState(public val durationLabel: String)

// -------------------------------------------------------------------------------------------
// F16 — Fail-Usb (takeover dialog, no runtime signal today — see DebugFailureOverride's kdoc).
// -------------------------------------------------------------------------------------------

/** `Fail-Usb.dc.html`. */
public data class UsbViewState(
    public val detachedAtLabel: String,
    public val reattachedAtLabel: String,
    public val staleOversCount: Int,
)

// -------------------------------------------------------------------------------------------
// F17 — Fail-Interrupted (informational card, no runtime signal — DebugFailureOverride only).
// -------------------------------------------------------------------------------------------

/** `Fail-Interrupted.dc.html`'s "The 3 overs" row — `statusLabel` is "resolving again…" or "queued". */
public data class InterruptedOverRow(
    public val timeLabel: String,
    public val statusLabel: String,
    public val text: String,
)

/** `Fail-Interrupted.dc.html`. Register R-147: [backlogLabel]/[overs] turn the small info card
 * ([FailInterruptedCard], still used wherever a future Session-detail screen embeds this inline —
 * a WP10 follow-up) into the board's own full screen ([FailInterruptedScreen]) — defaulted so the
 * card's existing call sites need no change. */
public data class InterruptedViewState(
    public val overCount: Int,
    public val gapLabel: String,
    public val backlogLabel: String = "",
    public val overs: List<InterruptedOverRow> = emptyList(),
)

// -------------------------------------------------------------------------------------------
// F19 — Fail-Reconcile (standalone screen, no runtime signal — DebugFailureOverride only).
// -------------------------------------------------------------------------------------------

public data class ReconcileRecord(public val label: String, public val whenLabel: String, public val note: String?)

/** Register R-148: [playable] is `true` only when a real audio player entry point exists for
 * [path] — today it never does (`TransmissionAudioPlayer` is keyed by transmission id, not an
 * arbitrary retained-audio path; an orphan file has no transmission row by definition), so `Play`
 * always renders as an honest disabled control, never a fabricated affordance. */
public data class ReconcileFile(
    public val path: String,
    public val durationLabel: String,
    public val note: String?,
    public val playable: Boolean = false,
)

/** `Fail-Reconcile.dc.html`. */
public data class ReconcileViewState(
    public val recordsNoFile: List<ReconcileRecord>,
    public val filesNoRecord: List<ReconcileFile>,
    public val causeText: String,
)

// -------------------------------------------------------------------------------------------
// F20 — Fail-Migration (standalone screen, no runtime signal — DebugFailureOverride only).
// -------------------------------------------------------------------------------------------

public data class MigrationStep(public val label: String, public val detail: String, public val ok: Boolean)

/** `Fail-Migration.dc.html`. */
public data class MigrationViewState(
    public val versionLabel: String,
    public val headline: String,
    public val steps: List<MigrationStep>,
)

// -------------------------------------------------------------------------------------------
// F21 — Fail-Asset-Swap (standalone screen, no runtime signal — DebugFailureOverride only).
// -------------------------------------------------------------------------------------------

/** `Fail-Asset-Swap.dc.html`'s Options rows — [subLine] is the board's own explanatory text under
 * each choice ("the default · nothing else to do", ...). */
public data class AssetSwapOption(public val label: String, public val subLine: String)

/** `Fail-Asset-Swap.dc.html`. The Lexicon rows' own dots (solid `accent/green` for [activeLabel],
 * a hollow ring for [stagedLabel] — guide §6.1's marker vocabulary, reused generically the way
 * `FailedState`'s own marker already is) are fixed to those two rows, not a field here. */
public data class AssetSwapViewState(
    public val activeLabel: String,
    public val stagedLabel: String,
    public val options: List<AssetSwapOption>,
    public val selectedOption: Int,
)

// -------------------------------------------------------------------------------------------
// F22 — Fail-Calibration (standalone screen, no runtime signal — DebugFailureOverride only).
// -------------------------------------------------------------------------------------------

/** `Fail-Calibration.dc.html`. [points] are (score, actuallyRight) pairs in `0f..1f`, oldest first. */
public data class CalibrationViewState(
    public val sinceLabel: String,
    public val scoreLabel: String,
    public val accuracyLabel: String,
    public val points: List<Pair<Float, Float>>,
    public val calibrationVersion: String,
    public val correctionsCount: Int,
    public val correctionsNeeded: Int,
)
