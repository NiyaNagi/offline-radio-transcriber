package org.ort.app

import android.app.Application
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.ort.app.analytics.AnalyticsAppWiring
import org.ort.app.analytics.AnalyticsUploadWorker
import org.ort.app.analytics.CrashCaptureHandler
import org.ort.app.assets.AndroidBundledAssetSource
import org.ort.app.assets.BundledAssetInstaller
import org.ort.app.assets.BundledAssetState
import org.ort.app.fieldreport.wiring.FieldReportAppWiring
import org.ort.pipeline.digest.ProseDigestRunner
import org.ort.pipeline.digest.SharedPreferencesProseDigestSettingsStore
import org.ort.telemetry.AnalyticsEventFactory
import org.ort.telemetry.AnalyticsTier1Payload

/**
 * Application shell. The Hilt graph, capture service wiring and onboarding arrive with
 * build-plan P8; for now this exists so the APK has a launchable entry point and the
 * minimum-API smoke test (AC-93 / NFR-5) has something real to start.
 *
 * **WPG (`spec/e2e-capture-modes-plan.md`, FR-AST-3/3b, D35/D36):** installs every bundled asset
 * on a background dispatcher as soon as the process starts, on every launch — not gated behind a
 * "first run" flag, so a destination or marker cleared by the OS (or never written because a
 * previous launch was killed mid-copy) is repaired the next time the process starts, with no
 * special-cased recovery path (`BundledAssetInstaller.installAll` is already idempotent — an
 * asset already verified is reported [BundledAssetState.Installed] without being re-copied).
 * Never on the main thread, and never something capture waits on (constitution IV): the existing
 * [org.ort.pipeline.capture.VadAvailability] path already says so visibly if the VAD is not yet
 * copied when capture starts, exactly as it would for a genuinely absent model. A per-asset
 * failure here is [BundledAssetState.Failed] — a stated, recoverable state (AC-137) logged to
 * Logcat, never analytics, telemetry or a crash (constitution V, FR-OBS-5) — never thrown, so a
 * corrupted bundled asset cannot crash app startup.
 *
 * **Never runs under Robolectric.** [OrtApplication] is every Robolectric test's own `Application`
 * (declared as `android:name` in the manifest, so `ApplicationProvider.getApplicationContext()`
 * instantiates exactly this class in every one of this module's ~1200 unit tests) — an
 * unconditional install here would make an unrelated test's "nothing is on disk yet" assumption
 * depend on a real, racy, off-thread filesystem copy this class's own KDoc already says nothing
 * should wait on, and on whether this machine's `app/src/main/assets/bundled/` happens to be
 * populated from an earlier `assembleDebug`. [isRunningUnderRobolectric] is the same
 * `Build.FINGERPRINT` check widely used for exactly this purpose (Robolectric's simulated
 * `Build.FINGERPRINT` is literally `"robolectric"`) — [BundledAssetInstaller] itself is fully
 * covered by its own direct, deterministic tests regardless (`BundledAssetInstallerTest`); this
 * guard is only about not firing it as a side effect of constructing an `Application` in a test.
 */
class OrtApplication : Application() {

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        if (isRunningUnderRobolectric()) return
        // WPR2 (FR-OBS-6/FR-OBS-7): the one call that starts the debug-build field-report session
        // recorder — see `FieldReportAppWiring`'s own doc comment for why this is called exactly
        // once, here, rather than from an `Activity`. A no-op in a release build.
        FieldReportAppWiring.configureOnce(filesDir)
        // P28 (D42, FR-ANL-1..14): the analytics channel's composition root — queue, tier
        // preferences, install id, upload client (`AnalyticsAppWiring`'s own doc comment). Started
        // unconditionally (tier 1 is on by default, FR-ANL-1) and idempotently (`configureOnce`).
        AnalyticsAppWiring.configureOnce(this)
        // FR-ANL-2/D42: crash and ANR capture without any third-party SDK. Chains whatever handler
        // was already installed (the platform's own) so recording a crash never changes what
        // happens to it (`CrashCaptureHandler`'s own doc comment).
        val previousExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(
            CrashCaptureHandler(previousExceptionHandler) { thread, throwable ->
                AnalyticsAppWiring.submit(
                    AnalyticsEventFactory.tier1(
                        AnalyticsAppWiring.baseProvenance(),
                        AnalyticsTier1Payload.Crash(
                            exceptionClass = throwable.javaClass.name,
                            stackTrace = throwable.stackTraceToString(),
                            isAnr = false,
                            threadName = thread.name,
                        ),
                    ),
                )
            },
        )
        // FR-ANL-7: the periodic drain-and-upload chain — a no-op today (D48) until
        // ORT_ANALYTICS_ENDPOINT is configured, since AnalyticsUploadRunner reports NotConfigured
        // and never drains the queue. Scheduling it regardless costs nothing and means a later
        // build that does configure an endpoint needs no separate app-level change to start
        // sending what has already been queuing quietly since v0.1.1.
        AnalyticsUploadWorker.schedule(this)
        // WPE (E2-I03's own "owed by WPE" note, FR-DIG-5): schedules the prose-digest work chain
        // on every launch — a no-op if it is already scheduled (`ProseDigestRunner.schedule`'s own
        // `ExistingWorkPolicy.KEEP`) — but only when the operator has not disabled it (CF04's
        // toggle writes this same store's flag straight through `.cancel()`, so a disabled state
        // must never be silently re-armed just because the process restarted).
        if (SharedPreferencesProseDigestSettingsStore(this).isEnabled()) {
            ProseDigestRunner.schedule(this)
        }
        backgroundScope.launch {
            val results = BundledAssetInstaller.installAll(filesDir, AndroidBundledAssetSource(this@OrtApplication))
            results.forEach { result ->
                when (result) {
                    is BundledAssetState.Installed -> Unit
                    is BundledAssetState.Failed ->
                        Log.w(TAG, "bundled asset ${result.id} failed to install: ${result.reason}")
                    is BundledAssetState.NotBundledInThisBuild ->
                        Log.w(TAG, "bundled asset ${result.id} is absent from this build: ${result.reason}")
                }
            }
        }
    }

    private fun isRunningUnderRobolectric(): Boolean = Build.FINGERPRINT.contains("robolectric", ignoreCase = true)

    private companion object {
        private const val TAG = "OrtApplication"
    }
}
