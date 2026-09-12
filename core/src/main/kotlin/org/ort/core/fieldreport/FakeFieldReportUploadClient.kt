package org.ort.core.fieldreport

import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

/**
 * Constitution II's behavioural fake for [FieldReportUploadClient] — every `:app`/`:net` test that
 * needs an upload client drives it through this rather than a real socket, so no ordinary test run
 * ever makes a real network call.
 *
 * Scriptable to report no destination at all (the honest "not configured" state), a destination of
 * any [FieldReportDestinationVisibility] (including [FieldReportDestinationVisibility.UNKNOWN], the
 * "could not be reached" state FR-OBS-10's guard must treat as public), return any
 * [FieldReportUploadResult] — success, or a failure carrying any [FieldReportUploadFailureReason]
 * (a scripted "unreachable destination" or "partial write" is just [resultToReturn] set to the
 * matching [FieldReportUploadResult.Failure]) — hang indefinitely until deliberately released
 * ([hangOnUpload]/[releaseHang]), or record exactly what it was called with for assertions.
 *
 * [hangOnUpload] suspends via the plain `kotlin.coroutines` stdlib primitive [suspendCoroutine],
 * never `kotlinx.coroutines`' cancellation machinery: `:core` carries no `kotlinx-coroutines-core`
 * dependency at all (`core/build.gradle.kts`: "Only the Kotlin stdlib and JUnit (test) belong
 * here"), and this fake ships in `:core`'s own main source set so every module that depends on
 * `:core` can use it. A plain `suspendCoroutine` continuation is **not** cancellable by
 * `Job.cancel()` — calling that on a coroutine parked here would leave it (and, with it, a test's
 * `runTest`) waiting forever, since nothing ever resumes the underlying continuation. [releaseHang]
 * is therefore the only way a hung [upload] call ever completes, and a test that scripts
 * [hangOnUpload] MUST call it before the test ends.
 */
public class FakeFieldReportUploadClient(
    @Volatile public var destinationToReturn: FieldReportDestination? = null,
    @Volatile public var resultToReturn: FieldReportUploadResult =
        FieldReportUploadResult.Success("https://example.invalid/issues/1"),
    @Volatile public var hangOnUpload: Boolean = false,
) : FieldReportUploadClient {

    public var uploadCallCount: Int = 0
        private set
    public var lastRequest: FieldReportUploadRequest? = null
        private set

    @Volatile
    private var hangContinuation: Continuation<Unit>? = null

    override suspend fun destination(): FieldReportDestination? = destinationToReturn

    override suspend fun upload(request: FieldReportUploadRequest): FieldReportUploadResult {
        uploadCallCount++
        lastRequest = request
        if (hangOnUpload) {
            suspendCoroutine<Unit> { continuation -> hangContinuation = continuation }
        }
        return resultToReturn
    }

    /** Resumes a call currently suspended by [hangOnUpload] — proving it never resolves on its
     * own, only when this is deliberately called. A no-op if nothing is hung. */
    public fun releaseHang() {
        val continuation = hangContinuation ?: return
        hangContinuation = null
        continuation.resumeWith(Result.success(Unit))
    }
}
