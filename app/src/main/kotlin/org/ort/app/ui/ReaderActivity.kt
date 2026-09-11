package org.ort.app.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.ort.app.ui.data.ModelsController
import org.ort.app.ui.failures.FailureHostActions
import org.ort.app.ui.navigation.NavSeed
import org.ort.app.ui.navigation.OrtNavHost
import org.ort.app.ui.navigation.ReaderDestination
import org.ort.app.ui.navigation.rememberReaderNavigator
import org.ort.app.ui.settings.SettingsScreenId
import org.ort.app.ui.theme.OrtSystemBarStyle
import org.ort.app.ui.theme.OrtTheme
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.digest.ForegroundActivityTracker

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
 * Round 13 (WP12's screenshot-tour seam): [org.ort.app.ui.navigation.NavSeed.fromIntent] reads
 * this same [intent] for that class's own extras (a drill-in id, `Log`'s R-276 filter, `Capture`'s
 * level meter, `Earlier nights`'s Review-seeded detail) — see its own doc comment for the exact
 * extra names and what it deliberately leaves out. No caller passes any of them directly to this
 * activity yet; `org.ort.app.debug.ScenarioReaderActivity` (WP4's file, `app/src/debug`) is the
 * intended first one, forwarding whatever `adb shell am start` gives it.
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
 * ui-conformance-plan WP11b, register R-100/R-101: [org.ort.app.ui.failures.FailureHost] used to
 * mount here, above [OrtNavHost] — the one edit WP11b made to this file. **Round 11, register
 * R-334 (halt):** moved to mount *inside* [OrtNavHost] instead — with `FailureHost` wrapping the
 * whole of this activity's content, its own banner overlay painted above `OrtNavHost`'s drawer
 * panel too, opaque, hiding most of the drawer's rows once open
 * (`storage-warn/N00-menu-with-banner-pass3.png`) — `OrtNavHost.kt`'s own doc comment on its new
 * `failureActions` parameter records the matching half of this move; this activity now builds the
 * same [org.ort.app.ui.failures.FailureHostActions] it always did and simply hands them straight
 * to [OrtNavHost] instead of to its own `FailureHost` call. Round 3 (WP3, this package) wired the
 * remaining recovery actions through a [org.ort.app.ui.navigation.ReaderNavigator], shared with
 * [OrtNavHost] below so both act on the same drawer state; round 5 upgrades three of
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
 * - **WPF (checklist row E2-F08, F23):** `onRetryInput`/`onSwitchToWiredInput` — F23's own two
 *   recovery actions (`Fail-Bluetooth-Audio.dc.html`) — now call the same
 *   [org.ort.app.ui.navigation.ReaderNavigator.openSetupInput] `onChooseAnotherInput` already uses:
 *   there is no separate "just retry the current device" or "just pick wired" primitive under
 *   `:capture-android`/`:pipeline` today (checked again this round), and Setup's own `Input` step
 *   is the one real surface that re-verifies a route and lets the operator choose a different,
 *   wired device — the honest destination for both, not a fabricated shortcut. `onRetryInput` is
 *   shared with F2's `FailDisconnectBanner` ("Retry"), which gets the identical, real behaviour for
 *   the first time in the same change — checked directly, no F2 test regressed. WPE's own row
 *   report named exactly these two lines as owed to this package (`FailureHostActions` is
 *   `ui/failures`, this package's row is `ui/navigation` plus this activity's own wiring).
 * - `onEndSession`/`onRequestUsbPermission` stay documented no-op stubs: "end the session" and
 *   "grant USB permission" are capture/system actions, not navigation, and neither
 *   `:capture-android` nor `:pipeline` exposes a callable entry point for either today (checked
 *   again this round; unchanged from round 3/4's own finding).
 * - "N06 `Adjust`" (round 5 brief) has no real target to route: `LevelMeterScreen.kt`'s own text
 *   ("In the band. Nothing to adjust.", R-175) is a status sentence, not a button — grepped
 *   `CaptureStatusContent.kt`/`LevelMeterScreen.kt` for "Adjust" again this round and found nothing,
 *   confirming round 4's own conclusion. `Settings-Capture`'s own re-verify action needs no routing
 *   here either — it is already real, wired entirely inside `ui/settings/SettingsContent.kt`
 *   (`onOpenInputSetup`, confirmed by reading that file), not a `FailureHostActions` entry.
 *
 * Register R-178 (WP11b follow-up): [org.ort.app.ui.failures.FailureHost]'s `content` slot hands
 * back the currently showing banner's real, measured height, so a banner that grows taller (font
 * scale 2.0) pushes the destination content down to clear itself instead of just covering more of
 * it — entirely internal to [OrtNavHost] since round 11's move above, nothing for this activity to
 * forward any more.
 */
public class ReaderActivity : ComponentActivity() {

    // WPE (E2-I03's own "owed by WPE" note, FR-DIG-5): [ForegroundActivityTracker] is
    // [org.ort.pipeline.digest.AndroidProseDigestDeviceSignals]'s own looser, reliable-on-ColorOS
    // half of its idle definition (see that class's doc comment) — this is the operator's main
    // reading surface, so its own resume/pause is the one signal in this app that most directly
    // means "the operator is looking at this app right now" / "just stopped looking at it".
    override fun onResume() {
        super.onResume()
        ForegroundActivityTracker.markActive()
    }

    override fun onPause() {
        super.onPause()
        ForegroundActivityTracker.markActive()
    }

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
        // Round 13 (WP12's screenshot-tour seam): `null` for any intent that carries none of
        // `NavSeed`'s own extras — see that class's own `fromIntent` doc comment — so every
        // ordinary launch (no caller of this activity passes one yet) behaves exactly as before.
        val seed = intent?.let { NavSeed.fromIntent(it) }
        setContent {
            OrtTheme {
                val navigator = rememberReaderNavigator(
                    initialDestination = initialDestination,
                    initialSettingsScreen = initialSettingsScreen,
                    seed = seed,
                )
                OrtNavHost(
                    sessionId = sessionId,
                    seed = seed,
                    navigator = navigator,
                    failureActions = FailureHostActions(
                        onChooseAnotherInput = { navigator.openSetupInput() },
                        // WPF (checklist row E2-F08, F23): both of F23's own recovery actions
                        // route to the same real re-verify entry `onChooseAnotherInput` already
                        // uses — see this class's own doc comment above for why there is no
                        // separate real target for either.
                        onRetryInput = { navigator.openSetupInput() },
                        onSwitchToWiredInput = { navigator.openSetupInput() },
                        onOpenBatteryExemptionSettings = {
                            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        },
                        onOpenStorageSettings = { navigator.openSettings(SettingsScreenId.STORAGE) },
                        onOpenRetentionSettings = { navigator.openSettings(SettingsScreenId.STORAGE) },
                        onSetFrequencyByHand = { navigator.openSettings(SettingsScreenId.CAPTURE) },
                        onReconnectRig = { navigator.openSettings(SettingsScreenId.RIG) },
                        // Round 14 (R-448's own report): F14's "‹ Earlier nights" and F21/F22's
                        // own "‹ Models and lexicon" headers now have a real target — the same
                        // `navigator.open`/`openSettings` calls every other real recovery action
                        // above already uses.
                        onOpenEarlierNights = { navigator.open(ReaderDestination.EARLIER_NIGHTS) },
                        onOpenModels = { navigator.openSettings(SettingsScreenId.ASSETS) },
                        // FR-AST-4 (register R-448 follow-up): F21's "Activate now" — the real
                        // `ModelsController.activateStaged` call, direct, exactly as safe to call
                        // here as `ModelsContent.kt`'s own `activateStagedOnOpen` (that function's
                        // own kdoc: "a safe no-op whenever a session is still live").
                        onActivateStagedAsset = {
                            lifecycleScope.launch { ModelsController.activateStaged(this@ReaderActivity) }
                        },
                    ),
                )
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
