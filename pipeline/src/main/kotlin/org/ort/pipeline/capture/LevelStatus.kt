package org.ort.pipeline.capture

/**
 * The captured signal's live level, readable by the status surface — the same process-wide
 * holder pattern [ShedStatus]/[ThermalStatus]/[StorageForecast] use, for the same reason: this is
 * live state about the running session, not a record. Before this existed, register R-112 found
 * `:pipeline` publishing no level signal at all — `Level-Meter.dc.html`, `Capture-Status`'s Level
 * row and `Setup-Level`/`Setup-Verify`'s "listening for signal" step could only ever render the
 * honest `NotMeasured` state, and nothing could exercise `Fail-Level.dc.html` (a level that has
 * drifted below the band).
 *
 * **This object is a pure holder — it does no arithmetic.** `:capture-android`'s `LevelMeter` (on
 * `AudioRecordSource`'s own frame path, so it is O(n) arithmetic over a frame already in memory,
 * never blocking capture) computes the peak/RMS/clip/noise-floor/history facts; `RealCaptureService`
 * reads that meter's already-computed snapshot on the same frame path and republishes it here —
 * this file carries no Android dependency and no arithmetic of its own, exactly as [ShedStatus]
 * carries none of [org.ort.pipeline.shed.ShedController]'s.
 *
 * [update] replaces [state] and [peakHistoryDbfs] together, atomically from a reader's point of
 * view (each is its own `@Volatile` field, but both are always set in the same call before either
 * is read again by the producer) — a caller never observes a peak history that belongs to a
 * different snapshot than [state]'s own peak.
 */
public object LevelStatus {

    public sealed interface State {
        /** Before the first frame has been measured this session — never a fabricated reading. */
        public data object NotMeasured : State

        /**
         * One meter tick, at roughly 10 Hz (the frame-read cadence `AudioRecordSource` already
         * runs at with its default read-buffer size). [noiseFloorDbfs] is `null` until the meter
         * has tracked enough RMS history to report a slow-tracking minimum honestly — see
         * `LevelMeter`'s own kdoc for exactly how long that takes.
         */
        public data class Measured(
            public val peakDbfs: Float,
            public val rmsDbfs: Float,
            public val noiseFloorDbfs: Float?,
            public val clipped: Boolean,
            public val clipCountLastSecond: Int,
            public val sampleRateHz: Int,
            public val updatedAtMillis: Long,
        ) : State
    }

    @Volatile
    public var state: State = State.NotMeasured
        private set

    /** The last up-to-60 s of per-second peak dBFS values, oldest first — `Level-Meter.dc.html`'s history bar. */
    @Volatile
    public var peakHistoryDbfs: List<Float> = emptyList()
        private set

    public fun update(measured: State.Measured, peakHistoryDbfs: List<Float>) {
        state = measured
        this.peakHistoryDbfs = peakHistoryDbfs
    }

    public fun reset() {
        state = State.NotMeasured
        peakHistoryDbfs = emptyList()
    }
}
