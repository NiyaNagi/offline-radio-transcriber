package org.ort.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import org.ort.app.permissions.PermissionsState
import org.ort.app.ui.ReaderActivity
import org.ort.app.ui.setup.SetupActivity
import org.ort.app.ui.setup.SetupStateMachine
import org.ort.app.ui.setup.SharedPreferencesSetupStore
import org.ort.app.ui.theme.OrtSystemBarStyle
import org.ort.core.Ulid
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.RealCaptureService

/**
 * The router (build-plan P8; ui-conformance-plan R-002, R-080, R-085): setup incomplete →
 * [SetupActivity]; complete and permitted → start capture + [ReaderActivity], exactly as before
 * WP9. Every interim permission screen this activity used to render directly
 * (`MicrophoneSetupScreen`, `MicrophoneDeniedScreen`, `NotificationsSetupScreen`, the `SetupScreen`
 * enum and `setupScreenFor`) has moved to `ui/setup` (every file under it), re-homed onto WP2's shared components as
 * [SetupActivity]'s own S02/S02b/S03 — see that package for the full guided sequence
 * (`Flow-Setup.dc.html`, register rows R-080..R-084). This activity carries no Compose UI of its
 * own any more: it decides, in [onCreate], and either starts capture or redirects, never showing
 * anything the operator would see.
 */
public class MainActivity : ComponentActivity() {

    private var sessionId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // R-008: see OrtSystemBarStyle's own doc comment — the no-arg enableEdgeToEdge() picks
        // light-on-dark only in night mode, which rendered dark-on-dark on emulator-5554.
        enableEdgeToEdge(statusBarStyle = OrtSystemBarStyle, navigationBarStyle = OrtSystemBarStyle)
        sessionId = savedInstanceState?.getString(KEY_SESSION_ID) ?: Ulid.generate().value
        route()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SESSION_ID, sessionId)
    }

    /**
     * The one decision this activity makes: is setup done and is capture actually still permitted
     * right now ([SetupStateMachine.isComplete] — re-checked on every launch, not just the first,
     * so a permission revoked after setup finished sends the operator back through the guided
     * sequence rather than silently failing to start capture). `true` runs the unchanged
     * [startCaptureAndShowStatus] path below; `false` hands off to [SetupActivity], which resumes
     * at the first unverified step (`Flow-Setup.dc.html`) and returns here once `Start capture` is
     * tapped.
     */
    private fun route() {
        val prefs = getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, MODE_PRIVATE)
        val store = SharedPreferencesSetupStore(prefs)
        if (SetupStateMachine.isComplete(currentPermissionsState(store.notificationsSkipped), store.snapshot())) {
            startCaptureAndShowStatus()
        } else {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
        }
    }

    /**
     * audit F-022: a foreground service is a singleton per process, and
     * `RealCaptureService.onStartCommand` already ignores a second start command while one is
     * live -- but before this, nothing here knew that, so relaunching this activity while capture
     * was already running always minted a fresh [sessionId] and handed it to [ReaderActivity],
     * which then polled a session nothing was capturing into (constitution IV, "never lies";
     * FR-UI-7). [CaptureState.sessionId] is published by `RealCaptureService.startCapture()` the
     * moment capture actually starts, so it is the one place in-process that knows which session,
     * if any, is genuinely live right now -- checked here instead of trusting this activity's own
     * freshly-generated [sessionId].
     *
     * ui-conformance-plan R-007: this is a same-process guard only — [CaptureState] is an
     * in-memory holder that does not survive a real process death (its own doc comment is explicit
     * about this), so it cannot by itself recover a session after the process that was capturing
     * has actually been killed. Nothing in this package queries `:data` for "the most recently
     * open session" to recover from that case, and building that is outside WP1's owned files.
     */
    private fun startCaptureAndShowStatus() {
        val liveSessionId = CaptureState.sessionId.takeIf { CaptureState.isCapturing }
        val effectiveSessionId = liveSessionId ?: sessionId
        if (liveSessionId == null) {
            val intent = Intent(
                this,
                RealCaptureService::class.java,
            ).putExtra(RealCaptureService.EXTRA_SESSION_ID, sessionId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        }
        // P13's Compose navigation host is now the reader (build-plan P13's "done when" switchover,
        // deliberately deferred out of P13 itself because P12 owned this file at the time).
        // StatusActivity/TransmissionListActivity remain registered and working — their Compose
        // ports live behind this nav host, and removing the originals belongs to P14, which
        // replaces the screens rather than merely re-hosting them.
        startActivity(
            Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_SESSION_ID, effectiveSessionId),
        )
        finish()
    }

    /** [notificationsSkipped] folds S03's diagnostic-only skip into "granted" the same way the
     * pre-WP9 flow did — notifications never gate capture (constitution: R-002). */
    private fun currentPermissionsState(notificationsSkipped: Boolean): PermissionsState {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return PermissionsState(
            recordAudioGranted = granted(Manifest.permission.RECORD_AUDIO),
            notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                granted(Manifest.permission.POST_NOTIFICATIONS) ||
                notificationsSkipped,
            isIgnoringBatteryOptimizationsDiagnosticOnly = pm.isIgnoringBatteryOptimizations(packageName),
        )
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    internal companion object {
        const val KEY_SESSION_ID = "session_id"
    }
}
