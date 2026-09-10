package org.ort.app

import android.app.Application
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.ort.app.assets.AndroidBundledAssetSource
import org.ort.app.assets.BundledAssetInstaller
import org.ort.app.assets.BundledAssetState

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
