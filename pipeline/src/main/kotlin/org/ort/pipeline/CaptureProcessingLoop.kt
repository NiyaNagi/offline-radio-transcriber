package org.ort.pipeline

import kotlinx.coroutines.delay

/**
 * Build-plan P12, defect 3: the piece that actually drains the queue. `PassDrainRunner` (P8) leases
 * and runs a batch; nothing before this called it in the running app, so a captured, enqueued
 * transmission sat `CAPTURED` forever. This is the loop that calls it repeatedly, for as long as
 * capture runs, with a real [Pass] (see `org.ort.pipeline.passb.PassBFactory`) instead of the
 * fakes `PassDrainRunnerTest` exercises this against.
 *
 * Continuing past an empty batch rather than stopping is deliberate (FR-RUN-1, constitution IV):
 * capture does not stop producing work just because the queue is briefly empty, so this must not
 * either.
 */
public class CaptureProcessingLoop(
    private val drainRunner: PassDrainRunner,
    private val pass: Pass,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val deadlineMillis: Long = DEFAULT_DEADLINE_MILLIS,
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
) {
    /** One drain of up to [batchSize] ready items. Returns however many were actually leased. */
    public suspend fun drainOnce(): Int = drainRunner.drainBatch(batchSize, deadlineMillis, pass).size

    /** Drains forever, sleeping [pollIntervalMillis] between polls — cancelled via its coroutine's job. */
    public suspend fun runForever() {
        while (true) {
            drainOnce()
            delay(pollIntervalMillis)
        }
    }

    public companion object {
        public const val DEFAULT_BATCH_SIZE: Int = 4
        public const val DEFAULT_DEADLINE_MILLIS: Long = 20_000
        public const val DEFAULT_POLL_INTERVAL_MILLIS: Long = 2_000
    }
}
