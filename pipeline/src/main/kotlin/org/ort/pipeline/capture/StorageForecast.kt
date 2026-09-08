package org.ort.pipeline.capture

/**
 * FR-STO-3's early warning (register R-105), readable by the status surface — the same
 * process-wide holder pattern [ShedStatus] uses. Before this existed, `:pipeline` carried exactly
 * one storage signal — [RealCaptureService.stopForStorageExhaustion]'s hard 100 MiB floor — and
 * nothing between "fine" and "stopped": there was no way to simulate, show, or build the "warned
 * at 3 nights left" / "warned at 1 night left" stages `design/canvas/Fail-Storage.dc.html` shows.
 *
 * [update] is the real production computation, called on the shed tick beside the existing floor
 * check: [bytesWrittenThisSession] over [sessionElapsedMillis] gives a real, measured write rate,
 * extrapolated to [NIGHT_DURATION_MILLIS] to get bytes-per-night. **[NIGHT_DURATION_MILLIS] is a
 * documented assumption, not a measured or configured value** — FR-STO-3's own budget/retention
 * settings screen (WP10, register R-090) is the eventual source of truth for what "a night" means;
 * until it exists, eight hours (the design canvas's own "Overnight" sessions run 6-8h) is the
 * least-wrong placeholder, named here so it is easy to find and replace. [freeBytes] at or below
 * [floorBytes] is always [State.AtFloor], regardless of the rate — the existing loud stop is the
 * one fact this must never contradict.
 *
 * Every named function corresponds to exactly one state, mirroring [CaptureState]/[AsrAvailability]
 * — a caller that knows the state it wants (the scenario simulator, a test) sets it directly rather
 * than reverse-engineering byte/time inputs that would produce it.
 */
public object StorageForecast {

    public sealed interface State {
        public val freeBytes: Long
        public val audioDirectoryBytes: Long

        /** Not enough has been measured yet this session (no elapsed time, or nothing written). */
        public data class NotYetMeasured(override val freeBytes: Long, override val audioDirectoryBytes: Long) : State

        public data class Fine(
            override val freeBytes: Long,
            override val audioDirectoryBytes: Long,
            public val nightsLeft: Double,
        ) : State

        public data class ThreeNightsLeft(
            override val freeBytes: Long,
            override val audioDirectoryBytes: Long,
            public val nightsLeft: Double,
        ) : State

        public data class OneNightLeft(
            override val freeBytes: Long,
            override val audioDirectoryBytes: Long,
            public val nightsLeft: Double,
        ) : State

        /** The existing hard floor (FR-STO-4) — never contradicted by a rate-based estimate. */
        public data class AtFloor(override val freeBytes: Long, override val audioDirectoryBytes: Long) : State
    }

    public const val ONE_NIGHT_THRESHOLD: Double = 1.0
    public const val THREE_NIGHTS_THRESHOLD: Double = 3.0

    /** A documented assumption, not a measured or configured value — see the class kdoc. */
    public const val NIGHT_DURATION_MILLIS: Long = 8L * 60 * 60 * 1000

    @Volatile
    public var state: State = State.NotYetMeasured(0L, 0L)
        private set

    /**
     * The real production computation (FR-STO-3). [floorBytes] is passed in, not hard-coded, so
     * this stays in step with [RealCaptureService.STORAGE_FLOOR_BYTES] without this file needing
     * to know that class's internals.
     */
    public fun update(
        freeBytes: Long,
        audioDirectoryBytes: Long,
        bytesWrittenThisSession: Long,
        sessionElapsedMillis: Long,
        floorBytes: Long,
    ) {
        state = when {
            freeBytes <= floorBytes -> State.AtFloor(freeBytes, audioDirectoryBytes)
            sessionElapsedMillis <= 0L || bytesWrittenThisSession <= 0L ->
                State.NotYetMeasured(freeBytes, audioDirectoryBytes)
            else -> {
                val bytesPerNight =
                    bytesWrittenThisSession.toDouble() / sessionElapsedMillis.toDouble() * NIGHT_DURATION_MILLIS
                val nightsLeft = freeBytes / bytesPerNight
                stateFor(freeBytes, audioDirectoryBytes, nightsLeft)
            }
        }
    }

    private fun stateFor(freeBytes: Long, audioDirectoryBytes: Long, nightsLeft: Double): State = when {
        nightsLeft <= ONE_NIGHT_THRESHOLD -> State.OneNightLeft(freeBytes, audioDirectoryBytes, nightsLeft)
        nightsLeft <= THREE_NIGHTS_THRESHOLD -> State.ThreeNightsLeft(freeBytes, audioDirectoryBytes, nightsLeft)
        else -> State.Fine(freeBytes, audioDirectoryBytes, nightsLeft)
    }

    /** Direct setter for the scenario simulator and tests — see class kdoc. */
    public fun set(newState: State) {
        state = newState
    }

    public fun reset() {
        state = State.NotYetMeasured(0L, 0L)
    }
}
