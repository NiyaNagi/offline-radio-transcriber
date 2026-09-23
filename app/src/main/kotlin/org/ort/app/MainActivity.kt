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
import kotlinx.coroutines.runBlocking
import org.ort.app.permissions.PermissionsState
import org.ort.app.ui.ReaderActivity
import org.ort.app.ui.setup.DebugOvernightSurvivalOverride
import org.ort.app.ui.setup.OvernightNagState
import org.ort.app.ui.setup.RealOvernightSurvivalChecker
import org.ort.app.ui.setup.SetupActivity
import org.ort.app.ui.setup.SetupStateMachine
import org.ort.app.ui.setup.SetupStore
import org.ort.app.ui.setup.SharedPreferencesSetupStore
import org.ort.app.ui.theme.OrtSystemBarStyle
import org.ort.core.Ulid
import org.ort.data.OrtDatabase
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.RealCaptureService
import org.ort.pipeline.digest.ForegroundActivityTracker

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

    // WPE (E2-I03's own "owed by WPE" note, FR-DIG-5) — see `ReaderActivity.onResume`'s own doc
    // comment for what this backs. This activity finishes immediately after routing (its own class
    // kdoc), so `onResume` here marks only the brief moment it was genuinely in front — real,
    // if short-lived, foreground activity, not nothing.
    override fun onResume() {
        super.onResume()
        ForegroundActivityTracker.markActive()
    }

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
     * sequence rather than silently failing to start capture) — and, if so, has overnight survival
     * actually been proven ([overnightSurvivalStillUnproven]). `true`/`false` respectively run the
     * unchanged [startCaptureAndShowStatus] path below; either gate failing hands off to
     * [SetupActivity], which resumes at the first unverified step (`Flow-Setup.dc.html`) and
     * returns here once `Start capture` is tapped.
     */
    private fun route() {
        val prefs = getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, MODE_PRIVATE)
        val store = SharedPreferencesSetupStore(prefs)
        val setupComplete =
            SetupStateMachine.isComplete(currentPermissionsState(store.notificationsSkipped), store.snapshot())
        if (setupComplete && !overnightSurvivalStillUnproven(store)) {
            startCaptureAndShowStatus()
        } else {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
        }
    }

    /**
     * R-1104 (register; AC-189, NFR-8, constitution IV): [SetupStateMachine.isComplete] alone lets
     * an operator who finished setup once, and always launches through this fast path, skip
     * [SetupActivity]'s own `reconcileOvernightSurvival()` forever — the one place that re-derives
     * [SetupStore.overnightSurvivalProven] from real session evidence
     * ([org.ort.app.ui.setup.OvernightSurvivalChecker]). On ColorOS, whose
     * `isIgnoringBatteryOptimizations()` reports wrongly (constitution IV — never trusted as
     * evidence here either), that is exactly the population this check exists to catch, and this
     * fast path was its only blind spot: an operator who never revisits Setup was never re-asked.
     *
     * This runs the identical check [SetupActivity]'s own `reconcileOvernightSurvival()` runs — a
     * real session, cleanly ended, at least `OVERNIGHT_SURVIVAL_THRESHOLD_MILLIS` long, never the
     * OS's own exemption flag — and, once proven, persists it exactly as that function does, so a
     * device that has already proven survival is never routed through Setup again to re-ask
     * (AC-189's own "until"; [SetupStore.overnightSurvivalProven]'s own doc comment covers the two
     * real writers this and [SetupActivity] now are). A no-op, returning `false` immediately, once
     * already proven — the ordinary case for every later launch on a device that has proven it.
     *
     * **R-1161 (register; AC-189, constitution IV) — this is a nag, and it was a deadlock.** As
     * originally written this returned `true` for as long as survival was unproven, on *every*
     * launch of this activity, and [route] turned that into a refusal to start capture. But
     * `hasProvenSurvival()`'s only admissible evidence is a recorded session of at least
     * `OVERNIGHT_SURVIVAL_THRESHOLD_MILLIS`, sessions are only ever created by
     * `RealCaptureService`, and the only thing that starts it is [startCaptureAndShowStatus] — the
     * branch the refusal never took. **The sole exit condition required the very thing the refusal
     * prevented**, and since `SetupActivity` and `ReaderActivity` are both `exported="false"`, an
     * operator who finished setup on a fresh install had no way out of the cycle but uninstalling.
     *
     * The fix is to separate the two things R-1104 had fused. **AC-189 asks only that the step
     * "reappears on every relevant subsequent launch" — it never says capture is blocked until
     * survival is proven** (`spec/functional-spec.md`), so the nag stays and the block goes:
     * - the detour is taken **at most once per process** ([OvernightNagState]), so the trip back
     *   from `SetupActivity` starts capture instead of bouncing;
     * - this function, and nothing else, **clears [SetupStore.overnightStepSeen]** when it decides
     *   to nag, which is what makes `SetupStateMachine.stepFor` resume on `SetupStep.OVERNIGHT` —
     *   AC-189's "reappears", now owned by the one caller that can know whether the operator has
     *   already been asked. `SetupActivity` used to do this on every entry, which re-armed the step
     *   the operator had just answered on every trip of the cycle (R-1161's other half).
     *
     * The proven branch is untouched: a real session, cleanly ended, at least
     * `OVERNIGHT_SURVIVAL_THRESHOLD_MILLIS` long, never the OS's own exemption flag — which
     * constitution IV records as lying on the reference device — and once proven it latches, so a
     * device that has proven survival is never routed through Setup again to re-ask (AC-189's own
     * "until").
     *
     * [DebugOvernightSurvivalOverride] is the same test seam [SetupActivity] uses, checked first so
     * a test can script this without a real `:data` database.
     */
    private fun overnightSurvivalStillUnproven(store: SetupStore): Boolean {
        if (store.overnightSurvivalProven) return false
        if (OvernightNagState.askedThisProcess) return false
        val checker = DebugOvernightSurvivalOverride.activeOverride
            ?: RealOvernightSurvivalChecker(OrtDatabase.create(applicationContext).sessionDao())
        if (runBlocking { checker.hasProvenSurvival() }) {
            store.overnightSurvivalProven = true
            return false
        }
        OvernightNagState.markAsked()
        store.overnightStepSeen = false
        return true
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
    /**
     * ui-conformance-plan WP9 round 3: passes through whatever [ReaderActivity.EXTRA_DESTINATION]
     * this activity's own launching intent carried, so a caller that reaches `MainActivity` with a
     * destination in mind does not lose it just because setup happened to already be complete.
     * **No live caller sends one today** — confirmed by reading `RealCaptureService`'s notification
     * `Open` action directly before writing this: its `PendingIntent` targets `ReaderActivity` by
     * explicit component name already (`R-102`'s own comment there), bypassing `MainActivity`
     * entirely, and carries only `EXTRA_SESSION_ID`, no destination. This is therefore forward
     * wiring for whenever that changes (or any other future caller of `MainActivity` itself), not a
     * fix to an observed bug — `:pipeline` is not this package's file to edit regardless.
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
        val readerIntent = Intent(this, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_SESSION_ID, effectiveSessionId)
        intent?.getStringExtra(ReaderActivity.EXTRA_DESTINATION)?.let { destination ->
            readerIntent.putExtra(ReaderActivity.EXTRA_DESTINATION, destination)
        }
        startActivity(readerIntent)
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
