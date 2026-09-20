package org.ort.pipeline.analytics

import org.ort.telemetry.ANALYTICS_SCHEMA_VERSION
import org.ort.telemetry.AnalyticsEvent
import org.ort.telemetry.AnalyticsProvenance

/**
 * P28 follow-up (D42, FR-ANL-1..14): the seam `:pipeline` code submits analytics events through,
 * without `:pipeline` owning a composition root of its own. `:app`'s `AnalyticsAppWiring` is the
 * only place that sees both `:telemetry` and `:net` at once (its own doc comment explains why), so
 * it wires [submit] and [baseProvenance] exactly once, at process start — the same "process-wide
 * holder" pattern [org.ort.pipeline.capture.CaptureState]/[org.ort.pipeline.capture.ThermalStatus]
 * already use, just inverted: those are written by `:pipeline` and read by `:app`; this object is
 * written by `:app` and called by `:pipeline`.
 *
 * Defaults to a no-op so any of this module's ~600 Robolectric tests that never wire it — which is
 * most of them — submits nothing and never crashes, exactly the discipline
 * `AnalyticsController.submit` itself already has for a disabled tier (never queued, never
 * thrown). This is also what keeps every call site cheap and non-blocking (constitution IV):
 * worst case, an unwired call is a single volatile read and an empty lambda invocation.
 *
 * `:capture-api`/`:capture-android` may never depend on `:telemetry` (`ModuleGraph`'s own
 * `explicitlyForbidden` block, both directions) — this object lives in `:pipeline`, which may, and
 * every caller of [submit] in this module is itself outside `:capture-*` (`ThermalTrackingPass`,
 * `CaptureStatusRepository` — both `:pipeline`-owned, not `:capture-android`).
 */
public object AnalyticsBridge {

    @Volatile
    public var submit: (AnalyticsEvent) -> Unit = {}

    @Volatile
    public var baseProvenance: () -> AnalyticsProvenance = { FALLBACK_PROVENANCE }

    /** Test-only reset — the same pattern this module's other process-lifetime singletons use
     * (`ThermalStatus.reset`, `VadAvailability.reset`), so one test class's wiring never leaks into
     * the next in a shared Robolectric JVM. */
    public fun resetForTest() {
        submit = {}
        baseProvenance = { FALLBACK_PROVENANCE }
    }

    /** Used only if a caller somehow invokes [baseProvenance] before `:app` has wired a real one
     * (should not happen in production — `AnalyticsAppWiring.configureOnce` runs first in
     * `OrtApplication.onCreate`, before any capture can start) — an honest "unknown" provenance,
     * never a crash, matching constitution I's "never fabricate, but never crash on an honest gap"
     * discipline the rest of this codebase already applies to a missing fact. */
    private val FALLBACK_PROVENANCE = AnalyticsProvenance(
        installId = "unknown",
        sessionId = null,
        overId = null,
        appVersion = "unknown",
        buildHash = "unknown",
        modelIds = emptyList(),
        modelShas = emptyList(),
        executionProvider = "unknown",
        deviceModel = "unknown",
        soc = "unknown",
        detectedTier = "unknown",
        captureMode = null,
        rigModule = null,
        band = null,
        schemaVersion = ANALYTICS_SCHEMA_VERSION,
    )
}
