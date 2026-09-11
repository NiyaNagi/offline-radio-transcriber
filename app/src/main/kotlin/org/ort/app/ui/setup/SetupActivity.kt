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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import org.ort.app.BuildConfig
import org.ort.app.MainActivity
import org.ort.app.permissions.PermissionsState
import org.ort.app.ui.ReaderActivity
import org.ort.app.ui.data.ModelsController
import org.ort.app.ui.navigation.ReaderDestination
import org.ort.app.ui.theme.OrtSystemBarStyle
import org.ort.app.ui.theme.OrtTheme
import org.ort.capture.android.AndroidAudioIo
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.core.capture.CaptureMode
import org.ort.core.capture.CaptureModePresets
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.rig.CaptureConfigurationStore
import org.ort.pipeline.rig.DefaultRigLinkBridge
import org.ort.pipeline.rig.SharedPreferencesCaptureConfigurationStore
import org.ort.rig.NullRigModule
import org.ort.rig.RigTransportKind
import org.ort.rig.catalogue.RigCatalogue
import org.ort.rig.catalogue.RigCatalogueEntry
import org.ort.rig.descriptor.RigDescriptor
import org.ort.core.capture.RigTransportKind as PresetRigTransportKind

/**
 * The guided setup sequence (build-plan P8; ui-conformance-plan WP9, register R-080..R-084,
 * `Flow-Setup.dc.html`) — S01 through S12. `MainActivity` routes here whenever
 * [SetupStateMachine.isComplete] is false and hands back to it once `Start capture` is tapped
 * ([onStartCapture]), so the "start capture + launch the reader" logic stays exactly where
 * `MainActivity`'s own doc comment says it does (register R-080's router split).
 *
 * [EXTRA_STEP] (ui-conformance-plan WP9, round 3): the name of a [SetupStep] to open on directly,
 * read once in [onCreate] ([tryOpenAtRequestedStep]) — WP3's
 * `ReaderNavigator.openSetupInput()` names `INPUT` for F1's "Choose another input" and
 * `Settings-Capture`'s "Re-verify", both fired *during a running session*, when
 * [SetupStateMachine.isComplete] is already true and the ordinary [refreshStep] would hand
 * straight back to `MainActivity` without ever showing anything (exactly the gap that function's
 * own doc comment reported before this extra existed). Honored only "when the store allows it": a
 * requested step is opened only if every gate *before* it in [SetupStep]'s own declaration order
 * is already satisfied — i.e. it is at or before [SetupStateMachine.stepFor]'s own natural resume
 * point (or that point is `null`, meaning every gate has cleared). A request to skip ahead of an
 * unmet gate (e.g. `RADIO` while the microphone is still unresolved) is silently ignored in favour
 * of the ordinary resume flow — this extra can only offer a *reconfiguration* entry point into an
 * already-valid sequence, never a way around a verification the guide requires. Checked once, at
 * cold start, not on every [refreshStep] call: after the initial jump, normal forward/back
 * navigation and any later `onResume` re-check behave exactly as they did before this extra
 * existed.
 *
 * [EXTRA_FONT_SCALE] (ui-conformance-plan WP9, screenshot-tour seam for WP12): the screenshot tour
 * (package `org.ort.app.debug.tour`, under this app module's own `debug` source set) captures Setup
 * steps by launching this activity with [EXTRA_STEP] directly — but reaching font scale 2.0 the same
 * way would mean changing the *system* `font_scale` setting, which needs an activity relaunch to
 * take effect and starves the one emulator the tour and a validator's own manual walk both depend
 * on. Read once in [onCreate], the same way [EXTRA_STEP] is: when the intent carries this extra,
 * [LocalDensity] is overridden for the whole Setup composition with a [Density] at the *same* pixel
 * density the platform already reports ([LocalDensity.current] itself, read before the override —
 * never a hardcoded `1f`, which would be wrong on any real device) and the requested `fontScale`, so
 * every Setup screen renders at that scale in-process, no system setting touched and no relaunch
 * needed. Absence of the extra changes nothing at all — the composition is never wrapped, so an
 * ordinary launch still reads the real platform [LocalDensity] exactly as before this existed;
 * debug/tooling-only in effect (the tour is itself part of the `debug` source set, never compiled
 * into a release build), and harmless there regardless, since no launcher sets it.
 *
 * Every screen composable this class dispatches to stays a pure function of a view-state (guide's
 * own rule) — all `Context`/coroutine/IO work lives here, in the one place allowed to touch it.
 * `LargeClass` suppressed for the same reason [SetupStore]'s own `TooManyFunctions` suppression
 * is: this is deliberately the *one* place every guided-setup `Context`/coroutine/IO concern lives
 * (D33/P19 added four more screens' worth on top of the pre-existing eight), not a class that
 * grew by accident — splitting it would scatter that single-responsibility boundary across
 * several files with no natural seam between them, per this file's own class doc comment above.
 */
@Suppress("LargeClass")
public class SetupActivity : ComponentActivity() {

    private lateinit var store: SetupStore
    private lateinit var audioIo: AndroidAudioIo

    /** WPC2's contract (`:pipeline`) — what `RealCaptureService` actually reads at session start
     * (FR-CAP-12/13, AC-131). [syncCaptureConfiguration] pushes [store]'s current facts through
     * this on every mutation to the mode/route/rig axes; [SetupCaptureConfigurationAdapter] is the
     * pure translation. */
    private lateinit var captureConfigStore: CaptureConfigurationStore

    private val stepState = mutableStateOf<SetupStep?>(null)

    /**
     * R-802 (register, tour run 2): `refreshPairedDevices()` used to run only from
     * [onSelectRigTransport]'s forward path and the explicit `Refresh` action, so an operator
     * whose process died mid-S10b (or anyone the tour/debug seam cold-opens straight onto this
     * step via [EXTRA_STEP]) saw an empty paired-device list until they tapped `Refresh` — a real
     * defect, not just a tour gap, since [SetupStateMachine]'s `RIG_BLUETOOTH` gate is resumable by
     * design. Routing every assignment through this property (not just [navigateForward]'s) means
     * [refreshPairedDevices] runs the moment the step *becomes* `RIG_BLUETOOTH`, on every path that
     * can produce that value: [refreshStep]'s cold/gate resume, [tryOpenAtRequestedStep]'s debug
     * entry, [navigateForward]'s forward action, and [onBack]. [onSelectRigTransport]'s own explicit
     * call is redundant with this but harmless (a second, idempotent [RigLinkPort.pairedDevices]
     * read) — left as-is rather than special-cased apart from the rest.
     */
    private var step: SetupStep?
        get() = stepState.value
        set(value) {
            stepState.value = value
            if (value == SetupStep.RIG_BLUETOOTH) refreshPairedDevices()
        }
    private val backStack = ArrayDeque<SetupStep>()

    private var inputRoutes by mutableStateOf<List<InputRouteOption>>(emptyList())
    private var selectedInputId by mutableStateOf<String?>(null)
    private var selectedInputLabel by mutableStateOf<String?>(null)

    private var verifyState by mutableStateOf<RouteCheckState?>(null)
    private var verifyRunToken by mutableStateOf(0)

    private var levelState by mutableStateOf<LevelCheckState?>(null)
    private var levelRunToken by mutableStateOf(0)

    private var rigStatusSnapshot by mutableStateOf(RigStatus.state)

    /** Set just before [RenderRadioVerified] routes an unexpected [RigStatus.State.Absent] back to
     * S09 — see that function's doc comment. Cleared the moment the operator acts on S09 again
     * ([onChooseRig]/[onRadioNotNow]/[onChangeRadio]), never left stale on a later, unrelated
     * visit to S09. */
    private var radioAbsentBanner by mutableStateOf<String?>(null)

    // --- D33/P19 (WPD): the rig catalogue, S09b/S10b state ---------------------------------------

    /** Descriptors accepted this session via S09's `Import it` (FR-RIG-19) — kept in-memory only;
     * persisting an imported descriptor across process death is `:rig`'s own asset-lifecycle
     * concern (FR-AST-7), not this screen's. */
    private var importedRigDescriptors by mutableStateOf<Set<RigDescriptor>>(emptySet())
    private var radioImportError by mutableStateOf<String?>(null)
    private var selectedRigTransportKind by mutableStateOf<RigTransportKind?>(null)
    private var rigBluetoothDevices by mutableStateOf<List<PairedDevice>>(emptyList())
    private var rigBluetoothSelectedAddress by mutableStateOf<String?>(null)
    private var rigLinkState by mutableStateOf<RigLinkState?>(null)
    private var rigLinkRunToken by mutableStateOf(0)

    /** R-903 (reviewer A2, run 3, design): the moment [onSelectRigBluetoothDevice] started this
     * attempt — S11's "identified and verified in N.N s" clause is measured from here to the
     * instant [rigLinkState] first reaches [RigLinkState.Verified] ([onRigLinkStateChanged]), never
     * fabricated. [org.ort.core.Clock.monotonicNanos], never [org.ort.core.Clock.wallMillis] — that
     * interface's own doc comment is explicit that wall time is for display/storage only and can
     * jump, so it is never honest for measuring a duration. `null` whenever no attempt is in flight
     * (reset on every fresh device selection so a later attempt's timing never inherits an earlier
     * one's start). [clock] is a settable seam (mirrors [isDebugBuild]'s own shape) purely for test
     * determinism. */
    private var rigLinkVerifyStartedAtNanos: Long? = null
    private var rigLinkVerifiedDurationSeconds by mutableStateOf<Double?>(null)
    internal var clock: org.ort.core.Clock = org.ort.core.SystemClock

    /** WPD's own `:app`-local seam (`RigLinkPort.kt`'s doc comment has the full account) — the
     * real link runs over WPC3's `RigLinkBridge` (`:pipeline`, merged `8e40041`), never
     * `:rig-bluetooth` directly (constitution VII); see [BridgeRigLinkPort]'s own doc comment.
     * Set once in [onCreate], from [DebugRigLinkPortOverride.activeOverride] first — the tour
     * cannot reach S10b's paired-device states over real Bluetooth hardware no AVD has
     * ([DebugRigLinkPortOverride]'s own doc comment). */
    private lateinit var rigLinkPort: RigLinkPort

    private val openDescriptorLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val text = runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }
            .getOrNull()
        if (text == null) {
            radioImportError = "Could not read that file."
            return@registerForActivityResult
        }
        RigCatalogue.import(text).fold(
            onSuccess = { descriptor ->
                importedRigDescriptors = importedRigDescriptors + descriptor
                radioImportError = null
            },
            onFailure = { error -> radioImportError = error.message ?: "That file is not a valid rig descriptor." },
        )
    }

    private val radioCatalogue: RigCatalogue get() = RigPickerCatalogue.build(importedRigDescriptors)

    /** WPC2: pushes [store]'s current mode/route/rig facts through [captureConfigStore] — the
     * contract `RealCaptureService` actually reads at session start. Called after every mutation
     * to those axes; a no-op before S00 is walked ([SetupCaptureConfigurationAdapter] returns
     * `null` until [SetupStore.captureMode] is set). [CaptureConfigurationStore.update] itself
     * decides pending-vs-immediate from whether capture is running (FR-CAP-12) — this call site
     * never needs to know which. */
    private fun syncCaptureConfiguration() {
        SetupCaptureConfigurationAdapter.toCaptureConfiguration(store)?.let(captureConfigStore::update)
    }

    /** The chosen rig's [RigCatalogueEntry], recovered from [SetupStore.rigId] rather than kept as
     * its own in-memory field — so a process death between S09 and S09b/S10b resumes correctly
     * ([SetupStateMachine]'s `RIG_TRANSPORT`/`RIG_BLUETOOTH` gates are resumable, unlike
     * [SetupStep.RADIO_USB]/[SetupStep.RADIO_VERIFIED]). */
    private fun currentRigEntry(): RigCatalogueEntry? = radioCatalogue.entries().firstOrNull { it.id == store.rigId }

    /** Test-only window into [step] — see `MainActivity.currentScreenForTest`'s identical pattern. */
    internal val currentStepForTest: SetupStep? get() = step

    /** Test-only window into [radioCatalogue] — a real `Activity`-level test cannot otherwise reach
     * a [RigCatalogueEntry] to hand to [onChooseRig] without duplicating [RigPickerCatalogue.build]
     * itself. */
    internal val radioCatalogueForTest: RigCatalogue get() = radioCatalogue

    /** Test-only window into [rigBluetoothDevices] — R-802's own regression proof needs to see
     * what a cold-opened S10b actually lists, not just that the screen renders something. */
    internal val rigBluetoothDevicesForTest: List<PairedDevice> get() = rigBluetoothDevices

    /** Test-only window into [rigLinkState] — the debug-extra follow-up's own regression proof
     * (connecting -> identified -> verified -> dropped) reads this directly rather than scraping
     * Compose semantics for each intermediate frame. */
    internal val rigLinkStateForTest: RigLinkState? get() = rigLinkState

    /** Test-only window into [rigBluetoothSelectedAddress] — proves [EXTRA_DEBUG_RIG_BLUETOOTH_ADDRESS]
     * actually reached this field, independent of whatever [rigLinkPort] then does with it. */
    internal val rigBluetoothSelectedAddressForTest: String? get() = rigBluetoothSelectedAddress

    /** Test-only window into [rigLinkVerifiedDurationSeconds] — R-903's own regression proof needs
     * to see the real measured value [onRigLinkStateChanged] computed. */
    internal val rigLinkVerifiedDurationSecondsForTest: Double? get() = rigLinkVerifiedDurationSeconds

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = OrtSystemBarStyle, navigationBarStyle = OrtSystemBarStyle)
        store = SharedPreferencesSetupStore(getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, MODE_PRIVATE))
        captureConfigStore = SharedPreferencesCaptureConfigurationStore(
            getSharedPreferences(SharedPreferencesCaptureConfigurationStore.PREFS_NAME, MODE_PRIVATE),
        )
        rigLinkPort = DebugRigLinkPortOverride.activeOverride ?: BridgeRigLinkPort(DefaultRigLinkBridge(this))
        audioIo = AndroidAudioIo(this)
        selectedInputId = store.selectedInputId
        selectedInputLabel = store.selectedInputLabel

        val fontScaleOverride = intent?.takeIf { it.hasExtra(EXTRA_FONT_SCALE) }?.getFloatExtra(EXTRA_FONT_SCALE, 1f)
        setContent {
            if (fontScaleOverride != null) {
                val platformDensity = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(platformDensity.density, fontScaleOverride)) {
                    OrtTheme { RenderStep() }
                }
            } else {
                OrtTheme { RenderStep() }
            }
        }
        if (!tryOpenAtRequestedStep(intent?.getStringExtra(EXTRA_STEP))) refreshStep()
        applyDebugRigBluetoothAddressExtra()
    }

    /** See [EXTRA_DEBUG_RIG_BLUETOOTH_ADDRESS]'s own doc comment. Called once, after the step has
     * already resolved above — deliberately after, not folded into [tryOpenAtRequestedStep], since
     * this must also apply on the *ordinary* resume path (no [EXTRA_STEP] at all: a store already
     * gated on `RIG_BLUETOOTH`), not only the debug direct-entry one. */
    private fun applyDebugRigBluetoothAddressExtra() {
        if (!isDebugBuild()) return
        if (step != SetupStep.RIG_BLUETOOTH) return
        val address = intent?.getStringExtra(EXTRA_DEBUG_RIG_BLUETOOTH_ADDRESS) ?: return
        onSelectRigBluetoothDevice(address)
    }

    /** See [EXTRA_STEP]'s own doc comment. Returns `true` (consuming the request) only when the
     * named step is honored; `false` leaves [step] untouched so the caller falls back to the
     * ordinary [refreshStep]. */
    private fun tryOpenAtRequestedStep(requestedStepName: String?): Boolean {
        val requested = requestedStepName?.let { name -> SetupStep.entries.firstOrNull { it.name == name } }
            ?: return false
        val naturalNext = SetupStateMachine.stepFor(currentPermissionsState(), micPermanentlyDenied(), store.snapshot())
        if (naturalNext != null && requested.ordinal > naturalNext.ordinal) return false
        step = requested
        if (requested == SetupStep.INPUT && inputRoutes.isEmpty()) refreshInputRoutes()
        return true
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

    // --- S00 Mode (D33, FR-CAP-8/FR-CAP-9) --------------------------------------------------------

    /**
     * Choosing a mode presets both independent axes (FR-CAP-9): the audio route to the first
     * enumerated route of [CaptureModePresets.presetsFor]'s preferred [org.ort.core.capture.AudioRouteKind]
     * (present, not yet verified — S04 still walks and shows it preselected, per the board), and
     * the rig-control transport to the preset's [org.ort.core.capture.CapturePreset.preferredRigTransportKind].
     * Both override flags reset — a fresh mode choice starts with nothing overridden yet.
     */
    internal fun onChooseMode(mode: CaptureMode) {
        store.captureMode = mode
        val preset = CaptureModePresets.presetsFor(mode)
        if (inputRoutes.isEmpty()) refreshInputRoutes() else applyPresetInputIfUnselected()
        store.modeOverriddenAudio = false
        store.rigTransport = preset.preferredRigTransportKind
        store.modeOverriddenRig = false
        syncCaptureConfiguration()
        refreshStep(pushCurrent = true)
    }

    // --- S02c Bluetooth permission (D33, FR-RIG-14) -----------------------------------------------

    internal fun requestBluetoothConnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_CODE)
        } else {
            // Below API 31 there is no dangerous BLUETOOTH_CONNECT permission to request at all —
            // the legacy BLUETOOTH permission is normal-protection and already granted.
            refreshStep()
        }
    }

    /** S02c's "Not now — use USB instead": the mode flip itself is what stops
     * [SetupStateMachine]'s `BLUETOOTH_PERMISSION` gate from firing again; [SetupStore
     * .bluetoothPermissionDeclined] records the decline defensively on top of that (that state
     * machine's own doc comment explains why both exist). Set *after* [onChooseMode] so the fresh
     * USB-mode preset does not reset it back to `false` the instant it is written. */
    internal fun onDeclineBluetoothPermission() {
        onChooseMode(CaptureMode.USB_RADIO)
        store.bluetoothPermissionDeclined = true
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
        applyPresetInputIfUnselected()
    }

    /** R-902: the single place [presetInputRouteFor]'s own "exactly one match" pre-select actually
     * runs — called from every path that can bring S04 up with a real route list and nothing chosen
     * yet, not only the interactive [onChooseMode] tap: [refreshInputRoutes] itself (a cold/resumed
     * entry straight onto `INPUT`, or a debug scenario that seeds `SetupStore.captureMode` directly
     * without ever calling [onChooseMode]) and [onChooseMode]'s own already-enumerated-routes case.
     * A no-op once the operator (or a resumed [SetupStore.selectedInputId]) has already chosen
     * something — never overwrites a real choice, matching [onChooseMode]'s own prior behaviour. */
    private fun applyPresetInputIfUnselected() {
        if (selectedInputId != null) return
        val route = presetInputRouteFor(store.captureMode, inputRoutes) ?: return
        selectedInputId = route.id
        selectedInputLabel = route.label
        store.selectedInputId = route.id
        store.selectedInputLabel = route.label
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
        syncCaptureConfiguration()
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

    // --- S09 Radio picker (D33/P19 WPD: generated from :rig's RigCatalogue) -----------------------

    /**
     * R-344 (validator pass 4, halt), extended by D33/P19: the null module ("No radio — I will
     * enter the frequency") used to write [SetupStore.radioChoice] and call [refreshStep]
     * immediately, the same instant as the tap — since that alone satisfies
     * [SetupStateMachine.stepFor]'s `radioChoice == null -> RADIO` gate, setup advanced straight
     * past S12 with `manualFrequencyHz` still `null`, having never asked for a frequency at all
     * (confirmed reproduced 3× by the validator). Routed to the same frequency-entry screen
     * ([RadioUsbScreen], S10) the CAT-rig-with-no-support fallback already uses instead —
     * [SetupStore.radioChoice] is deliberately **not** written here for the null module; only
     * [onEnterFrequency] writes it, and only once a real value is confirmed, the same gate that
     * already protects the CAT-rig fallback path. A real rig moves to S09b ([SetupStep.RIG_TRANSPORT])
     * — never straight to S10/S11 the way the pre-catalogue three-row screen did, since which
     * transport to use is now its own decision (FR-RIG-13).
     */
    internal fun onChooseRig(entry: RigCatalogueEntry) {
        radioAbsentBanner = null
        radioImportError = null
        store.rigId = entry.id
        if (entry.id == NullRigModule.ID) {
            store.rigTransport = null
            syncCaptureConfiguration()
            rigStatusSnapshot = RigStatus.state
            navigateForward(SetupStep.RADIO_USB)
            return
        }
        store.radioChoice = if (entry.displayName.contains("TH-D75A")) {
            RadioChoice.TH_D75A
        } else {
            RadioChoice.OTHER_CAT_RIG
        }
        val modePresetKind = store.captureMode?.let(CaptureModePresets::presetsFor)?.preferredRigTransportKind
        selectedRigTransportKind = modePresetKind?.let(RigPickerCatalogue::fromPresetKind)
            ?: entry.transportCapabilities.keys.firstOrNull { it != RigTransportKind.NONE }
        syncCaptureConfiguration()
        navigateForward(SetupStep.RIG_TRANSPORT)
    }

    internal fun onImportRig() {
        openDescriptorLauncher.launch(arrayOf("application/json", "*/*"))
    }

    internal fun onRadioNotNow() {
        store.radioChoice = RadioChoice.NONE
        store.rigId = NullRigModule.ID
        store.rigTransport = null
        radioAbsentBanner = null
        syncCaptureConfiguration()
        refreshStep(pushCurrent = true)
    }

    // --- S09b Rig transport (D33, FR-RIG-13/14/17) -------------------------------------------------

    internal fun onSelectRigTransport(kind: RigTransportKind) {
        selectedRigTransportKind = kind
    }

    internal fun onConnectRigTransport() {
        val kind = selectedRigTransportKind ?: return
        val presetKind = RigPickerCatalogue.toPresetKind(kind)
        store.rigTransport = presetKind
        val modePresetKind = store.captureMode?.let(CaptureModePresets::presetsFor)?.preferredRigTransportKind
        store.modeOverriddenRig = presetKind != modePresetKind
        syncCaptureConfiguration()
        if (kind == RigTransportKind.BLUETOOTH_SPP) {
            rigBluetoothSelectedAddress = null
            refreshPairedDevices()
            navigateForward(SetupStep.RIG_BLUETOOTH)
        } else {
            // R-903: the USB lane has no timed probe at all -- never carry a Bluetooth attempt's
            // own measured duration into an S11 visit this lane reaches directly.
            rigLinkVerifiedDurationSeconds = null
            // Matches the pre-catalogue onChooseRadio's own dispatch: a genuinely Connected
            // RigStatus (today, only ever produced by the debug scenario simulator -- :rig-usb's
            // real transport is not wired to :app, RigLinkPort.kt's own doc comment) skips the
            // honest "no rig support" fallback and goes straight to S11.
            rigStatusSnapshot = RigStatus.state
            val next = if (rigStatusSnapshot is RigStatus.State.Absent) {
                SetupStep.RADIO_USB
            } else {
                SetupStep.RADIO_VERIFIED
            }
            navigateForward(next)
        }
    }

    internal fun onRigTransportBack() {
        step = SetupStep.RADIO
    }

    // --- S10b Rig Bluetooth link (D33/D34, FR-RIG-14/15) --------------------------------------------

    internal fun onSelectRigBluetoothDevice(address: String) {
        rigBluetoothSelectedAddress = address
        rigLinkState = null
        rigLinkRunToken += 1
        // R-903: a fresh attempt's own start -- never inherits a duration from a previous pick.
        rigLinkVerifyStartedAtNanos = clock.monotonicNanos()
        rigLinkVerifiedDurationSeconds = null
    }

    /** R-903: [RenderRigBluetooth]'s own `LaunchedEffect` routes every [RigLinkState] emission
     * through here rather than assigning [rigLinkState] directly, so the one moment the checklist
     * first reaches [RigLinkState.Verified] is also the one moment S11's real, measured verify
     * duration gets computed — from [rigLinkVerifyStartedAtNanos], never recomputed on a later
     * recomposition even though [rigLinkState] itself does not change again after `Verified`. */
    internal fun onRigLinkStateChanged(new: RigLinkState) {
        rigLinkState = new
        if (new is RigLinkState.Verified && rigLinkVerifiedDurationSeconds == null) {
            rigLinkVerifyStartedAtNanos?.let { startedAt ->
                rigLinkVerifiedDurationSeconds = (clock.monotonicNanos() - startedAt) / NANOS_PER_SECOND
            }
        }
    }

    internal fun onRefreshRigBluetoothDevices() {
        refreshPairedDevices()
    }

    /** FR-PLT-2/constitution I: [RigLinkPort.pairedDevices]' own [PairedDevicesResult.permissionGranted]
     * drives [RigLinkState.NoPermission] directly — the operator sees the "grant nearby devices"
     * banner the moment S10b (or a `Refresh`) finds the permission missing, never only after they
     * have already picked a device and tried to connect. */
    private fun refreshPairedDevices() {
        val result = rigLinkPort.pairedDevices()
        rigBluetoothDevices = result.devices
        rigLinkState = if (result.permissionGranted) null else RigLinkState.NoPermission
    }

    internal fun onPairRigBluetoothInSettings() {
        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    internal fun onContinueRigBluetooth() {
        val address = rigBluetoothSelectedAddress ?: return
        if (rigLinkState !is RigLinkState.Verified) return
        store.rigBluetoothAddress = address
        store.rigBluetoothVerified = true
        val entry = currentRigEntry()
        // Honest, not fabricated: no live per-band reading exists yet (the real DescriptorRigModule
        // poll loop cannot run from :app — RigLinkPort.kt's own doc comment). An empty band list,
        // never an invented one, is what S11 renders until a live session actually reports bands.
        rigStatusSnapshot = RigStatus.State.Connected(descriptor = entry?.displayName ?: "Rig", bands = emptyList())
        syncCaptureConfiguration()
        navigateForward(SetupStep.RADIO_VERIFIED)
    }

    internal fun onUseUsbInsteadForRig() {
        store.rigTransport = PresetRigTransportKind.USB_SERIAL
        rigStatusSnapshot = RigStatus.state
        syncCaptureConfiguration()
        navigateForward(SetupStep.RADIO_USB)
    }

    /**
     * S10's "Enter the frequency instead" — reached both from S09's third row (R-344) and straight
     * from a chosen CAT rig with no real support (see [RadioUsbScreen]'s own doc comment). [hz] is
     * `null` for a blank or unparseable entry ([parseMegahertzToHz]) — [RadioUsbScreen] itself now
     * disables this action's own button until [hz] would be real, but this function guards it too
     * (constitution I: never silently lose a fact) rather than trusting the UI alone — a `null` here
     * is a no-op, not a proceed-with-nothing.
     */
    internal fun onEnterFrequency(hz: Long?) {
        if (hz == null) return
        store.radioChoice = RadioChoice.NONE
        store.manualFrequencyHz = hz
        syncCaptureConfiguration()
        refreshStep(pushCurrent = true)
    }

    internal fun onRadioVerifiedContinue() {
        refreshStep(pushCurrent = true)
    }

    internal fun onChangeRadio() {
        store.radioChoice = null
        store.rigId = null
        store.rigTransport = null
        store.rigBluetoothAddress = null
        store.rigBluetoothVerified = false
        radioAbsentBanner = null
        // R-903: a later S11 visit (a different rig, or the same one over a different transport)
        // must never show a duration measured for a now-abandoned attempt.
        rigLinkVerifyStartedAtNanos = null
        rigLinkVerifiedDurationSeconds = null
        syncCaptureConfiguration()
        step = SetupStep.RADIO
    }

    /** S11's `Reconnect` on a [RigStatus.State.Stale] reading (register R-120..R-125) — re-reads
     * [RigStatus.state] rather than any real reconnect handshake, since `:rig`/`:rig-usb` are
     * unbuilt (there is nothing to actually command); this is honest re-polling, not a fabricated
     * action. Reaches [RigStatus.State.Connected] once the debug scenario (the only real producer
     * today) or, eventually, a real rig module reports current data again. */
    internal fun onReconnectRadio() {
        rigStatusSnapshot = RigStatus.state
        step = SetupStep.RADIO_VERIFIED
    }

    // --- S12 Ready ------------------------------------------------------------------------------

    internal fun onStartCapture() {
        store.setupComplete = true
        handBackToMainActivity()
    }

    /**
     * S12's `Install` (`Setup-Done.dc.html`): opens [ReaderActivity] at its `SETTINGS` destination
     * (`ReaderActivity.EXTRA_DESTINATION`, WP3 round 3) rather than blocking on `Start capture`
     * first — asset installation is a general app concern, reachable regardless of whether this
     * session has started yet. **Lands on Settings' root, not its `Assets` sub-screen directly**:
     * confirmed by reading `ui/settings/SettingsContent.kt` before writing this — it takes no
     * `initialScreen`/equivalent parameter yet (its `screen` state is entirely internal, the same
     * gap `ReaderNavigator.openSettingsStorage()`'s own doc comment already reports for the
     * identical reason), and WP3 defines no second extra to name a sub-screen. Does not `finish()`
     * this activity — the operator returns to `Ready` normally (the platform back stack), since
     * this is a lateral look-something-up, not setup completing.
     */
    internal fun onInstallModel() {
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_DESTINATION, ReaderDestination.SETTINGS.name),
        )
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
            // D33/S02c: below API 31 there is no dangerous BLUETOOTH_CONNECT permission at all --
            // the legacy BLUETOOTH permission is normal-protection and granted at install, so this
            // reports true unconditionally rather than ever prompting (PermissionsState's own doc
            // comment).
            bluetoothConnectGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                granted(Manifest.permission.BLUETOOTH_CONNECT),
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
            SetupStep.MODE -> ModeScreen(onChoose = ::onChooseMode)
            SetupStep.MICROPHONE -> MicrophoneScreen(onAllow = ::requestRecordAudio, onBack = ::onBack)
            SetupStep.MICROPHONE_DENIED -> MicrophoneDeniedScreen(
                onOpenSettings = ::openAppSettings,
                onCheckAgain = ::checkMicAgain,
                onBack = ::onBack,
            )
            SetupStep.BLUETOOTH_PERMISSION -> BluetoothPermissionScreen(
                onAllow = ::requestBluetoothConnect,
                onNotNow = ::onDeclineBluetoothPermission,
            )
            SetupStep.NOTIFICATIONS ->
                NotificationsScreen(onAllow = ::requestNotifications, onSkip = ::skipNotifications, onBack = ::onBack)
            SetupStep.INPUT -> RenderInput()
            SetupStep.VERIFY -> RenderVerify()
            SetupStep.ROUTE_MISMATCH -> RenderRouteMismatch()
            SetupStep.LEVEL -> RenderLevel()
            SetupStep.OVERNIGHT -> OvernightScreen(onOpenSetting = ::onOpenBatterySetting, onSkip = ::onSkipOvernight)
            SetupStep.RADIO -> RenderRadioPicker()
            SetupStep.RIG_TRANSPORT -> RenderRigTransport()
            SetupStep.RADIO_USB -> RadioUsbScreen(
                rigStatus = rigStatusSnapshot,
                onBack = ::onBack,
                onEnterFrequency = ::onEnterFrequency,
            )
            SetupStep.RIG_BLUETOOTH -> RenderRigBluetooth()
            SetupStep.RADIO_VERIFIED -> RenderRadioVerified()
            SetupStep.READY -> RenderReady()
            null -> {}
        }
    }

    @Composable
    private fun RenderInput() {
        val chipState = presetChipStateFor(store.captureMode, store.modeOverriddenAudio, inputRoutes)
        // R-902: the same "exactly one match" rule presetInputRouteFor's own pre-select uses --
        // several equally-preferred routes are exactly as ambiguous for override-detection as they
        // are for pre-selection, never treated as if the first one enumerated were "the" preset.
        val presetRouteId = presetInputRouteFor(store.captureMode, inputRoutes)?.id
        InputScreen(
            state = InputViewState(
                routes = inputRoutes,
                selectedId = selectedInputId,
                presetLabel = chipState.modeLabel,
                presetUnavailableText = chipState.presetUnavailableText,
            ),
            onSelect = {
                if (isAudioRouteOverride(store.captureMode, presetRouteId, it)) store.modeOverriddenAudio = true
                onSelectInput(it)
            },
            onRefresh = ::refreshInputRoutes,
            onVerify = ::onStartVerify,
            onBack = ::onBack,
        )
    }

    @Composable
    private fun RenderRadioPicker() {
        val presetLabel = store.captureMode?.operatorLabel?.takeUnless { store.modeOverriddenRig }
        val catalogue = radioCatalogue
        // R-815: the currently chosen/preset rig, else the catalogue's own verified entry --
        // RadioPickerViewState's own doc comment has the full account.
        val emphasizedEntryId = store.rigId ?: catalogue.entries().firstOrNull { it.verified }?.id
        RadioScreen(
            state = RadioPickerViewState(
                catalogue = catalogue,
                presetLabel = presetLabel,
                importError = radioImportError,
                emphasizedEntryId = emphasizedEntryId,
            ),
            onChoose = ::onChooseRig,
            onImport = ::onImportRig,
            onNotNow = ::onRadioNotNow,
            banner = radioAbsentBanner,
        )
    }

    @Composable
    private fun RenderRigTransport() {
        val entry = currentRigEntry() ?: return
        val modePresetKind = store.captureMode?.let(CaptureModePresets::presetsFor)?.preferredRigTransportKind
        val options = entry.transportCapabilities.keys
            .filter { it != RigTransportKind.NONE }
            .sortedBy { if (it == RigTransportKind.BLUETOOTH_SPP) 0 else 1 }
            .map { kind ->
                val isPreset = modePresetKind != null && RigPickerCatalogue.toPresetKind(kind) == modePresetKind
                RigTransportOption(
                    kind = kind,
                    label = RigPickerCatalogue.transportLabel(kind),
                    subLabel = rigTransportSubLabel(kind, isPreset),
                    capabilities = RigPickerCatalogue.capabilitiesFor(entry, kind),
                    costLine = rigTransportCostLine(kind),
                    isPreset = isPreset,
                )
            }
        RigTransportScreen(
            state = RigTransportViewState(entry.displayName, options, selectedRigTransportKind),
            onSelect = ::onSelectRigTransport,
            onConnect = ::onConnectRigTransport,
            onBack = ::onRigTransportBack,
        )
    }

    @Composable
    private fun RenderRigBluetooth() {
        val entry = currentRigEntry()
        val address = rigBluetoothSelectedAddress
        if (address != null) {
            LaunchedEffect(rigLinkRunToken, address) {
                rigLinkPort.connect(address, entry?.id ?: "").collect(::onRigLinkStateChanged)
            }
        }
        RigBluetoothScreen(
            state = RigBluetoothViewState(
                rigDisplayName = entry?.displayName ?: "the rig",
                devices = rigBluetoothDevices,
                selectedAddress = rigBluetoothSelectedAddress,
                linkState = rigLinkState,
            ),
            onSelectDevice = ::onSelectRigBluetoothDevice,
            onPairInSettings = ::onPairRigBluetoothInSettings,
            onRefresh = ::onRefreshRigBluetoothDevices,
            onContinue = ::onContinueRigBluetooth,
            onUseUsbInstead = ::onUseUsbInsteadForRig,
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
            onBack = ::onBack,
            onTryAgain = ::onTryVerifyAgain,
            onChooseAnotherInput = ::onChooseAnotherInput,
        )
    }

    /** R-222 (validator pass 2): looks up the chosen device's already-resolved
     * [InputRouteOption.typeLabel] from S04's own route list, rather than re-deriving it from the
     * coarser [org.ort.capture.android.AudioDeviceDescriptor.kind] `RouteCheckState.Mismatch`
     * itself carries — see [RouteMismatchScreen]'s own doc comment for why. Falls back to a plain
     * `"Unknown"` only when the route list is somehow empty at this point (never reachable through
     * the ordinary S04 -> S05 -> S06 flow, since [onStartVerify] cannot fire without a selection
     * already present in that same list) — never a crash, and never a fabricated specific type.
     */
    @Composable
    private fun RenderRouteMismatch() {
        val mismatch = verifyState as? RouteCheckState.Mismatch ?: return
        val selectedTypeLabel = inputRoutes.firstOrNull { it.id == mismatch.selected.id }?.typeLabel ?: "Unknown"
        RouteMismatchScreen(
            mismatch = mismatch,
            selectedTypeLabel = selectedTypeLabel,
            onChooseAnotherInput = ::onChooseAnotherInput,
            onTryAgain = ::onTryVerifyAgain,
            onBack = ::onChooseAnotherInput,
        )
    }

    /**
     * S07 prefers a real, already-running [LevelStatus] over driving a second, competing
     * `AudioRecord` open of its own: [LevelStatus] is published only by `RealCaptureService`
     * itself, so a real [LevelStatus.State.Measured] here means a live capture session already has
     * this device open (an operator revisiting setup while capture runs, or re-verifying after a
     * mismatch). [LevelStatus.state] is a plain `@Volatile` field, not Compose-observable state, so
     * the poll below (not a one-shot read) is what keeps the meter live — [RealLevelCheck] remains
     * the path for the ordinary first-run case, where nothing has published anything yet.
     */
    @Composable
    private fun RenderLevel() {
        if (LevelStatus.state is LevelStatus.State.Measured) {
            LaunchedEffect(levelRunToken) {
                while (true) {
                    val measured = LevelStatus.state
                    if (measured is LevelStatus.State.Measured) {
                        val reading = levelReadingFrom(measured, LevelStatus.peakHistoryDbfs)
                        onLevelStateChanged(LevelCheckState.Reading(reading))
                    }
                    delay(LEVEL_STATUS_POLL_INTERVAL_MILLIS)
                }
            }
        } else {
            val selection = selectedDescriptor()
            if (selection != null) {
                LaunchedEffect(levelRunToken) {
                    RealLevelCheck().run(audioIo, selection).collect { onLevelStateChanged(it) }
                }
            }
        }
        LevelScreen(state = levelState, onContinue = ::onLevelContinue)
    }

    /**
     * Validator finding (register R-120..R-125, halt): this used to cast straight to
     * [RigStatus.State.Connected] and `return` (rendering nothing) for anything else — a
     * [RigStatus.State.Stale] reading (the real `rig-lost` scenario, reached through
     * [onChooseRadio]) hit exactly that `return` and left the operator on a blank black screen.
     * [RadioVerifiedScreen] itself now renders `Connected`/`Stale` both honestly; the one case
     * still handled here, before ever calling it, is [RigStatus.State.Absent] — reachable only if
     * the rig status changes *between* [onChooseRadio]'s snapshot and this composition (e.g. an
     * `onResume` re-check while already on S11), since [onChooseRadio] itself already routes an
     * `Absent` reading to S10, never S11. "Routes back to S09 with a banner, never a blank
     * screen" (the finding's own words) is exactly what this `LaunchedEffect` does.
     */
    @Composable
    private fun RenderRadioVerified() {
        val status = rigStatusSnapshot
        if (status is RigStatus.State.Absent) {
            LaunchedEffect(Unit) {
                radioAbsentBanner = "The rig connection was lost before setup could verify it. " +
                    "Choose a radio again, or enter the frequency by hand."
                step = SetupStep.RADIO
            }
            return
        }
        val rigTransportKind = store.rigTransport?.let(RigPickerCatalogue::fromPresetKind)
        RadioVerifiedScreen(
            state = status,
            onContinue = ::onRadioVerifiedContinue,
            onChangeRadio = ::onChangeRadio,
            onReconnect = ::onReconnectRadio,
            transportLabel = store.rigTransport?.let {
                when (it) {
                    PresetRigTransportKind.USB_SERIAL -> "USB serial"
                    PresetRigTransportKind.BLUETOOTH_SPP -> "Bluetooth SPP"
                }
            },
            verifyDurationSeconds = rigLinkVerifiedDurationSeconds,
            sameCommandSetAsUsb = sameCommandSetAsUsb(
                currentRigEntry()?.transportCapabilities.orEmpty(),
                rigTransportKind,
            ),
        )
    }

    @Composable
    private fun RenderReady() {
        val actions = ReadyActions(
            onFixInput = { step = SetupStep.INPUT },
            onFixLevel = { step = SetupStep.LEVEL },
            onFixOvernight = { step = SetupStep.OVERNIGHT },
            onFixRadio = { step = SetupStep.RADIO },
            // R-285: the same clear-then-navigate callback S11's own "Change radio" already uses
            // ([RadioVerifiedScreen]), not the bare step jump [onFixRadio] is — this row is already
            // `ok`, not broken, so a stray leftover `radioAbsentBanner` must not appear on S09.
            onChangeRadio = ::onChangeRadio,
            onInstallModel = ::onInstallModel,
            onChangeMode = { step = SetupStep.MODE },
        )
        val modelsState = ModelsController.currentState(this)
        val rows = readyRowsFor(store, batteryExempt(), rigStatusSnapshot, modelsState, actions)
        ReadyScreen(state = ReadyViewState(rows), onStartCapture = ::onStartCapture)
    }

    /** S09b's per-transport sub-line (`Setup-Rig-Transport.dc.html`) — the preset case names it
     * generically ("preset by your mode") since S09b has no knowledge of the specific mode's own
     * board copy; the non-preset case names the physical connection. */
    private fun rigTransportSubLabel(kind: RigTransportKind, isPreset: Boolean): String = when {
        isPreset && kind == RigTransportKind.BLUETOOTH_SPP -> "preset by your mode · pair in system settings first"
        isPreset -> "preset by your mode"
        kind == RigTransportKind.BLUETOOTH_SPP -> "pair in system settings first"
        kind == RigTransportKind.USB_SERIAL -> "USB-C to the radio's data port · CDC, no driver"
        else -> ""
    }

    private fun rigTransportCostLine(kind: RigTransportKind): String = when (kind) {
        RigTransportKind.BLUETOOTH_SPP -> "Drops more often than a cable — capture continues, frequency marked stale"
        RigTransportKind.USB_SERIAL -> "USB permission does not survive a re-plug"
        else -> ""
    }

    internal companion object {
        /** R-903: the divisor [onRigLinkStateChanged] uses to turn a [org.ort.core.Clock.monotonicNanos]
         * delta into the real, decimal seconds [radioVerifiedSubtitle] renders ("1.2 s"). */
        private const val NANOS_PER_SECOND: Double = 1_000_000_000.0

        /** See this class's own doc comment. The name of a [SetupStep] entry, e.g. `"INPUT"`. */
        const val EXTRA_STEP: String = "step"

        /** See this class's own doc comment. A `Float` `fontScale`, e.g. `2.0f`. Absent means
         * "render at the platform's own font scale" — never assume `1.0f` means the same thing as
         * absence; they differ whenever the platform's own real setting is not already `1.0`. */
        const val EXTRA_FONT_SCALE: String = "font_scale"

        /**
         * Debug-only (tour builder follow-up, `spec/e2e-capture-modes-plan.md` WPD): a paired
         * device's Bluetooth MAC address to pre-select on S10b, honored only when [isDebugBuild]
         * reports true — see this constant's doc, and [DebugRigLinkPortOverride]'s own class kdoc
         * for why one of these seams exists at all here. [SetupStore]'s `RIG_BLUETOOTH` gate is
         * resumable, but the connect -> identify -> verify checklist and its drop/failure banners
         * (E2-E10/E2-E11) only ever animate after [onSelectRigBluetoothDevice] runs, which is
         * normally a tap on a paired-device row (`RigBluetoothScreen`'s own `PairedDeviceRow`) — a
         * tap the screenshot tour (`app/src/debug/kotlin/org/ort/app/debug/tour`) cannot perform,
         * since it can only relaunch this activity with intent extras. Read once, in [onCreate],
         * immediately after the step resolves (whether via [EXTRA_STEP] or the ordinary
         * [refreshStep] resume) — a no-op unless that resolved step is exactly `RIG_BLUETOOTH`, so
         * launching at any other step (or a debug build with no `RIG_BLUETOOTH` gate pending)
         * ignores this extra entirely rather than fabricating a selection nothing asked for. In a
         * release build [isDebugBuild] reports false and the extra is never even read, matching
         * [DebugRigLinkPortOverride.activeOverride]'s own gating exactly.
         */
        const val EXTRA_DEBUG_RIG_BLUETOOTH_ADDRESS: String = "debug_rig_bluetooth_address"

        /** Test seam — see [EXTRA_DEBUG_RIG_BLUETOOTH_ADDRESS]'s own doc comment for why one is
         * needed, identically to [DebugRigLinkPortOverride.isDebugBuild]: Robolectric only ever
         * compiles this module's debug variant, so `BuildConfig.DEBUG` alone cannot prove "ignored
         * in a release build" from a unit test. Production code never assigns this. */
        @Volatile
        internal var isDebugBuild: () -> Boolean = { BuildConfig.DEBUG }

        const val TAG = "SetupActivity"
        const val REQUEST_CODE = 1002

        /** Matches `RealLevelCheck.DEFAULT_SAMPLE_INTERVAL_MILLIS` and `RealCaptureService`'s own
         * roughly-10Hz frame-read cadence (`LevelStatus`'s own doc comment) — polling any faster
         * would just re-read the same snapshot. */
        const val LEVEL_STATUS_POLL_INTERVAL_MILLIS = 200L
    }
}
