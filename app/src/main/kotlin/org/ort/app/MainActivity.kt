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
import org.ort.app.permissions.PermissionStep
import org.ort.app.permissions.PermissionsFlow
import org.ort.app.permissions.PermissionsState
import org.ort.app.status.StatusActivity
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_BATTERY_EXEMPTION) advance()
    }

    /** Re-reads real permission state and asks for the next thing [PermissionsFlow] wants, or starts capture. */
    private fun advance() {
        val state = currentPermissionsState()
        when (PermissionsFlow.nextStep(state)) {
            PermissionStep.RECORD_AUDIO -> {
                statusView.text = getString(R.string.placeholder_running) + "\n\nRequesting microphone access…"
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE)
            }
            PermissionStep.NOTIFICATIONS -> {
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
            PermissionStep.BATTERY_EXEMPTION -> {
                // Diagnostic-only per PermissionsFlow's own doc comment (AC-65, constitution IV) —
                // requested for the OS's sake, never trusted as proof capture will keep running.
                statusView.text = getString(R.string.placeholder_running) +
                    "\n\nAsking to ignore battery optimisation (does not gate capture readiness)…"
                val intent =
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQUEST_BATTERY_EXEMPTION)
            }
            PermissionStep.DONE -> startCaptureAndShowStatus()
        }
    }

    private fun startCaptureAndShowStatus() {
        val intent = Intent(
            this,
            RealCaptureService::class.java,
        ).putExtra(RealCaptureService.EXTRA_SESSION_ID, sessionId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        startActivity(Intent(this, StatusActivity::class.java).putExtra(StatusActivity.EXTRA_SESSION_ID, sessionId))
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
        const val REQUEST_CODE = 1001
        const val REQUEST_BATTERY_EXEMPTION = 1002
        const val KEY_SESSION_ID = "session_id"
    }
}
