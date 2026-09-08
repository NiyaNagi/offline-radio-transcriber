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
import org.ort.app.ui.navigation.ReaderDestination
import org.ort.app.ui.navigation.rememberReaderNavigator
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
 * [EXTRA_DESTINATION] (ui-conformance-plan WP3, round 3): the name of a [ReaderDestination] to
 * open on launch, read once in [onCreate] and handed to [org.ort.app.ui.navigation.ReaderNavigator]
 * as its starting destination — Setup's S12 `Install` action and the persistent capture
 * notification's `Open` action both pass it (WP9's and `:pipeline`'s own files respectively; this
 * activity only consumes the extra, per this package's row). An unset or unrecognised extra value
 * falls back to [ReaderDestination.NOW], exactly as no extra at all does.
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
 * here, above [OrtNavHost] — the one edit WP11b made to this file. Round 3 (WP3, this package)
 * wires the remaining recovery actions through a [org.ort.app.ui.navigation.ReaderNavigator],
 * shared with [OrtNavHost] below so both act on the same drawer state:
 * - `onOpenBatteryExemptionSettings` still launches the OS's own battery-exemption settings screen
 *   directly (WP11b's own wiring, unchanged — it names a real platform surface this package has no
 *   in-app equivalent for).
 * - `onOpenStorageSettings`/`onOpenRetentionSettings` ("Free up space"/"Retention") now open this
 *   app's own `Settings` destination via [org.ort.app.ui.navigation.ReaderNavigator.openSettingsStorage]
 *   instead of the OS storage settings screen, now that WP10's real Storage sub-screen exists —
 *   landing on `Settings`' *root*, not the `Storage` sub-screen directly, since `SettingsContent`
 *   exposes no way to open at a specific sub-screen (see that function's own report/CHANGENOTE).
 * - `onSetFrequencyByHand` opens `Settings` the same way, for the same reason (the Rig sub-screen
 *   is where a hand-entered frequency would live, and is equally unreachable directly).
 * - `onChooseAnotherInput` calls [org.ort.app.ui.navigation.ReaderNavigator.openSetupInput] —
 *   see that function's own doc comment for why it cannot reliably reach Setup's `Input` step.
 * - `onRetryInput`/`onReconnectRig` stay documented no-op stubs: "retry the current input" and
 *   "reconnect the rig" are capture/rig actions, not navigation — this package's row is everything
 *   under `ui/navigation`, and neither `:capture-android` nor `:pipeline` (F9's rig module,
 *   unbuilt — register R-084) exposes a callable retry/reconnect entry point today.
 *   `onEndSession`/`onRequestUsbPermission` are untouched this round (not named in this round's
 *   brief).
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
        val initialDestination = resolveInitialDestination(intent?.getStringExtra(EXTRA_DESTINATION))
        setContent {
            OrtTheme {
                val navigator = rememberReaderNavigator(initialDestination = initialDestination)
                FailureHost(
                    sessionId = sessionId,
                    actions = FailureHostActions(
                        onChooseAnotherInput = { navigator.openSetupInput() },
                        onOpenBatteryExemptionSettings = {
                            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        },
                        onOpenStorageSettings = { navigator.openSettingsStorage() },
                        onOpenRetentionSettings = { navigator.openSettingsStorage() },
                        onSetFrequencyByHand = { navigator.open(ReaderDestination.SETTINGS) },
                    ),
                ) {
                    OrtNavHost(sessionId = sessionId, navigator = navigator)
                }
            }
        }
    }

    public companion object {
        public const val EXTRA_SESSION_ID: String = "session_id"
        public const val EXTRA_DESTINATION: String = "destination"
    }
}

/**
 * [ReaderActivity.EXTRA_DESTINATION]'s parse: an unset or unrecognised value falls back to
 * [ReaderDestination.NOW] rather than crashing on a name a future enum entry, an old notification
 * pending-intent, or a typo produced.
 */
internal fun resolveInitialDestination(extra: String?): ReaderDestination =
    ReaderDestination.entries.firstOrNull { it.name == extra } ?: ReaderDestination.NOW

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
