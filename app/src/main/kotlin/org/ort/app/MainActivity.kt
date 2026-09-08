package org.ort.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.ort.app.permissions.PermissionsFlow
import org.ort.app.permissions.PermissionsState
import org.ort.app.ui.ReaderActivity
import org.ort.core.Ulid
import org.ort.pipeline.capture.RealCaptureService

/**
 * **v0 smoke-test wiring, not build-plan P8's promised Compose navigation host** (that remains a
 * later session's job — see `app/build.gradle.kts`'s own comment). This drives
 * [PermissionsFlow]'s real Android side (actual runtime permission requests, not just the state
 * machine P8 already tested) so a debug build genuinely starts real capture and shows the real
 * [StatusActivity], for on-device validation ahead of the M0/M2 hardware/registration steps this
 * project is otherwise blocked on.
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private var sessionId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this)
        setContentView(statusView)
        sessionId = savedInstanceState?.getString(KEY_SESSION_ID) ?: Ulid.generate().value
        advance()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SESSION_ID, sessionId)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        advance()
    }

    /**
     * Re-reads real permission state and asks for the next thing needed, or starts capture.
     *
     * **Bug found by on-device testing, not any test suite**: the first version of this method
     * used [PermissionsFlow.nextStep], which sequences through `BATTERY_EXEMPTION` *before*
     * `DONE` — so capture never started until that step completed. That
     * directly contradicts [PermissionsFlow.captureIsPermitted]'s own contract (and this file's
     * own comment on the old battery-exemption branch): battery exemption is diagnostic-only and
     * must never gate capture readiness. On a real device, `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
     * also threw `SecurityException` (a required manifest permission was missing — now added),
     * which meant that step never completed at all, and this bug turned a diagnostic-only ask
     * into a permanent block on ever starting capture. Fixed by checking
     * [PermissionsFlow.captureIsPermitted] directly and treating the battery-exemption request as
     * fire-and-forget, asked but never awaited.
     */
    private fun advance() {
        val state = currentPermissionsState()
        when {
            !state.recordAudioGranted -> {
                statusView.text = getString(R.string.placeholder_running) + "\n\nRequesting microphone access…"
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE)
            }
            !state.notificationsGranted -> {
                statusView.text = getString(R.string.placeholder_running) + "\n\nRequesting notification access…"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        REQUEST_CODE,
                    )
                } else {
                    advance() // nothing to request pre-33; PermissionsState already reports it granted
                }
            }
            else -> {
                check(PermissionsFlow.captureIsPermitted(state)) { "mic and notifications granted but not permitted" }
                requestBatteryExemptionBestEffort(state)
                startCaptureAndShowStatus()
            }
        }
    }

    /**
     * Fire-and-forget: asked for the OS's sake, never awaited, never blocks capture (AC-65,
     * constitution IV). Swallows every way this can fail — a missing manifest permission on an
     * older install, an OEM that restricts or has no handler for this action at all — because
     * none of those are reasons to keep the microphone from working.
     */
    private fun requestBatteryExemptionBestEffort(state: PermissionsState) {
        if (state.isIgnoringBatteryOptimizationsDiagnosticOnly) return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            startActivity(intent)
        } catch (e: SecurityException) {
            android.util.Log.w(TAG, "battery-exemption request denied (diagnostic-only, ignoring)", e)
        } catch (e: android.content.ActivityNotFoundException) {
            android.util.Log.w(TAG, "no handler for battery-exemption request (diagnostic-only, ignoring)", e)
        }
    }

    private fun startCaptureAndShowStatus() {
        val intent = Intent(
            this,
            RealCaptureService::class.java,
        ).putExtra(RealCaptureService.EXTRA_SESSION_ID, sessionId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        // P13's Compose navigation host is now the reader (build-plan P13's "done when" switchover,
        // deliberately deferred out of P13 itself because P12 owned this file at the time).
        // StatusActivity/TransmissionListActivity remain registered and working — their Compose
        // ports live behind this nav host, and removing the originals belongs to P14, which
        // replaces the screens rather than merely re-hosting them.
        startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_SESSION_ID, sessionId))
        finish()
    }

    private fun currentPermissionsState(): PermissionsState {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return PermissionsState(
            recordAudioGranted = granted(Manifest.permission.RECORD_AUDIO),
            notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                granted(Manifest.permission.POST_NOTIFICATIONS),
            isIgnoringBatteryOptimizationsDiagnosticOnly = pm.isIgnoringBatteryOptimizations(packageName),
        )
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "MainActivity"
        const val REQUEST_CODE = 1001
        const val KEY_SESSION_ID = "session_id"
    }
}
