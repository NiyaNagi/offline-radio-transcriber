package org.ort.capture.android.oem

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Resolves each [OemStep.deepLinkAction] against the device's actual `PackageManager`
 * (`queryIntentActivities`), dropping the deep link (but keeping the step's manual instructions)
 * when nothing on the device can handle it.
 */
public class OemGuidanceResolver(private val context: Context) {

    public fun resolve(guidance: OemGuidance): OemGuidance {
        val pm = context.packageManager
        val resolved = guidance.steps.map { step ->
            val action = step.deepLinkAction ?: return@map step
            val resolves = pm.queryIntentActivities(Intent(action), PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
            if (resolves) step else step.copy(deepLinkAction = null)
        }
        return guidance.copy(steps = resolved)
    }
}
