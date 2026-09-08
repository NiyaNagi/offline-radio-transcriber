package org.ort.app

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.ort.app.permissions.PermissionsFlow
import org.ort.app.permissions.PermissionsState
import org.ort.app.ui.ReaderActivity
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtSystemBarStyle
import org.ort.app.ui.theme.OrtTheme
import org.ort.app.ui.theme.OrtType
import org.ort.core.Ulid
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.RealCaptureService

/**
 * The one-and-only permission gate ahead of the reader (build-plan P8; ui-conformance-plan R-002,
 * R-085). Not build-plan P8's promised full guided setup sequence (S01-S12, `Flow-Setup.dc.html`,
 * register row R-080) — that thirteen-board sequence is WP9's job, built on WP2's shared
 * components. The three private composables below cover only the two permissions this activity
 * itself gates on (RECORD_AUDIO, POST_NOTIFICATIONS); they are deliberately small, styled directly
 * from [OrtColors]/[OrtType]/[OrtSpacing] rather than a shared component library that does not
 * exist yet, and are written to be thrown away wholesale once WP9 lands the real sequence — see
 * each composable's own doc comment.
 */
public class MainActivity : ComponentActivity() {

    private var sessionId: String = ""
    private lateinit var prefs: SharedPreferences
    private var hasResumedBefore = false
    private var screen by mutableStateOf<SetupScreen?>(null)

    /** Test-only window into [screen] — `screen` itself stays `private` (Compose delegate) so
     * production code never reads it except through [setContent]'s own recomposition. */
    internal val currentScreenForTest: SetupScreen? get() = screen

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // R-008: see OrtSystemBarStyle's own doc comment — the no-arg enableEdgeToEdge() picks
        // light-on-dark only in night mode, which rendered dark-on-dark on emulator-5554.
        enableEdgeToEdge(statusBarStyle = OrtSystemBarStyle, navigationBarStyle = OrtSystemBarStyle)
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        sessionId = savedInstanceState?.getString(KEY_SESSION_ID) ?: Ulid.generate().value
        setContent {
            OrtTheme {
                when (screen) {
                    SetupScreen.REQUEST_MICROPHONE -> MicrophoneSetupScreen(onAllow = ::requestRecordAudio)
                    SetupScreen.MICROPHONE_DENIED -> MicrophoneDeniedScreen(
                        onOpenSettings = ::openAppSettings,
                        onCheckAgain = ::refreshScreen,
                    )
                    SetupScreen.REQUEST_NOTIFICATIONS -> NotificationsSetupScreen(
                        onAllow = ::requestNotifications,
                        onSkip = ::skipNotifications,
                    )
                    // Permitted: refreshScreen() below has already started capture and finish()ed.
                    null -> {}
                }
            }
        }
        refreshScreen()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SESSION_ID, sessionId)
    }

    /**
     * R-085: re-checks whenever this activity comes back to the foreground — the microphone-denied
     * screen names this explicitly ("this screen checks again on its own") because the only way
     * back from a permanent denial is the system settings screen, which never calls
     * [onRequestPermissionsResult]. Guarded by [hasResumedBefore] so the very first resume (which
     * immediately follows [onCreate]'s own [refreshScreen] call in the same lifecycle pass) does
     * not redundantly recompute the same state twice.
     */
    override fun onResume() {
        super.onResume()
        if (hasResumedBefore) refreshScreen() else hasResumedBefore = true
    }

    // ComponentActivity deprecates this callback in favour of the Activity Result API
    // (registerForActivityResult). Kept as-is here: it is the tested, working mechanism this
    // package inherited (PermissionsFlowTest, the on-device bug fix documented on refreshScreen())
    // and R-002/R-085 only change what is shown while a permission is unresolved, not how it is
    // requested — migrating to the newer API is a real change of its own, out of this package's
    // scope.
    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshScreen()
    }

    /**
     * Re-reads real permission state and picks the screen to show, or starts capture.
     *
     * **Bug found by on-device testing, not any test suite** (kept from the original v0 flow this
     * replaces): the first version of this method used [PermissionsFlow.nextStep], which sequences
     * through `BATTERY_EXEMPTION` *before* `DONE` — so capture never started until that step
     * completed, directly contradicting [PermissionsFlow.captureIsPermitted]'s own contract.
     * Fixed by checking [PermissionsFlow.captureIsPermitted] directly and treating the
     * battery-exemption request as fire-and-forget, asked but never awaited.
     *
     * Unlike that original flow, this no longer fires an OS permission dialog as a side effect of
     * merely computing which screen to show (R-002): [SetupScreen.REQUEST_MICROPHONE] and
     * [SetupScreen.REQUEST_NOTIFICATIONS] are reached without any dialog appearing, and only the
     * screen's own primary button ([requestRecordAudio], [requestNotifications]) asks the OS.
     */
    private fun refreshScreen() {
        val state = currentPermissionsState()
        val next = setupScreenFor(state, micPermanentlyDenied())
        screen = next
        if (next == null) {
            check(PermissionsFlow.captureIsPermitted(state)) { "mic and notifications resolved but not permitted" }
            requestBatteryExemptionBestEffort(state)
            startCaptureAndShowStatus()
        }
    }

    private fun requestRecordAudio() {
        prefs.edit().putBoolean(KEY_MIC_REQUESTED, true).apply()
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE)
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE)
        } else {
            refreshScreen() // nothing to request pre-33; currentPermissionsState() already reports it granted
        }
    }

    /**
     * R-002: notifications never gate capture ([PermissionsFlow.captureIsPermitted] is unchanged
     * and still requires both booleans true — see that object's own doc comment) — this persists
     * that the operator chose to skip, which [currentPermissionsState] then folds into
     * `notificationsGranted` alongside the real OS grant. The permission itself stays ungranted;
     * only the setup screen stops asking.
     */
    private fun skipNotifications() {
        prefs.edit().putBoolean(KEY_NOTIFICATIONS_SKIPPED, true).apply()
        refreshScreen()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        startActivity(intent)
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
        startActivity(
            Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_SESSION_ID, effectiveSessionId),
        )
        finish()
    }

    private fun currentPermissionsState(): PermissionsState {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return PermissionsState(
            recordAudioGranted = granted(Manifest.permission.RECORD_AUDIO),
            notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                granted(Manifest.permission.POST_NOTIFICATIONS) ||
                prefs.getBoolean(KEY_NOTIFICATIONS_SKIPPED, false),
            isIgnoringBatteryOptimizationsDiagnosticOnly = pm.isIgnoringBatteryOptimizations(packageName),
        )
    }

    /**
     * R-085: `shouldShowRequestPermissionRationale` alone cannot distinguish "never asked yet"
     * from "denied twice, permanently" — both return `false`. [KEY_MIC_REQUESTED] (set the moment
     * [requestRecordAudio] fires the real request) is what tells the two apart, the same bookkeeping
     * pattern the platform docs themselves describe for this exact ambiguity.
     */
    private fun micPermanentlyDenied(): Boolean {
        if (granted(Manifest.permission.RECORD_AUDIO)) return false
        val canShowRationale =
            ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)
        val requestedBefore = prefs.getBoolean(KEY_MIC_REQUESTED, false)
        return requestedBefore && !canShowRationale
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    internal companion object {
        const val TAG = "MainActivity"
        const val REQUEST_CODE = 1001
        const val KEY_SESSION_ID = "session_id"

        /** `internal`, not `private`: tests pre-populate this exact `SharedPreferences` (same name,
         * same [android.content.Context]) to simulate a permission having been requested before, or
         * notifications having been skipped in an earlier launch — see `MainActivityTest`. */
        internal const val PREFS_NAME = "org.ort.app.setup"
        internal const val KEY_MIC_REQUESTED = "mic_requested"
        internal const val KEY_NOTIFICATIONS_SKIPPED = "notifications_skipped"
    }
}

/** The one screen [MainActivity] can be showing while a permission is unresolved. `internal`, not
 * `private`, so [setupScreenFor]'s decision logic is unit-testable on its own (`SetupScreenSelectionTest`),
 * the same split [PermissionsFlow] already establishes between pure decision logic and Android glue. */
internal enum class SetupScreen {
    REQUEST_MICROPHONE,
    MICROPHONE_DENIED,
    REQUEST_NOTIFICATIONS,
}

/**
 * The pure part of [MainActivity.refreshScreen]'s decision: which screen to show given the current
 * [PermissionsState] and whether the microphone has been permanently denied (`null` means every
 * gate has cleared and capture may start). Kept free of `Context`/`Activity` so R-002 and R-085 are
 * each one fast JVM assertion (`SetupScreenSelectionTest`) rather than a Robolectric round-trip.
 */
internal fun setupScreenFor(state: PermissionsState, micPermanentlyDenied: Boolean): SetupScreen? = when {
    !state.recordAudioGranted && micPermanentlyDenied -> SetupScreen.MICROPHONE_DENIED
    !state.recordAudioGranted -> SetupScreen.REQUEST_MICROPHONE
    !state.notificationsGranted -> SetupScreen.REQUEST_NOTIFICATIONS
    else -> null
}

private const val SETUP_TOTAL_STEPS = 7

/**
 * R-002 — `Setup-Mic.dc.html` (S02 "1 of 7"). Step header, title, subtitle, the three-point
 * rationale card and the primary "Allow microphone" action. WP9 replaces this wholesale with the
 * full guided sequence (S01-S12) on WP2's shared components (register row R-080) — this exists
 * only so the two permissions [MainActivity] itself gates on are never shown as a bare OS prompt
 * on the platform ground (R-002).
 */
@Composable
internal fun MicrophoneSetupScreen(onAllow: () -> Unit) {
    SetupScaffold(
        stepIndex = 1,
        title = stringResourceCompat(R.string.setup_mic_title),
        subtitle = stringResourceCompat(R.string.setup_mic_subtitle),
        primary = SetupAction(stringResourceCompat(R.string.setup_mic_allow), onAllow),
    ) {
        Text(
            text = stringResourceCompat(R.string.setup_mic_body),
            style = OrtType.bodyProse,
            color = OrtColors.textSecondary,
        )
        RationaleCard(
            points = listOf(
                stringResourceCompat(R.string.setup_mic_point_1),
                stringResourceCompat(R.string.setup_mic_point_2),
                stringResourceCompat(R.string.setup_mic_point_3),
            ),
        )
    }
}

/**
 * R-085 — `Setup-Mic-Denied.dc.html` (S02b). The halt banner ("Android will not ask again"), the
 * three settings steps, `Open app settings` and the `Check again` text action — the path back from
 * a permanent denial that the old plain-`TextView` flow never offered at all (it stayed on
 * "Requesting microphone access…" forever). See [MainActivity.onResume] for the automatic re-check.
 */
@Composable
internal fun MicrophoneDeniedScreen(onOpenSettings: () -> Unit, onCheckAgain: () -> Unit) {
    SetupScaffold(
        stepIndex = 1,
        halted = true,
        title = stringResourceCompat(R.string.setup_mic_denied_title),
        subtitle = stringResourceCompat(R.string.setup_mic_denied_subtitle),
        primary = SetupAction(stringResourceCompat(R.string.setup_mic_denied_open_settings), onOpenSettings),
        secondary = SetupAction(stringResourceCompat(R.string.setup_mic_denied_check_again), onCheckAgain),
    ) {
        HaltBanner(
            title = stringResourceCompat(R.string.setup_mic_denied_banner_title),
            body = stringResourceCompat(R.string.setup_mic_denied_banner_body),
        )
        Column {
            Text(
                text = stringResourceCompat(R.string.setup_mic_denied_steps_label).uppercase(),
                style = OrtType.sectionLabel,
                color = OrtColors.textFaint,
            )
            Spacer(modifier = Modifier.height(OrtSpacing.xs))
            listOf(
                stringResourceCompat(R.string.setup_mic_denied_step_1),
                stringResourceCompat(R.string.setup_mic_denied_step_2),
                stringResourceCompat(R.string.setup_mic_denied_step_3),
            ).forEachIndexed { index, step ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = OrtSpacing.sm),
                ) {
                    Text(
                        text = "${index + 1}",
                        style = OrtType.signal,
                        color = OrtColors.textLow,
                        modifier = Modifier.width(22.dp),
                    )
                    Text(text = step, style = OrtType.control, color = OrtColors.textBody)
                }
            }
        }
        Text(
            text = stringResourceCompat(R.string.setup_mic_denied_footer),
            style = OrtType.cardBody,
            color = OrtColors.textDim,
        )
    }
}

/** R-002 — `Setup-Notify.dc.html` (S03 "2 of 7"): the notification preview card, `Allow
 * notifications`, and `Skip` (which proceeds — notifications never gate capture, see
 * [MainActivity.skipNotifications]). */
@Composable
internal fun NotificationsSetupScreen(onAllow: () -> Unit, onSkip: () -> Unit) {
    SetupScaffold(
        stepIndex = 2,
        title = stringResourceCompat(R.string.setup_notify_title),
        subtitle = stringResourceCompat(R.string.setup_notify_subtitle),
        primary = SetupAction(stringResourceCompat(R.string.setup_notify_allow), onAllow),
        secondary = SetupAction(stringResourceCompat(R.string.setup_notify_skip), onSkip),
    ) {
        Text(
            text = stringResourceCompat(R.string.setup_notify_body),
            style = OrtType.bodyProse,
            color = OrtColors.textSecondary,
        )
        NotificationPreviewCard()
        Text(
            text = stringResourceCompat(R.string.setup_notify_footer),
            style = OrtType.cardBody,
            color = OrtColors.textDim,
        )
    }
}

/**
 * The shared shell every setup screen above uses: the 44dp status-bar inset ([WindowInsets.statusBars],
 * never a hardcoded dp — guide §5), the step-segment indicator + `n of 7` counter, the screen
 * title/subtitle, the screen's own body content, and a bottom action column (a 48dp primary button,
 * an optional 44dp text action beneath it).
 */
/** A label paired with its click handler — bundles [SetupScaffold]'s primary/secondary actions so
 * the composable itself stays under detekt's `LongParameterList` threshold (9). */
private data class SetupAction(val label: String, val onClick: () -> Unit)

@Composable
private fun SetupScaffold(
    stepIndex: Int,
    title: String,
    subtitle: String,
    primary: SetupAction,
    halted: Boolean = false,
    secondary: SetupAction? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Decorative: MainActivity's flow has no earlier setup step to return to yet — a real
            // back destination arrives with WP9's full sequence (S01 Welcome precedes this step).
            Text(text = "‹", style = OrtType.figure, color = OrtColors.textIcon)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = stringResourceCompat(R.string.setup_step_counter, stepIndex, SETUP_TOTAL_STEPS),
                style = OrtType.signal,
                color = OrtColors.textDim,
            )
        }
        StepSegments(stepIndex = stepIndex, totalSteps = SETUP_TOTAL_STEPS, halted = halted)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.md)) {
            Text(text = title, style = OrtType.screenTitle, color = OrtColors.textHigh)
            Text(text = subtitle, style = OrtType.subtitle, color = OrtColors.textDim)
        }
        Column(
            modifier = Modifier
                .padding(horizontal = OrtSpacing.lg)
                .weight(1f),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(OrtSpacing.md),
        ) {
            content()
        }
        Column(
            modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.md),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(OrtSpacing.sm),
        ) {
            PrimaryButton(label = primary.label, onClick = primary.onClick)
            if (secondary != null) {
                TextActionButton(label = secondary.label, onClick = secondary.onClick)
            }
        }
    }
}

/** Guide §6.10: a row of equal segments, `accent/green` for done/current, `line/default` for the
 * rest, `halt/text` for a halted step. */
@Composable
private fun StepSegments(stepIndex: Int, totalSteps: Int, halted: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OrtSpacing.lg)
            .semantics { contentDescription = "Setup step $stepIndex of $totalSteps" },
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
    ) {
        repeat(totalSteps) { index ->
            val done = index < stepIndex
            val color = when {
                done && halted && index == stepIndex - 1 -> OrtColors.haltText
                done -> OrtColors.accentGreen
                else -> OrtColors.lineDefault
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
    }
}

/** Guide §6.7: a real filled button for a primary setup action — 48dp tall, `accent/green` fill,
 * `accent/on-green` text, 8dp radius. */
@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(OrtColors.accentGreen)
            .clickable(onClickLabel = label) { onClick() }
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = OrtType.bodyProse.copy(fontWeight = FontWeight.Medium),
            color = OrtColors.accentOnGreen,
        )
    }
}

/** Guide §6.7: a text action still gets a real 44dp hit area and a pressed affordance, never bare
 * clickable text with the text's own height as the target. */
@Composable
private fun TextActionButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(onClickLabel = label) { onClick() }
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = OrtType.textAction, color = OrtColors.accentGreen)
    }
}

/** `Setup-Mic.dc.html`'s three-point rationale card: `bg/card`, 10dp radius, a green dot per point. */
@Composable
private fun RationaleCard(points: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(OrtColors.bgCard)
            .padding(OrtSpacing.md + OrtSpacing.xs),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(OrtSpacing.sm),
    ) {
        points.forEach { point ->
            Row {
                Box(
                    modifier = Modifier
                        .padding(top = 5.dp, end = 11.dp)
                        .width(9.dp)
                        .height(9.dp)
                        .clip(CircleShape)
                        .background(OrtColors.accentGreen),
                )
                Text(text = point, style = OrtType.subtitle, color = OrtColors.textBody)
            }
        }
    }
}

/** `Setup-Mic-Denied.dc.html`'s halting banner (guide §3's halt colour family, the one case a WP1
 * screen ever renders red): capture is not the thing halted here, but setup cannot proceed without
 * this permission, and the guide's halt family is what the board itself uses for that state. */
@Composable
private fun HaltBanner(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(OrtColors.haltBg)
            .padding(OrtSpacing.md + 1.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$title. $body" },
    ) {
        Text(text = title, style = OrtType.control.copy(fontWeight = FontWeight.Medium), color = OrtColors.textHigh)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = body, style = OrtType.cardBody, color = OrtColors.textSecondary)
    }
}

/** `Setup-Notify.dc.html`'s illustrative notification preview — static, not the real notification
 * (`RealCaptureService` owns that; verified separately against N05, register row R-102). */
@Composable
private fun NotificationPreviewCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(OrtColors.bgPage)
            .padding(OrtSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResourceCompat(R.string.setup_notify_preview_app),
                style = OrtType.subLine,
                color = OrtColors.textDim,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResourceCompat(R.string.setup_notify_preview_now),
                style = OrtType.signal,
                color = OrtColors.textLow,
            )
        }
        Spacer(modifier = Modifier.height(7.dp))
        Text(
            text = stringResourceCompat(R.string.setup_notify_preview_title),
            style = OrtType.subtitle.copy(fontWeight = FontWeight.Medium),
            color = OrtColors.textHigh,
        )
        Text(
            text = stringResourceCompat(R.string.setup_notify_preview_body),
            style = OrtType.cardBody,
            color = OrtColors.textMuted,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(18.dp)) {
            Text(
                text = stringResourceCompat(R.string.setup_notify_preview_open),
                style = OrtType.subtitle.copy(fontWeight = FontWeight.Medium),
                color = OrtColors.accentGreen,
            )
            Text(
                text = stringResourceCompat(R.string.setup_notify_preview_stop),
                style = OrtType.subtitle.copy(fontWeight = FontWeight.Medium),
                color = OrtColors.accentGreen,
            )
        }
    }
}

@Composable
private fun stringResourceCompat(id: Int): String = androidx.compose.ui.res.stringResource(id)

@Composable
private fun stringResourceCompat(id: Int, vararg args: Any): String = androidx.compose.ui.res.stringResource(id, *args)
