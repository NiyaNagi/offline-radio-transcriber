package org.ort.pipeline.shed

import org.ort.core.Clock
import org.ort.core.Tier

/**
 * technical design §7.3's shed controller. Levels 0-5, sampled every [SAMPLE_INTERVAL_MILLIS]:
 *
 * - 0: nominal.
 * - 1: drop Pass A (the live streaming hypothesis; Pass B still runs offline).
 * - 2: drop Pass E (speaker identity).
 * - 3: downgrade Pass B's model.
 * - 4: defer all passes — capture and enqueue only (battery below [criticalBatteryPercent] and
 *   not charging forces this level regardless of backlog).
 * - 5: storage exhaustion — capture itself must stop, loudly (constitution IV).
 *
 * **Audio is never dropped by shedding at any level** (FR-RUN-3 → AC-46): every level here is a
 * *processing* decision. Capture keeps enqueueing every segment regardless of [currentLevel] —
 * nothing in this class ever touches the capture path or the queue's `enqueue` call.
 *
 * Hysteresis prevents oscillation: a level is entered the instant its threshold is crossed, but
 * only left once the signal falls to [LEAVE_FACTOR] of that threshold **and** [minDwellMillis]
 * has elapsed since the level was entered.
 */
public class ShedController(
    private val signals: ShedSignals,
    private val clock: Clock,
    private val criticalBatteryPercent: Int = DEFAULT_CRITICAL_BATTERY_PERCENT,
    private val backlogThresholds: Map<Int, Int> = DEFAULT_BACKLOG_THRESHOLDS,
    private val minDwellMillis: Long = DEFAULT_MIN_DWELL_MILLIS,
) {
    public data class ShedEvent(val level: Int, val reason: String, val atWallMillis: Long)

    public var currentLevel: Int = 0
        private set

    private var enteredAtMonotonic: Long = clock.monotonicNanos()
    private val mutableEvents = mutableListOf<ShedEvent>()
    public val events: List<ShedEvent> get() = mutableEvents

    /** Re-samples the signals and updates [currentLevel] under hysteresis. Call every 10 s. */
    public fun sample() {
        val battery = signals.batteryPercent()
        val charging = signals.isCharging()
        val backlog = signals.queueBacklog()

        // Battery-critical is an override: it forces level 4 immediately, no hysteresis delay,
        // because draining the battery to zero is worse than briefly deferring processing. A
        // tier cap (below) can never be lower-priority than this -- it only ever adds shedding,
        // and 4 is already the ceiling short of the storage-exhaustion stop (level 5, forced
        // elsewhere), so there is nothing left for a cap to add here.
        if (battery < criticalBatteryPercent && !charging) {
            transitionTo(4, "battery $battery% < $criticalBatteryPercent% and not charging")
            return
        }

        val desiredFromBacklog = backlogThresholds.entries
            .filter { backlog >= it.value }
            .maxByOrNull { it.key }
            ?.key ?: 0

        // R-1141 (FR-TIER-3): the operator's Settings tier override is a CEILING on tier, which is
        // a FLOOR on shed level -- "Hold at T1" must never let backlog pressure choose a level
        // lower than the one T1 implies, only ever a level equal to or higher. FR-TIER-4's
        // automatic degradation is mandatory, so this floor is combined with [desiredFromBacklog]
        // by taking the max, never by replacing it -- real pressure can still push below the cap
        // (a genuinely overheating/backlogged device sheds further than the operator's chosen
        // ceiling), it just can never be pulled back up above it.
        val capFloor = signals.tierCapOrdinal()?.let { (MAX_TIER_ORDINAL - it).coerceIn(0, MAX_TIER_ORDINAL) } ?: 0
        val desired = maxOf(desiredFromBacklog, capFloor)
        val reason = if (capFloor > desiredFromBacklog) {
            "tier cap (ordinal ${signals.tierCapOrdinal()}) floors the level at $capFloor"
        } else {
            "backlog $backlog >= threshold"
        }

        val elapsedSinceEntry = clock.monotonicNanos() - enteredAtMonotonic
        val dwellElapsed = elapsedSinceEntry >= minDwellMillis * 1_000_000

        when {
            desired > currentLevel -> transitionTo(desired, reason)
            desired < currentLevel && dwellElapsed -> {
                val leaveThreshold = backlogThresholds[currentLevel]?.let { it * LEAVE_FACTOR } ?: 0.0
                if (backlog <= leaveThreshold) transitionTo(desired, "backlog $backlog <= leave threshold")
            }
            else -> Unit
        }
    }

    private fun transitionTo(level: Int, reason: String) {
        if (level == currentLevel) return
        currentLevel = level
        enteredAtMonotonic = clock.monotonicNanos()
        mutableEvents.add(ShedEvent(level, reason, clock.wallMillis()))
    }

    public companion object {
        public const val DEFAULT_CRITICAL_BATTERY_PERCENT: Int = 15
        public const val DEFAULT_MIN_DWELL_MILLIS: Long = 60_000
        private const val LEAVE_FACTOR = 0.7

        /** [Tier.entries]' top ordinal (3) -- the same value every other tier-from-shed-level
         * formula in this codebase (`RealCaptureService.tierFromShedLevel()`,
         * `ReprocessRunner.currentTierFromShedLevel()`, `ImprovePolling.currentTierOrdinal()`,
         * `SettingsPolling.currentTierNumber()`) hardcodes as `3` for the same module-boundary
         * reason (`:pipeline` cannot import `:app`'s copies, and each already duplicates the
         * literal) -- derived from [Tier] here since this file already depends on `:core`. */
        private val MAX_TIER_ORDINAL: Int = Tier.entries.size - 1

        /** Backlog size at which each level is entered (technical design §7.3, illustrative defaults). */
        public val DEFAULT_BACKLOG_THRESHOLDS: Map<Int, Int> = mapOf(
            1 to 20,
            2 to 50,
            3 to 100,
        )
    }
}
