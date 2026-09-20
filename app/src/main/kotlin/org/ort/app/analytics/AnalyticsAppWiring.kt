package org.ort.app.analytics

import android.content.Context
import android.os.Build
import org.ort.app.BuildConfig
import org.ort.core.analytics.AnalyticsUploadClient
import org.ort.net.analytics.real.RealAnalyticsUploadClient
import org.ort.pipeline.analytics.AnalyticsBridge
import org.ort.pipeline.capture.CaptureState
import org.ort.telemetry.ANALYTICS_SCHEMA_VERSION
import org.ort.telemetry.AnalyticsController
import org.ort.telemetry.AnalyticsEvent
import org.ort.telemetry.AnalyticsEventQueue
import org.ort.telemetry.AnalyticsProvenance
import org.ort.telemetry.AnalyticsTierPreferences
import org.ort.telemetry.FileBackedAnalyticsEventQueue
import org.ort.telemetry.InstallIdStore
import org.ort.telemetry.SharedPreferencesAnalyticsTierPreferences
import org.ort.telemetry.SharedPreferencesInstallIdStore
import java.io.File

/**
 * P28 (D42, D48, FR-ANL-1..14) — the composition root for the analytics channel, the same role
 * `org.ort.app.fieldreport.wiring.FieldReportAppWiring` already plays for the field-report
 * channel: [configureOnce] is called exactly once per process, from
 * [org.ort.app.OrtApplication.onCreate], and every screen or worker that needs to submit an
 * event, read a tier toggle, reset the install id, or run a drain-and-upload pass reaches it
 * through this object rather than constructing its own queue/preferences/client.
 *
 * **Why the queue and preferences live here, not lower.** `:telemetry` supplies the *types*
 * ([AnalyticsController], [FileBackedAnalyticsEventQueue], [SharedPreferencesAnalyticsTierPreferences]);
 * `:net` supplies [RealAnalyticsUploadClient]. Neither module may depend on the other
 * (`ModuleGraph`), and only `:app` sees `:pipeline`'s [CaptureState] *and* both of those modules
 * at once — so `:app` is where they are wired together, exactly the reasoning
 * `org.ort.core.analytics.AnalyticsUploadRequest`'s own doc comment gives for why `captureActive`
 * is passed as plain data instead.
 */
public object AnalyticsAppWiring {

    private const val QUEUE_MAX_COUNT = 5_000
    private const val QUEUE_MAX_BYTES = 8L * 1024 * 1024 // 8 MiB — FR-ANL-13's bound in bytes.
    private const val QUEUE_DIR_NAME = "analytics-queue"

    public lateinit var controller: AnalyticsController
    public lateinit var tierPreferences: AnalyticsTierPreferences
    public lateinit var installIdStore: InstallIdStore
    private lateinit var queue: AnalyticsEventQueue
    private lateinit var uploadClient: AnalyticsUploadClient
    private var configured = false

    public fun configureOnce(context: Context) {
        if (configured) return
        configured = true
        tierPreferences = SharedPreferencesAnalyticsTierPreferences(
            context.getSharedPreferences(SharedPreferencesAnalyticsTierPreferences.PREFS_NAME, Context.MODE_PRIVATE),
        )
        installIdStore = SharedPreferencesInstallIdStore(
            context.getSharedPreferences(SharedPreferencesInstallIdStore.PREFS_NAME, Context.MODE_PRIVATE),
        )
        queue = FileBackedAnalyticsEventQueue(
            File(context.filesDir, QUEUE_DIR_NAME),
            maxCount = QUEUE_MAX_COUNT,
            maxBytes = QUEUE_MAX_BYTES,
        )
        controller = AnalyticsController(queue, tierPreferences)
        uploadClient = RealAnalyticsUploadClient(BuildConfig.ANALYTICS_ENDPOINT.ifBlank { null })
        // P28 follow-up (D42, FR-ANL-1..14): wires `:pipeline`'s own submission seam
        // (`AnalyticsBridge`'s own doc comment explains why that module cannot hold this
        // composition root itself) to this object's real controller/provenance, exactly once,
        // before any capture session can start (this call runs first in
        // `OrtApplication.onCreate`). Routed through `submitSafely` — never `submit` directly —
        // so a `:pipeline` call site (which cannot itself catch an `:app`-side wiring failure) is
        // just as protected as every other caller of this object.
        AnalyticsBridge.submit = { event -> submitSafely { event } }
        AnalyticsBridge.baseProvenance = ::baseProvenance
    }

    /** D48: whether an endpoint is configured in this build — Settings shows this rather than
     * implying delivery that cannot happen when it is `false` (the default state today). */
    public fun isDestinationConfigured(): Boolean = BuildConfig.ANALYTICS_ENDPOINT.isNotBlank()

    /** [org.ort.app.analytics.AnalyticsUploadWorker]'s own call — see [AnalyticsUploadRunner]'s
     * doc comment for the decision logic this wraps. */
    public suspend fun runUploadOnce(): AnalyticsUploadRunOutcome =
        AnalyticsUploadRunner(queue, uploadClient, isCapturing = { CaptureState.isCapturing }).run()

    /** FR-ANL-11: mints a new install id and asks the configured destination to purge every row
     * associated with the previous one. The purge result is intentionally not surfaced further
     * than a log-level concern here — the id has already changed by the time this returns, which
     * is the guarantee FR-ANL-11 actually requires (no future event ever carries the old id);
     * the purge itself is best-effort against whatever destination is configured. */
    public suspend fun resetInstallId() {
        val reset = installIdStore.reset()
        if (isDestinationConfigured()) uploadClient.purge(reset.previousId)
    }

    /** A base [AnalyticsProvenance] carrying every fact this object can state on its own —
     * install id and the static build/device facts. A caller with more specific context (a real
     * capture session, a specific model set) overrides the rest with `.copy(...)`, the same
     * pattern `SetupSnapshot.requiredModelsInstalled`'s own doc comment documents for a fact one
     * layer cannot itself answer. */
    public fun baseProvenance(): AnalyticsProvenance = AnalyticsProvenance(
        installId = installIdStore.currentId(),
        sessionId = null,
        overId = null,
        appVersion = BuildConfig.VERSION_NAME,
        buildHash = BuildConfig.GIT_SHORT_COMMIT,
        modelIds = emptyList(),
        modelShas = emptyList(),
        executionProvider = "unknown",
        deviceModel = Build.MODEL ?: "unknown",
        soc = Build.HARDWARE ?: "unknown",
        detectedTier = "unknown",
        captureMode = null,
        rigModule = null,
        band = null,
        schemaVersion = ANALYTICS_SCHEMA_VERSION,
    )

    /** Submits [event] if its tier is enabled — a thin, discoverable call site for callers that
     * do not otherwise need [controller] directly. Assumes [configureOnce] has already run; most
     * callers should prefer [submitSafely]. */
    public fun submit(event: AnalyticsEvent) {
        controller.submit(event)
    }

    /**
     * FR-RUN-1/FR-ANL-13 extended to every instrumentation call site this build adds outside
     * `OrtApplication` itself: [build] and the eventual [controller].[org.ort.telemetry.AnalyticsController.submit]
     * both run inside [runCatching], so a call site reached before [configureOnce] has ever run in
     * this process (a `lateinit` read throws), or one whose [controller]/queue throws for any other
     * reason (a full disk, a corrupted queue file), can never propagate into the caller — capture,
     * a correction, a setup step, a screen navigation. Building [build] lazily, inside the same
     * `runCatching`, matters just as much as guarding [submit] itself: [baseProvenance] reads
     * [installIdStore], another `lateinit`, and a caller that built its event eagerly before calling
     * a guarded `submit` would already have crashed constructing the argument.
     */
    public fun submitSafely(build: () -> AnalyticsEvent) {
        runCatching { submit(build()) }
    }

    /** Test-only reset — mirrors the pattern already used across this app's other process-lifetime
     * singletons (`ModelsController.resetForTest`, `StationPolling`'s `SharedDatabase`) for exactly
     * the reason their own doc comments give: a `lateinit object` otherwise leaks state across
     * Robolectric tests that share one JVM. */
    public fun resetForTest() {
        configured = false
        AnalyticsBridge.resetForTest()
    }
}
