package org.ort.pipeline

import kotlinx.coroutines.delay
import org.ort.core.TransmissionState
import org.ort.data.PassRunOutcome
import org.ort.data.entity.WorkQueueItemEntity

/**
 * One processing pass, as `:pipeline` sees it before `:asr-*` exists (build-plan P8's
 * instruction: build the watchdog against a fake pass rather than create a dependency on
 * `:asr-*`). A real pass implementation lands in a later build-plan session.
 */
public fun interface Pass {
    public suspend fun run(item: WorkQueueItemEntity): PassRunOutcome
}

/** A pass that never returns — the behavioural fake AC-99 is demonstrated against. */
public class HangingPass : Pass {
    override suspend fun run(item: WorkQueueItemEntity): PassRunOutcome {
        delay(Long.MAX_VALUE / 2)
        error("unreachable — the watchdog must have cancelled this first")
    }
}

/** A pass that always succeeds — used to prove the queue keeps draining past a hung item. */
public class SucceedingPass(private val finalState: TransmissionState = TransmissionState.COMPLETE) : Pass {
    override suspend fun run(item: WorkQueueItemEntity): PassRunOutcome = PassRunOutcome.Finished(finalState)
}
