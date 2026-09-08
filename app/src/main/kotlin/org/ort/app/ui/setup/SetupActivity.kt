package org.ort.app.ui.setup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.collect
import org.ort.app.MainActivity
import org.ort.app.permissions.PermissionsState
import org.ort.app.ui.theme.OrtSystemBarStyle
import org.ort.app.ui.theme.OrtTheme
import org.ort.capture.android.AndroidAudioIo
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.pipeline.capture.AsrAvailability
import org.ort.pipeline.capture.RigStatus

/**
 * The guided setup sequence (build-plan P8; ui-conformance-plan WP9, register R-080..R-084,
 * `Flow-Setup.dc.html`) — S01 through S12. `MainActivity` routes here whenever
 * [SetupStateMachine.isComplete] is false and hands back to it once `Start capture` is tapped
 * ([onStartCapture]), so the "start capture + launch the reader" logic stays exactly where
 * `MainActivity`'s own doc comment says it does (register R-080's router split).
 *
 * Every screen composable this class dispatches to stays a pure function of a view-state (guide's
 * own rule) — all `Context`/coroutine/IO work lives here, in the one place allowed to touch it.
 */
public class SetupActivity : ComponentActivity() {

    private lateinit var store: SetupStore
    private lateinit var audioIo: AndroidAudioIo

    private var step by mutableStateOf<SetupStep?>(null)
    private val backStack = ArrayDeque<SetupStep>()

    private var inputRoutes by mutableStateOf<List<InputRouteOption>>(emptyList())
    private var selectedInputId by mutableStateOf<String?>(null)
    private var selectedInputLabel by mutableStateOf<String?>(null)

    private var verifyState by mutableStateOf<RouteCheckState?>(null)
    private var verifyRunToken by mutableStateOf(0)

    private var levelState by mutableStateOf<LevelCheckState?>(null)
    private var levelRunToken by mutableStateOf(0)

    private var rigStatusSnapshot by mutableStateOf(RigStatus.state)

    /** Test-only window into [step] — see `MainActivity.currentScreenForTest`'s identical pattern. */
    internal val currentStepForTest: SetupStep? get() = step

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = OrtSystemBarStyle, navigationBarStyle = OrtSystemBarStyle)
        store = SharedPreferencesSetupStore(getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, MODE_PRIVATE))
        audioIo = AndroidAudioIo(this)
        selectedInputId = store.selectedInputId
        selectedInputLabel = store.selectedInputLabel

        setContent {
            OrtTheme { RenderStep() }
        }
        refreshStep()
    }

    override fun onResume() {
        super.onResume()
        // R-085's pattern, carried over: the only way back from a permanent mic denial is the
        // system settings screen, which never calls onRequestPermissionsResult -- re-check here.
        if (step == SetupStep.MICROPHONE_DENIED || step == null) refreshStep()
        rigStatusSnapshot = RigStatus.state
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStep(pushCurrent = true)
    }

    // --- Step resolution --------------------------------------------------------------------

    /**
     * [pushCurrent] distinguishes a genuine forward action (Begin, a permission just resolved,
     * Skip, Not now, ...) — which leaves the step being left behind reachable via [onBack] — from
     * a cold resume (`onCreate`/`onResume` with no user action), which must never fabricate a
     * back-stack entry the operator did not actually navigate through.
     */
    private fun refreshStep(pushCurrent: Boolean = false) {
        val permissions = currentPermissionsState()
        val next = SetupStateMachine.stepFor(permissions, micPermanentlyDenied(), store.snapshot())
        if (next == null) {
            store.setupComplete = true
            handBackToMainActivity()
            return
        }
        if (pushCurrent) step?.let { if (it != next) backStack.addLast(it) }
        step = next
        if (next == SetupStep.INPUT && inputRoutes.isEmpty()) refreshInputRoutes()
    }

    private fun handBackToMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun navigateForward(next: SetupStep) {
        step?.let { backStack.addLast(it) }
        step = next
    }

    internal fun onBack() {
        val previous = backStack.removeLastOrNull() ?: return
        step = previous
    }

    // --- S01 Welcome --------------------------------------------------------------------------

    internal fun onBegin() {
        store.welcomeSeen = true
        refreshStep(pushCurrent = true)
    }

    // --- S02/S02b Microphone --------------------------------------------------------------------

    internal fun requestRecordAudio() {
        store.micRequested = true
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE)
    }

    internal fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    internal fun checkMicAgain() {
        refreshStep()
    }

    // --- S03 Notifications ------------------------------------------------------------------

    internal fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE)
        } else {
            refreshStep()
        }
    }

    internal fun skipNotifications() {
        store.notificationsSkipped = true
        refreshStep(pushCurrent = true)
    }

    // --- S04 Input ------------------------------------------------------------------------------

    internal fun refreshInputRoutes() {
        inputRoutes = InputRouteEnumerator(this, audioIo).list()
    }

    internal fun onSelectInput(id: String) {
        selectedInputId = id
        selectedInputLabel = inputRoutes.firstOrNull { it.id == id }?.label
    }

    internal fun onStartVerify() {
        val id = selectedInputId ?: return
        store.selectedInputId = id
        store.selectedInputLabel = selectedInputLabel
        store.clearInputVerification()
        verifyState = null
        verifyRunToken += 1
        navigateForward(SetupStep.VERIFY)
    }

    private fun selectedDescriptor(): AudioDeviceDescriptor? =
        audioIo.availableDevices().firstOrNull { it.id == selectedInputId }

    // --- S05/S06 Verify / Route mismatch ---------------------------------------------------

    internal fun onVerifyStateChanged(new: RouteCheckState) {
        verifyState = new
        if (new is RouteCheckState.Mismatch) {
            navigateForward(SetupStep.ROUTE_MISMATCH)
        } else if (new is RouteCheckState.Passed) {
            store.inputVerified = true
            store.verifiedNativeRateHz = new.nativeRateHz
            store.verifiedResamplerIdentity = new.resamplerDescription
        }
    }

    internal fun onVerifyContinue() {
        navigateForward(SetupStep.LEVEL)
        levelState = null
        levelRunToken += 1
    }

    internal fun onChooseAnotherInput() {
        store.clearInputVerification()
        step = SetupStep.INPUT
    }

    internal fun onTryVerifyAgain() {
        verifyState = null
        verifyRunToken += 1
        step = SetupStep.VERIFY
    }

    // --- S07 Level ------------------------------------------------------------------------------

    internal fun onLevelStateChanged(new: LevelCheckState) {
        levelState = new
        if (new is LevelCheckState.Reading) {
            store.levelPeakDbfs = new.level.peakDbfs
            store.levelInBand = new.level.band == LevelBand.IN_BAND
        }
    }

    internal fun onLevelContinue() {
        navigateForward(SetupStep.OVERNIGHT)
    }

    // --- S08 Overnight ----------------------------------------------------------------------

    internal fun onOpenBatterySetting() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            startActivity(intent)
        } catch (e: SecurityException) {
            android.util.Log.w(TAG, "battery-exemption request denied", e)
        } catch (e: android.content.ActivityNotFoundException) {
            android.util.Log.w(TAG, "no handler for battery-exemption request", e)
        }
        store.overnightStepSeen = true
        refreshStep(pushCurrent = true)
    }

    internal fun onSkipOvernight() {
        store.overnightStepSeen = true
        refreshStep(pushCurrent = true)
    }

    // --- S09-S11 Radio --------------------------------------------------------------------------

    internal fun onChooseRadio(choice: RadioChoice) {
        store.radioChoice = choice
        when (choice) {
            RadioChoice.NONE -> refreshStep(pushCurrent = true)
            RadioChoice.TH_D75A, RadioChoice.OTHER_CAT_RIG -> {
                rigStatusSnapshot = RigStatus.state
                val next = if (rigStatusSnapshot is RigStatus.State.Absent) {
                    SetupStep.RADIO_USB
                } else {
                    SetupStep.RADIO_VERIFIED
                }
                navigateForward(next)
            }
        }
    }

    internal fun onRadioNotNow() {
        store.radioChoice = RadioChoice.NONE
        refreshStep(pushCurrent = true)
    }

    internal fun onRadioVerifiedContinue() {
        refreshStep(pushCurrent = true)
    }

    internal fun onChangeRadio() {
        store.radioChoice = null
        step = SetupStep.RADIO
    }

    // --- S12 Ready ------------------------------------------------------------------------------

    internal fun onStartCapture() {
        store.setupComplete = true
        handBackToMainActivity()
    }

    private fun batteryExempt(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    // --- shared -----------------------------------------------------------------------------

    private fun currentPermissionsState(): PermissionsState {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return PermissionsState(
            recordAudioGranted = granted(Manifest.permission.RECORD_AUDIO),
            notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                granted(Manifest.permission.POST_NOTIFICATIONS) ||
                store.notificationsSkipped,
            isIgnoringBatteryOptimizationsDiagnosticOnly = pm.isIgnoringBatteryOptimizations(packageName),
        )
    }

    private fun micPermanentlyDenied(): Boolean {
        if (granted(Manifest.permission.RECORD_AUDIO)) return false
        val canShowRationale =
            ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)
        return store.micRequested && !canShowRationale
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** The one big `when` dispatching [step] to its screen composable. A member function (not a
     * top-level one) so it can read this activity's own state directly, the same way
     * `MainActivity`'s `setContent` block did before WP9. Each multi-line branch is its own small
     * private composable below, both to stay under detekt's complexity threshold and because each
     * one (VERIFY, LEVEL especially) has a real reason to be read on its own. */
    @Composable
    private fun RenderStep() {
        when (step) {
            SetupStep.WELCOME -> WelcomeScreen(onBegin = ::onBegin)
            SetupStep.MICROPHONE -> MicrophoneScreen(onAllow = ::requestRecordAudio, onBack = ::onBack)
            SetupStep.MICROPHONE_DENIED ->
                MicrophoneDeniedScreen(onOpenSettings = ::openAppSettings, onCheckAgain = ::checkMicAgain)
            SetupStep.NOTIFICATIONS ->
                NotificationsScreen(onAllow = ::requestNotifications, onSkip = ::skipNotifications, onBack = ::onBack)
            SetupStep.INPUT -> RenderInput()
            SetupStep.VERIFY -> RenderVerify()
            SetupStep.ROUTE_MISMATCH -> RenderRouteMismatch()
            SetupStep.LEVEL -> RenderLevel()
            SetupStep.OVERNIGHT -> OvernightScreen(onOpenSetting = ::onOpenBatterySetting, onSkip = ::onSkipOvernight)
            SetupStep.RADIO -> RadioScreen(onChoose = ::onChooseRadio, onNotNow = ::onRadioNotNow)
            SetupStep.RADIO_USB -> RadioUsbScreen(
                rigStatus = rigStatusSnapshot,
                onBack = ::onBack,
                onEnterFrequencyInstead = ::onRadioNotNow,
            )
            SetupStep.RADIO_VERIFIED -> RenderRadioVerified()
            SetupStep.READY -> RenderReady()
            null -> {}
        }
    }

    @Composable
    private fun RenderInput() {
        InputScreen(
            state = InputViewState(routes = inputRoutes, selectedId = selectedInputId),
            onSelect = ::onSelectInput,
            onRefresh = ::refreshInputRoutes,
            onVerify = ::onStartVerify,
            onBack = ::onBack,
        )
    }

    @Composable
    private fun RenderVerify() {
        val selection = selectedDescriptor()
        if (selection != null) {
            LaunchedEffect(verifyRunToken) {
                RealRouteCheck().run(audioIo, selection).collect { onVerifyStateChanged(it) }
            }
        }
        VerifyScreen(
            state = VerifyViewState(inputLabel = selectedInputLabel ?: "", check = verifyState),
            onContinue = ::onVerifyContinue,
        )
    }

    @Composable
    private fun RenderRouteMismatch() {
        val mismatch = verifyState as? RouteCheckState.Mismatch ?: return
        RouteMismatchScreen(
            mismatch = mismatch,
            onChooseAnotherInput = ::onChooseAnotherInput,
            onTryAgain = ::onTryVerifyAgain,
        )
    }

    @Composable
    private fun RenderLevel() {
        val selection = selectedDescriptor()
        if (selection != null) {
            LaunchedEffect(levelRunToken) {
                RealLevelCheck().run(audioIo, selection).collect { onLevelStateChanged(it) }
            }
        }
        LevelScreen(state = levelState, onContinue = ::onLevelContinue)
    }

    @Composable
    private fun RenderRadioVerified() {
        val connected = rigStatusSnapshot as? RigStatus.State.Connected ?: return
        RadioVerifiedScreen(
            connected = connected,
            onContinue = ::onRadioVerifiedContinue,
            onChangeRadio = ::onChangeRadio,
        )
    }

    @Composable
    private fun RenderReady() {
        val actions = ReadyActions(
            onFixInput = { step = SetupStep.INPUT },
            onFixLevel = { step = SetupStep.LEVEL },
            onFixOvernight = { step = SetupStep.OVERNIGHT },
            onFixRadio = { step = SetupStep.RADIO },
            // No destination exists from setup for this today -- see this package's report
            // ("Install" has nowhere to navigate to before capture starts).
            onInstallModel = {},
        )
        val rows = readyRowsFor(store, batteryExempt(), rigStatusSnapshot, AsrAvailability.state, actions)
        ReadyScreen(state = ReadyViewState(rows), onStartCapture = ::onStartCapture)
    }

    internal companion object {
        const val TAG = "SetupActivity"
        const val REQUEST_CODE = 1002
    }
}
