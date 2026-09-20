package org.ort.core.analytics

/**
 * One upload attempt (FR-ANL-7). [ndjson] is already the fully recomputed, closed-field-list wire
 * form (`org.ort.telemetry.AnalyticsEventCodec`) — this class never touches an entity or a live
 * object graph, matching [org.ort.core.fieldreport.FieldReportUploadRequest]'s own [captureActive]
 * pattern: the caller states the fact fresh at send time, since neither `:core` nor `:net` may see
 * `:pipeline`'s `CaptureState` directly (`ModuleGraph`; constitution VII — only `:app` sees both).
 */
public data class AnalyticsUploadRequest(val ndjson: String, val eventCount: Int, val captureActive: Boolean)

/** A closed failure vocabulary (constitution II: "assertions MUST NOT depend on prose") —
 * mirrors [org.ort.core.fieldreport.FieldReportUploadFailureReason]'s own shape. */
public enum class AnalyticsUploadFailureReason {
    /** D48: `ORT_ANALYTICS_ENDPOINT` unset in this build — the default state today. An honest
     * "not configured", never a silent no-op. */
    NOT_CONFIGURED,

    /** FR-ANL-7: refused because capture was active at the moment of send. */
    CAPTURE_ACTIVE,

    /** The destination could not be reached. */
    DESTINATION_UNREACHABLE,

    /** The destination reached, but rejected the payload outright (a non-2xx response). */
    UPLOAD_FAILED,

    /** The upload did not complete — the connection failed while writing. */
    PARTIAL_WRITE,
}

public sealed interface AnalyticsUploadResult {
    public object Success : AnalyticsUploadResult
    public data class Failure(val reason: AnalyticsUploadFailureReason, val detail: String) : AnalyticsUploadResult
}

/** FR-ANL-11: purging every row the destination holds for one install id (D48's GDPR-style
 * erasure by id). */
public sealed interface AnalyticsPurgeResult {
    public object Success : AnalyticsPurgeResult
    public data class Failure(val reason: AnalyticsUploadFailureReason, val detail: String) : AnalyticsPurgeResult
}

/**
 * The one entry point analytics ever reaches the network through (D48). Declared in `:core` so
 * both `:net` (the real implementation — the only module permitted an HTTP client, constitution
 * V) and `:app` (the composition root that owns the queue-drain schedule and the install-id-reset
 * coordinator) can see it without either gaining a forbidden edge on the other — the same
 * resolution [org.ort.core.fieldreport.FieldReportUploadClient] already uses.
 *
 * [isConfigured] answers "is `ORT_ANALYTICS_ENDPOINT` set in this build" without attempting a
 * network call — Settings uses it to show the honest "not configured, nothing is ever sent" state
 * (D48) rather than implying delivery that cannot happen.
 */
public interface AnalyticsUploadClient {
    public suspend fun isConfigured(): Boolean
    public suspend fun upload(request: AnalyticsUploadRequest): AnalyticsUploadResult
    public suspend fun purge(installId: String): AnalyticsPurgeResult
}
