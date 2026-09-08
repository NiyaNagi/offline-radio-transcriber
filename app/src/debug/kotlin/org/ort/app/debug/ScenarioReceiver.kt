package org.ort.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * spec/ui-conformance-plan.md WP0, register R-110 — the scenario simulator's entry point.
 *
 * Debug-build-only (`app/src/debug/AndroidManifest.xml`, `android:exported="true"`), so
 * `tools/ui-audit/scenario.ps1` can put the app into any of [Scenarios.NAMES] by broadcast:
 *
 * ```
 * adb shell am broadcast -a org.ort.app.debug.SCENARIO --es name overnight
 * ```
 *
 * A `BroadcastReceiver.onReceive` runs on the main thread and must return quickly; [Scenarios.load]
 * does real Room I/O, so this uses [goAsync] to keep the receiver alive across the coroutine that
 * does the work, and reports the outcome two ways so a caller need not depend on either alone:
 *
 * 1. **Logcat** (`Log.i(TAG, ...)`), tag [TAG] — the format `scenario <name>: <n> transmissions,
 *    <m> sessions` a validator's `scenario.ps1` greps for (this package's brief, verbatim), plus a
 *    second line naming the primary session id when the scenario has one.
 * 2. **The broadcast's own result extras** (`setResultExtras`), under [EXTRA_SESSION] — so
 *    `adb shell am broadcast`'s own stdout (`Broadcast completed: result=0, ..., extras: ...`)
 *    carries the session id too, without requiring a logcat round-trip.
 */
public class ScenarioReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra(EXTRA_NAME)
        if (name.isNullOrBlank()) {
            Log.w(TAG, "scenario broadcast received with no '$EXTRA_NAME' extra — ignoring")
            return
        }
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val result = Scenarios.load(appContext, name)
                Log.i(
                    TAG,
                    "scenario $name: ${result.transmissionCount} transmissions, ${result.sessionCount} sessions",
                )
                if (result.primarySessionId != null) {
                    Log.i(TAG, "scenario $name: session=${result.primarySessionId}")
                    pending.setResultExtras(Bundle().apply { putString(EXTRA_SESSION, result.primarySessionId) })
                }
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "scenario $name: unknown scenario name", e)
            } catch (e: Exception) {
                Log.e(TAG, "scenario $name: failed to load", e)
            } finally {
                pending.finish()
            }
        }
    }

    public companion object {
        public const val ACTION_SCENARIO: String = "org.ort.app.debug.SCENARIO"
        public const val EXTRA_NAME: String = "name"

        /** Output-only: the primary session id, carried in the broadcast's result extras and logged. */
        public const val EXTRA_SESSION: String = "session"
        private const val TAG = "ScenarioReceiver"
    }
}
