package org.ort.core.analytics

import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

/**
 * Constitution II's behavioural fake for [AnalyticsUploadClient] — every `:app`/`:net` test that
 * needs an upload client drives it through this rather than a real socket. Mirrors
 * [org.ort.core.fieldreport.FakeFieldReportUploadClient] exactly, including its `hangOnUpload`
 * mechanism: `:core` carries no `kotlinx-coroutines-core` dependency, so this fake ships in
 * `:core`'s own main source set using only the plain `kotlin.coroutines` stdlib primitive
 * [suspendCoroutine] (not cancellable by `Job.cancel()` — [releaseHang] is the only way a hung
 * call ever completes, and a test that scripts [hangOnUpload] MUST call it before the test ends).
 */
public class FakeAnalyticsUploadClient(
    @Volatile public var configured: Boolean = false,
    @Volatile public var uploadResultToReturn: AnalyticsUploadResult = AnalyticsUploadResult.Success,
    @Volatile public var purgeResultToReturn: AnalyticsPurgeResult = AnalyticsPurgeResult.Success,
    @Volatile public var hangOnUpload: Boolean = false,
) : AnalyticsUploadClient {

    public var uploadCallCount: Int = 0
        private set
    public var lastRequest: AnalyticsUploadRequest? = null
        private set
    public var purgeCallCount: Int = 0
        private set
    public var lastPurgedInstallId: String? = null
        private set

    @Volatile
    private var hangContinuation: Continuation<Unit>? = null

    override suspend fun isConfigured(): Boolean = configured

    override suspend fun upload(request: AnalyticsUploadRequest): AnalyticsUploadResult {
        uploadCallCount++
        lastRequest = request
        if (request.captureActive) {
            return AnalyticsUploadResult.Failure(AnalyticsUploadFailureReason.CAPTURE_ACTIVE, "capture active")
        }
        if (!configured) {
            return AnalyticsUploadResult.Failure(AnalyticsUploadFailureReason.NOT_CONFIGURED, "no endpoint configured")
        }
        if (hangOnUpload) {
            suspendCoroutine<Unit> { continuation -> hangContinuation = continuation }
        }
        return uploadResultToReturn
    }

    override suspend fun purge(installId: String): AnalyticsPurgeResult {
        purgeCallCount++
        lastPurgedInstallId = installId
        return purgeResultToReturn
    }

    /** Resumes a call currently suspended by [hangOnUpload] — a no-op if nothing is hung. */
    public fun releaseHang() {
        val continuation = hangContinuation ?: return
        hangContinuation = null
        continuation.resumeWith(Result.success(Unit))
    }
}
