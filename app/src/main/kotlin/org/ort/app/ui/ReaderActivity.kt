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
import org.ort.app.ui.settings.SettingsScreenId
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
 * [EXTRA_SETTINGS_SCREEN] (round 5, defined here for WP9 to pass later): the name of a
 * [SettingsScreenId] to land [org.ort.app.ui.settings.SettingsContent] on directly, alongside
 * [EXTRA_DESTINATION]`=SETTINGS` — Setup S12's `Install` action is its intended first caller (it
 * wants `Assets`, not the `Settings` root); no caller passes it yet, since S12 itself is WP9's file,
 * outside this package's row this round. An unset, unrecognised, or absent value falls back to
 * `null` (the `Settings` root), the same "honest until wired" treatment [resolveInitialDestination]
 * already gives [EXTRA_DESTINATION].
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
 * wired the remaining recovery actions through a [org.ort.app.ui.navigation.ReaderNavigator],
 * shared with [OrtNavHost] below so both act on the same drawer state; round 5 upgrades three of
 * them from "lands on the `Settings` root, the operator finds the row themselves" to "lands
 * directly on the real sub-screen", now that WP10 merged `SettingsContent.initialScreen`
 * (confirmed by reading that file before rewiring this):
 * - `onOpenBatteryExemptionSettings` still launches the OS's own battery-exemption settings screen
 *   directly (WP11b's own wiring, unchanged — it names a real platform surface this package has no
 *   in-app equivalent for).
 * - `onOpenStorageSettings`/`onOpenRetentionSettings` (F6, "Free up space"/"Retention") now open
 *   [org.ort.app.ui.navigation.ReaderNavigator.openSettings] with [SettingsScreenId.STORAGE] —
 *   landing directly on the real Storage sub-screen, not its root.
 * - `onReconnectRig` (F9, "Reconnect") is a real target for the first time: `openSettings` with
 *   [SettingsScreenId.RIG] — previously a documented no-op stub, since navigating anywhere useful
 *   needed the same sub-screen entry this round adds.
 * - `onSetFrequencyByHand` now also uses `openSettings`, with [SettingsScreenId.CAPTURE] rather
 *   than `RIG` — reading `SettingsCaptureScreen.kt` before wiring this found the hand-entered
 *   frequency row ("Log overs against, MHz", subline "used only while the rig is disconnected or
 *   absent" — exactly this failure's own condition) lives under `Settings-Capture`, not
 *   `Settings-Rig`; the round-3 comment that guessed `Rig` was wrong, corrected here.
 * - `onChooseAnotherInput` still calls [org.ort.app.ui.navigation.ReaderNavigator.openSetupInput] —
 *   see that function's own doc comment for why it cannot reliably reach Setup's `Input` step.
 * - `onRetryInput`/`onEndSession`/`onRequestUsbPermission` stay documented no-op stubs: "retry the
 *   current input", "end the session" and "grant USB permission" are capture/system actions, not
 *   navigation — this package's row is everything under `ui/navigation`, and neither
 *   `:capture-android` nor `:pipeline` exposes a callable entry point for any of the three today
 *   (checked again this round; unchanged from round 3/4's own finding).
 * - "N06 `Adjust`" (round 5 brief) has no real target to route: `LevelMeterScreen.kt`'s own text
 *   ("In the band. Nothing to adjust.", R-175) is a status sentence, not a button — grepped
 *   `CaptureStatusContent.kt`/`LevelMeterScreen.kt` for "Adjust" again this round and found nothing,
 *   confirming round 4's own conclusion. `Settings-Capture`'s own re-verify action needs no routing
 *   here either — it is already real, wired entirely inside `ui/settings/SettingsContent.kt`
 *   (`onOpenInputSetup`, confirmed by reading that file), not a `FailureHostActions` entry.
 *
 * Register R-178 (WP11b follow-up): [FailureHost]'s `content` slot now hands back the currently
 * showing banner's real, measured height — forwarded straight into [OrtNavHost]'s own
 * `contentTopPadding` so a banner that grows taller (font scale 2.0) pushes the destination
 * content down to clear itself instead of just covering more of it.
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
        val initialSettingsScreen = resolveInitialSettingsScreen(intent?.getStringExtra(EXTRA_SETTINGS_SCREEN))
        setContent {
            OrtTheme {
                val navigator = rememberReaderNavigator(
                    initialDestination = initialDestination,
                    initialSettingsScreen = initialSettingsScreen,
                )
                FailureHost(
                    sessionId = sessionId,
                    actions = FailureHostActions(
                        onChooseAnotherInput = { navigator.openSetupInput() },
                        onOpenBatteryExemptionSettings = {
                            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        },
                        onOpenStorageSettings = { navigator.openSettings(SettingsScreenId.STORAGE) },
                        onOpenRetentionSettings = { navigator.openSettings(SettingsScreenId.STORAGE) },
                        onSetFrequencyByHand = { navigator.openSettings(SettingsScreenId.CAPTURE) },
                        onReconnectRig = { navigator.openSettings(SettingsScreenId.RIG) },
                    ),
                ) { contentTopPadding ->
                    OrtNavHost(sessionId = sessionId, navigator = navigator, contentTopPadding = contentTopPadding)
                }
            }
        }
    }

    public companion object {
        public const val EXTRA_SESSION_ID: String = "session_id"
        public const val EXTRA_DESTINATION: String = "destination"
        public const val EXTRA_SETTINGS_SCREEN: String = "settings_screen"
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
 * [ReaderActivity.EXTRA_SETTINGS_SCREEN]'s parse (round 5): the same "fall back rather than crash"
 * shape as [resolveInitialDestination], for the identical reason — `null` (unset, unrecognised, or
 * a caller that never passes this extra at all) opens `SettingsContent`'s root, exactly as before
 * this extra existed.
 */
internal fun resolveInitialSettingsScreen(extra: String?): SettingsScreenId? =
    SettingsScreenId.entries.firstOrNull { it.name == extra }

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
