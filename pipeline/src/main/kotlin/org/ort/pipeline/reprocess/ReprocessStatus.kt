package org.ort.pipeline.reprocess

/**
 * register R-091/R-143 (FR-REP-9, FR-REP-11): the reprocess-run's live progress, readable by the
 * status surface — the same process-wide holder pattern [org.ort.pipeline.capture.ShedStatus]/
 * [org.ort.pipeline.capture.ThermalStatus] use, for the same reason (live state about a running
 * operation, not a record — the durable record is the `work_queue_item` table and the pass
 * results/superseded transcripts [ReprocessRunner] writes through the existing pass runners).
 *
 * [State.Paused] is published only for the **capture-priority yield** [ReprocessRunner] performs
 * automatically (`CaptureState.isCapturing` and the shed level says the device is busy) — a
 * *user*-requested pause is handled entirely by [ReprocessRunner.run]'s own cold-`Flow` contract
 * (a collector that stops advancing stalls the producer via `emit`'s backpressure, the same
 * cooperative mechanism `FakeImproveRunner`/`ImproveContent` already rely on — see
 * `org.ort.app.ui.improve.ImproveRunner`'s own kdoc) and needs no holder-visible state of its own:
 * from this object's point of view a user-paused run is indistinguishable from one that is simply
 * running slowly, which is exactly correct — [ReprocessRunner] itself never blocks on anything the
 * UI does.
 */
public object ReprocessStatus {

    /**
     * R-143: counts of what a reprocess run actually changed, measured by comparing each
     * transmission's transcript text and attribution (state, station) before and after its pass
     * ran — never a fabricated "N improved" figure (constitution I). [correctedCount] is
     * informational, not a skip count: a transmission a user already corrected is still reprocessed
     * for a possibly-better transcript, but `TransmissionDao.updateAttribution`'s own `AND
     * corrected = 0` guard (build-plan P16, FR-SPK-7) — unchanged, relied on rather than
     * duplicated — structurally refuses to let any pass overwrite its locked attribution, so such a
     * transmission can contribute to [transcriptsChanged] but never to [attributionsChanged].
     */
    public data class Summary(
        public val total: Int,
        public val transcriptsChanged: Int = 0,
        public val attributionsChanged: Int = 0,
        public val rejected: Int = 0,
        public val failed: Int = 0,
        public val correctedCount: Int = 0,
    ) {
        /** Every transmission whose stored record is observably different after the run. */
        public val changedCount: Int get() = transcriptsChanged + attributionsChanged
    }

    public sealed interface State {
        public data object Idle : State

        public data class Running(public val done: Int, public val total: Int, public val currentId: String?) : State

        /** Capture-priority yield in effect — see the class kdoc for why this is never a user pause. */
        public data class Paused(public val done: Int, public val total: Int) : State

        public data class Done(public val summary: Summary) : State
    }

    @Volatile
    public var state: State = State.Idle
        private set

    public fun running(done: Int, total: Int, currentId: String?) {
        state = State.Running(done, total, currentId)
    }

    public fun paused(done: Int, total: Int) {
        state = State.Paused(done, total)
    }

    public fun done(summary: Summary) {
        state = State.Done(summary)
    }

    public fun reset() {
        state = State.Idle
    }
}
