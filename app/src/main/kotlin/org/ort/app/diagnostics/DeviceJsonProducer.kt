package org.ort.app.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.ort.app.BuildConfig
import org.ort.pipeline.capture.ThermalStatus

/**
 * WP11e (register R-137, `Settings-Diagnostics.dc.html`'s `device.json`, brief step 4): "SoC, RAM,
 * Android version, OEM, thermal history · no serial, no IMEI, no account" — the closed field set
 * below is the whole of what this producer reads. It never calls
 * [android.telephony.TelephonyManager], never reads `Settings.Secure.ANDROID_ID`, and never touches
 * an account manager — there is structurally nothing here that *could* leak a serial, an IMEI, an
 * advertising id or an account, not merely an omission a future edit could quietly undo
 * ([DeviceJsonProducerTest] proves the closed key set, not just the four forbidden substrings).
 *
 * [ramClassLabel] and [thermalHeadroomClass] are top-level so they are testable as the pure
 * functions they are, without an Android [Context].
 */
public object DeviceJsonProducer : DiagnosticsFileProducer {

    override suspend fun produce(context: Context): ByteArray = withContext(Dispatchers.IO) {
        val json = JSONObject()
        json.put("model", Build.MODEL ?: "unknown")
        json.put("manufacturer", Build.MANUFACTURER ?: "unknown")
        json.put("androidVersion", Build.VERSION.RELEASE ?: "unknown")
        json.put("sdkInt", Build.VERSION.SDK_INT)
        json.put("abi", Build.SUPPORTED_ABIS?.firstOrNull() ?: "unknown")
        json.put("appVersion", appVersionLabel(context))
        json.put("gitShortCommit", BuildConfig.GIT_SHORT_COMMIT)
        json.put("ramClass", ramClassLabel(totalMemBytes(context)))
        json.put("thermalHeadroomClass", thermalHeadroomClass(ThermalStatus.state))
        json.toString(2).toByteArray(Charsets.UTF_8)
    }

    /** The real `versionName` this build shipped as — never `BuildConfig.GIT_SHORT_COMMIT` alone,
     * and never a fabricated value on the (rare, packaging-only) failure path. */
    private fun appVersionLabel(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "dev build"
    } catch (e: PackageManager.NameNotFoundException) {
        "dev build"
    }

    /** `0` when no [ActivityManager] is available to ask (never fabricated) — [ramClassLabel]
     * reports that honestly as `"unknown"` rather than a fictitious `"0 GB class"`. */
    private fun totalMemBytes(context: Context): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 0L
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info.totalMem
    }
}

/**
 * Bucketed to the nearest whole GB, never a raw byte count — a coarse "class", not a per-device
 * fingerprint. `0` or negative (no real reading available) reports `"unknown"`, never `"0 GB
 * class"` — constitution I: an absent measurement is not the same fact as a measured zero.
 */
public fun ramClassLabel(totalMemBytes: Long): String {
    if (totalMemBytes <= 0L) return "unknown"
    val roundedGb = Math.round(totalMemBytes / (1024.0 * 1024.0 * 1024.0))
    return "$roundedGb GB class"
}

/** [ThermalStatus.State]'s own tri-state, relabelled for the bundle's operator-facing field. */
public fun thermalHeadroomClass(state: ThermalStatus.State): String = when (state) {
    is ThermalStatus.State.Nominal -> "nominal"
    is ThermalStatus.State.Warm -> "warm"
    is ThermalStatus.State.Hot -> "hot"
}
