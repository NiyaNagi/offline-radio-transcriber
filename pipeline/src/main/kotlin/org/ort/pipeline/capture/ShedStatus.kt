package org.ort.pipeline.capture

/**
 * The real `ShedController`'s live level and queue backlog, readable by the status surface — the
 * same process-wide-holder pattern [CaptureState], [AsrAvailability] and [VadAvailability] use,
 * for the same reason: this is live state about the running session, not a record (persisted shed
 * transitions are `ShedEventDao`'s job — F-021, `org.ort.pipeline.shed.ShedEventPersister`).
 *
 * **audit F-007**: before this existed, `:app`'s status surface (`ReaderPolling.kt`,
 * `StatusActivity.kt`) constructed its own `ShedController` fed by `FakeShedSignals` purely to
 * have something to display (F-002) — nothing in the running process ever sampled a real signal,
 * so `FR-RUN-3`'s shed order could never actually trigger. `RealCaptureService` now ticks a real
 * `ShedController` backed by `AndroidShedSignals` every 10 s and republishes its state here.
 *
 * `:app` cannot depend on `:pipeline`'s Android-only `ShedSignals`/`AndroidShedSignals` types
 * directly without care, but this object carries no such dependency — it is two `Int`s — so a
 * follow-up on F-002 can read [currentLevel]/[backlog] here in place of its own fake-fed
 * controller instance without a new module edge.
 */
public object ShedStatus {

    @Volatile
    public var currentLevel: Int = 0
        private set

    @Volatile
    public var backlog: Int = 0
        private set

    public fun update(level: Int, backlog: Int) {
        currentLevel = level
        this.backlog = backlog
    }

    public fun reset() {
        currentLevel = 0
        backlog = 0
    }
}
