package org.ort.pipeline.capture

import org.ort.core.SystemClock

/**
 * F7 (register R-104): thermal throttling, readable by the status surface — the same process-wide
 * holder pattern [ShedStatus]/[AsrAvailability]/[VadAvailability]/[CaptureState] use, for the same
 * reason (live state about the running process, not a record). Before this existed, `:pipeline`
 * carried no thermal signal at all — [org.ort.pipeline.shed.ShedSignals] is battery/backlog/storage
 * only — so F7 ("thermal throttling") could not be simulated, shown, or built against.
 *
 * Two facts, never conflated (constitution I — never invented):
 *
 * - [State.osThermalStatus] mirrors `android.os.PowerManager`'s `THERMAL_STATUS_*` ints, read by
 *   [RealCaptureService] via `PowerManager.getThermalStatus()` on API 29+. Below API 29 the OS
 *   exposes nothing, and [THERMAL_STATUS_NONE] is reported honestly rather than invented — this
 *   object has no Android dependency itself, so the constant is mirrored here rather than imported.
 * - [State.realTimeFactor] is **measured**, never estimated: the wall time of one real Pass B run
 *   over its segment's real audio duration ([recordPassTiming]), exponentially smoothed so one fast
 *   or slow segment cannot flicker the published level. `null` until the first pass has actually
 *   run this session — the same "not yet measured" discipline `StatusViewState.backlogLabel`
 *   already uses, not a fabricated starting value.
 *
 * [sample] is the tick that turns the OS reading plus whatever has been measured so far into the
 * published tri-state — called on the same 10 s tick `RealCaptureService.runShedMonitor` already
 * runs, so a caller reading [state] mid-session never sees a stale thermal reading next to a fresh
 * shed level. Deliberately does **not** touch `ShedController`/`ShedSignals`
 * (`org.ort.pipeline.shed`) or its shed-order rules (FR-RUN-3) — this object only publishes the
 * facts; the tier drop a screen shows on `Warm`/`Hot` is that screen's own reading of this state
 * next to the existing shed level (WP11b/WP4), not a decision made here.
 *
 * **register R-177**: [State.Warm]/[State.Hot] carry [State.Warm.sinceMillis]/[State.Hot.sinceMillis]
 * — the same shape [RigStatus.State.Stale.sinceMillis] already uses — set once at the moment
 * [sample] first observes *this* tier and preserved unchanged across every later tick while the
 * tier does not change, so a mapper reading it repeatedly (`Fail-Thermal`'s "dropped to tier N at
 * HH:MM:SS") renders the real transition moment, not a time that creeps forward on every poll.
 * Entering a *different* non-nominal tier (`Warm` → `Hot` or `Hot` → `Warm`) is its own transition
 * and gets a fresh `sinceMillis`, same as entering from `Nominal`. [Warm]/[Hot] both default
 * [State.Warm.sinceMillis]/[State.Hot.sinceMillis] to "now" only so a caller that constructs one
 * directly without a transition to track (existing fixtures elsewhere) still compiles; every real
 * caller in this file ([stateFor], via [sample]/[update]) always passes an explicit value.
 */
public object ThermalStatus {

    /** Mirrors `PowerManager.THERMAL_STATUS_NONE` — also the honest default below API 29. */
    public const val THERMAL_STATUS_NONE: Int = 0
    public const val THERMAL_STATUS_LIGHT: Int = 1
    public const val THERMAL_STATUS_MODERATE: Int = 2
    public const val THERMAL_STATUS_SEVERE: Int = 3
    public const val THERMAL_STATUS_CRITICAL: Int = 4
    public const val THERMAL_STATUS_EMERGENCY: Int = 5
    public const val THERMAL_STATUS_SHUTDOWN: Int = 6

    /** Weight given to the newest sample — a real-time factor swings less than backlog, so a fast average is fine. */
    public const val DEFAULT_SMOOTHING_ALPHA: Double = 0.3

    public sealed interface State {
        public val osThermalStatus: Int
        public val realTimeFactor: Double?

        /** [THERMAL_STATUS_NONE] or [THERMAL_STATUS_LIGHT]. */
        public data class Nominal(override val osThermalStatus: Int, override val realTimeFactor: Double?) : State

        /** [THERMAL_STATUS_MODERATE]. [sinceMillis] — see the class kdoc's R-177 note. */
        public data class Warm(
            override val osThermalStatus: Int,
            override val realTimeFactor: Double?,
            public val sinceMillis: Long = SystemClock.wallMillis(),
        ) : State

        /** [THERMAL_STATUS_SEVERE] or worse. [sinceMillis] — see the class kdoc's R-177 note. */
        public data class Hot(
            override val osThermalStatus: Int,
            override val realTimeFactor: Double?,
            public val sinceMillis: Long = SystemClock.wallMillis(),
        ) : State
    }

    @Volatile
    public var state: State = State.Nominal(THERMAL_STATUS_NONE, null)
        private set

    @Volatile
    private var smoothedRealTimeFactor: Double? = null

    /**
     * Records one real Pass B run's wall time over its segment's real audio duration
     * (`RealCaptureService`'s `ThermalTrackingPass` is the one caller — see its own kdoc for why
     * the measurement is taken there rather than inside `CaptureProcessingLoop`/`PassDrainRunner`,
     * both outside this package's file ownership). Does not by itself change [state] — [sample]
     * republishes it on the shared 10 s tick, so a burst of small segments cannot flicker the
     * published level between ticks.
     */
    public fun recordPassTiming(realTimeFactor: Double, alpha: Double = DEFAULT_SMOOTHING_ALPHA) {
        val previous = smoothedRealTimeFactor
        smoothedRealTimeFactor = if (previous == null) {
            realTimeFactor
        } else {
            alpha * realTimeFactor + (1 - alpha) * previous
        }
    }

    /**
     * Republishes [state] from a fresh OS thermal status and whatever [recordPassTiming] has
     * measured so far. [nowMillis] is only consulted when this tick lands on a *fresh* transition
     * into [State.Warm]/[State.Hot] (R-177) — a same-tier tick preserves the previous
     * `sinceMillis` untouched, which is the whole point: `RealCaptureService`'s real 10 s tick
     * never needs to pass this explicitly.
     */
    public fun sample(osThermalStatus: Int, nowMillis: Long = SystemClock.wallMillis()) {
        state = stateFor(osThermalStatus, smoothedRealTimeFactor, nowMillis)
    }

    /**
     * Sets both facts at once — the scenario simulator's and tests' entry point. [sinceMillis], if
     * given, is used verbatim for the resulting [State.Warm]/[State.Hot] regardless of the previous
     * state (register R-177 — the `thermal` scenario sets a real "minutes ago" value this way, not
     * "now"); if omitted, falls back to the same transition-preserving logic [sample] uses.
     */
    public fun update(osThermalStatus: Int, realTimeFactor: Double?, sinceMillis: Long? = null) {
        smoothedRealTimeFactor = realTimeFactor
        state = stateFor(osThermalStatus, realTimeFactor, SystemClock.wallMillis(), sinceOverride = sinceMillis)
    }

    public fun reset() {
        smoothedRealTimeFactor = null
        state = State.Nominal(THERMAL_STATUS_NONE, null)
    }

    private fun stateFor(
        osThermalStatus: Int,
        realTimeFactor: Double?,
        nowMillis: Long,
        sinceOverride: Long? = null,
    ): State = when {
        osThermalStatus >= THERMAL_STATUS_SEVERE ->
            State.Hot(osThermalStatus, realTimeFactor, sinceOverride ?: sinceMillisFor(isHot = true, nowMillis))
        osThermalStatus >= THERMAL_STATUS_MODERATE ->
            State.Warm(osThermalStatus, realTimeFactor, sinceOverride ?: sinceMillisFor(isHot = false, nowMillis))
        else -> State.Nominal(osThermalStatus, realTimeFactor)
    }

    /**
     * R-177: the previous tier's own `sinceMillis` carried forward when [state] is already the
     * *same* tier ([isHot] matching), or [nowMillis] — a fresh transition — otherwise (entering
     * from [State.Nominal] or from the other non-nominal tier).
     */
    private fun sinceMillisFor(isHot: Boolean, nowMillis: Long): Long {
        val previous = state
        return when {
            isHot && previous is State.Hot -> previous.sinceMillis
            !isHot && previous is State.Warm -> previous.sinceMillis
            else -> nowMillis
        }
    }
}
