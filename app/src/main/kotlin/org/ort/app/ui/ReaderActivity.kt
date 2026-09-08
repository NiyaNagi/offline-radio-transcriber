package org.ort.app.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.ort.app.ui.failures.FailureHost
import org.ort.app.ui.failures.FailureHostActions
import org.ort.app.ui.navigation.OrtNavHost
import org.ort.app.ui.theme.OrtSystemBarStyle
import org.ort.app.ui.theme.OrtTheme
import org.ort.pipeline.capture.CaptureState

/**
 * Hosts the Compose navigation graph (build-plan P13, D15). Not yet the app's launcher — see
 * `AndroidManifest.xml`'s comment and the build-plan's own "done when": this prompt lands the
 * foundation reachable and tested, and the switchover that makes it the app's actual entry point
 * is a deliberate follow-up once P12's capture wiring lands (they touch adjacent but disjoint
 * files this wave, per the standing "do not touch" scoping).
 *
 * [EXTRA_SESSION_ID] mirrors [org.ort.app.status.StatusActivity] and
 * [org.ort.app.transmissions.TransmissionListActivity]'s own extra, so this activity can be
 * launched the same way they are for the v0 smoke-test path (`ui/data/ReaderPolling.kt`).
 *
 * audit F-022: [CaptureState.sessionId] -- published by `RealCaptureService.startCapture()` the
 * moment capture actually starts -- is preferred over the intent extra whenever the two differ and
 * capture is genuinely live. This activity is not only reached through `MainActivity`'s own fix for
 * the same finding: without this, any other path back here (a saved/restored task, a future deep
 * link) could still poll a stale or phantom session while a real one is running — see
 * [resolveSessionId] below, which is what actually computes this now (ui-conformance-plan R-007,
 * pulled out to a pure function for testability).
 *
 * ui-conformance-plan R-001: [enableEdgeToEdge] draws content behind a transparent status/
 * navigation bar rather than under an opaque platform one — see `res/values/themes.xml`'s
 * `Theme.Ort` and [OrtTheme]'s own doc comment for the rest of R-001/R-006.
 *
 * ui-conformance-plan R-008: [OrtSystemBarStyle] keeps the status/navigation bar icons light
 * regardless of the OS's night-mode state — see that constant's own doc comment.
 *
 * ui-conformance-plan WP11b, register R-100/R-101: [org.ort.app.ui.failures.FailureHost] mounts
 * here, above [OrtNavHost] — the one edit this package makes to this file (its own row names
 * nothing else in this class). Two of its recovery actions reach a real platform surface directly
 * ([onOpenBatteryExemptionSettings]/[onOpenStorageSettings] launch OS Settings screens that need
 * no manifest permission); the rest — choosing another input, retrying, reconnecting the rig,
 * setting a frequency by hand, requesting USB permission — have no destination or entry point
 * this package can reach without editing a file another package owns (`OrtNavHost.kt` is WP3's;
 * `:capture-android` exposes no USB-permission call yet, FR-RIG unbuilt). Those stay documented
 * no-op stubs at [FailureHost]'s own default — see this package's report for exactly what each
 * needs and from whom.
 */
public class ReaderActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = OrtSystemBarStyle, navigationBarStyle = OrtSystemBarStyle)
        val sessionId = resolveSessionId(
            intentSessionId = intent?.getStringExtra(EXTRA_SESSION_ID),
            liveSessionId = CaptureState.sessionId,
            isCapturing = CaptureState.isCapturing,
        )
        setContent {
            OrtTheme {
                FailureHost(
                    sessionId = sessionId,
                    actions = FailureHostActions(
                        onOpenBatteryExemptionSettings = {
                            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        },
                        onOpenStorageSettings = {
                            startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
                        },
                        onOpenRetentionSettings = {
                            startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
                        },
                    ),
                ) {
                    OrtNavHost(sessionId = sessionId)
                }
            }
        }
    }

    public companion object {
        public const val EXTRA_SESSION_ID: String = "session_id"
    }
}

/**
 * R-007's routing decision, pulled out as a pure function (no `Activity`, no `Context`) so it is
 * directly unit-testable without composing [OrtNavHost] — building a real [ReaderActivity] with a
 * non-null session id starts `OrtNavHost`'s `Now`/`Log` polling `LaunchedEffect(sessionId) {
 * while (true) { ...; delay(2000) } }` loops (`OrtNavHost.kt`), which a Robolectric-driven test
 * never gets a chance to cleanly cancel; that is what was poisoning `ActivityPatternChartTest`'s
 * Compose idle-check when the full `:app` suite ran, even after the activity was destroyed
 * end-to-end. [resolveSessionId] is exactly what [ReaderActivity.onCreate] computes, so
 * `ReaderActivityTest`'s `R_007` cases assert it directly and never build the activity at all.
 */
internal fun resolveSessionId(intentSessionId: String?, liveSessionId: String?, isCapturing: Boolean): String? {
    val effectiveLiveSessionId = liveSessionId.takeIf { isCapturing }
    return effectiveLiveSessionId ?: intentSessionId
}
