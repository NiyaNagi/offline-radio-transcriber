package org.ort.app.analytics

import org.ort.core.analytics.AnalyticsUploadClient
import org.ort.core.analytics.AnalyticsUploadFailureReason
import org.ort.core.analytics.AnalyticsUploadRequest
import org.ort.core.analytics.AnalyticsUploadResult
import org.ort.telemetry.AnalyticsEventCodec
import org.ort.telemetry.AnalyticsEventQueue

/** What one [AnalyticsUploadRunner.run] attempt did — the same closed-outcome-vocabulary shape
 * `org.ort.pipeline.digest.ProseDigestRunOutcome` already uses for its own worker's decision. */
public sealed interface AnalyticsUploadRunOutcome {
    /** FR-ANL-7: capture was active — refused before ever draining the queue. */
    public data object NotEligible : AnalyticsUploadRunOutcome
    public data object NothingQueued : AnalyticsUploadRunOutcome

    /** D48: no `ORT_ANALYTICS_ENDPOINT` configured in this build — the queue is never drained. */
    public data object NotConfigured : AnalyticsUploadRunOutcome
    public data class Uploaded(val count: Int) : AnalyticsUploadRunOutcome
    public data class Failed(val reason: AnalyticsUploadFailureReason) : AnalyticsUploadRunOutcome
}

/**
 * The pure decision logic behind the periodic analytics-upload work chain (FR-ANL-7,
 * `org.ort.pipeline.digest.ProseDigestRunner`'s own split between a plain, injectable runner and
 * its thin `CoroutineWorker` adapter — mirrored here in `AnalyticsUploadWorker`). [isCapturing] is
 * read fresh on every [run] — never cached — the same discipline
 * `org.ort.pipeline.digest.ProseDigestWorkRunner` and the field-report upload path already apply
 * to the identical "never while capture is active" rule.
 *
 * A failed upload re-queues the drained batch (best-effort at-least-once — `:net`'s
 * [AnalyticsUploadClient] never fabricates a success) rather than discarding it; [AnalyticsEventQueue]'s
 * own bound (FR-ANL-13) is what prevents an unreachable destination from growing the queue without
 * limit, not this class.
 */
public class AnalyticsUploadRunner(
    private val queue: AnalyticsEventQueue,
    private val uploader: AnalyticsUploadClient,
    private val isCapturing: () -> Boolean,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    public suspend fun run(): AnalyticsUploadRunOutcome {
        if (isCapturing()) return AnalyticsUploadRunOutcome.NotEligible
        if (queue.size() == 0) return AnalyticsUploadRunOutcome.NothingQueued
        if (!uploader.isConfigured()) return AnalyticsUploadRunOutcome.NotConfigured

        val events = queue.drain(batchSize)
        if (events.isEmpty()) return AnalyticsUploadRunOutcome.NothingQueued

        val ndjson = events.joinToString(separator = "\n") { AnalyticsEventCodec.encode(it) }
        val request = AnalyticsUploadRequest(ndjson = ndjson, eventCount = events.size, captureActive = false)

        return when (val result = uploader.upload(request)) {
            is AnalyticsUploadResult.Success -> AnalyticsUploadRunOutcome.Uploaded(events.size)
            is AnalyticsUploadResult.Failure -> {
                events.forEach { queue.enqueue(it) }
                AnalyticsUploadRunOutcome.Failed(result.reason)
            }
        }
    }

    private companion object {
        const val DEFAULT_BATCH_SIZE = 200
    }
}
