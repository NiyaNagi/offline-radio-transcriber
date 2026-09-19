package org.ort.pipeline.threading

import org.ort.data.entity.ThreadKind
import org.ort.data.entity.ThreadKindSource

/**
 * The one production entry point [org.ort.pipeline.passb.DataPassBResultSink] calls, immediately
 * after a transmission's Pass B result is recorded (FR-SPK-5's amendment: "after Pass B closes
 * each transmission ... does not wait on a transcript"). Everything [ThreadGrouper] and
 * [ThreadKindClassifier] need is read fresh through [repository] for this one call and then
 * discarded — no state survives between calls in this class itself, so replaying the same
 * sequence of closures always produces the same threads (this unit's determinism rule).
 *
 * Idempotent per transmission: [ThreadRepository.closureFor] returns `null` once a transmission
 * already carries a `threadId`, so a transmission whose Pass B attempt failed and was later
 * retried (both calls reach [org.ort.pipeline.passb.DataPassBResultSink.record], only one of them
 * an [org.ort.asrapi.PassBOutcome.Accepted]/[org.ort.asrapi.PassBOutcome.Rejected]) is threaded
 * exactly once, on whichever call closes it first — a transmission's frequency and timing are
 * fixed at capture time and do not change between attempts, so which attempt does the threading
 * does not change the answer.
 */
public class ThreadGroupingCoordinator(
    private val repository: ThreadRepository,
    private val grouper: ThreadGrouper = ThreadGrouper(),
) {

    public suspend fun onTransmissionClosed(transmissionId: String) {
        val closure = repository.closureFor(transmissionId) ?: return
        val prior = repository.priorContextFor(closure)
        val unlistenedGap = prior != null &&
            repository.unlistenedCaptureGapBetween(closure.sessionId, prior.lastEndedAtUtc, closure.startedAtUtc)

        when (val decision = grouper.decide(closure, prior, unlistenedGap)) {
            is ThreadGroupingDecision.StartNewThread -> {
                val kind = ThreadKindClassifier.classify(
                    previousKind = ThreadKind.UNKNOWN,
                    previousKindSource = ThreadKindSource.DETECTED,
                    voiceKeyCountsBeforeCurrent = emptyMap(),
                    current = closure,
                    transmissionCountBeforeCurrent = 0,
                )
                repository.startThread(closure, kind)
            }
            is ThreadGroupingDecision.JoinExistingThread -> {
                val thread = requireNotNull(prior) {
                    "ThreadGrouper.decide never returns JoinExistingThread with a null prior"
                }
                val kind = ThreadKindClassifier.classify(
                    previousKind = thread.kind,
                    previousKindSource = thread.kindSource,
                    voiceKeyCountsBeforeCurrent = thread.voiceKeyCounts,
                    current = closure,
                    transmissionCountBeforeCurrent = thread.transmissionCount,
                )
                repository.appendToThread(decision.threadId, closure, kind)
            }
        }
    }
}
