package org.ort.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ort.app.BuildConfig
import org.ort.app.diagnostics.DiagnosticsBundleBuilder
import org.ort.app.fieldreport.bundle.FieldReportBundleBuilder
import org.ort.app.fieldreport.bundle.FieldReportBundlePreview
import org.ort.app.fieldreport.bundle.FieldReportGatedCategory
import org.ort.app.fieldreport.settings.SharedPreferencesFieldReportSettingsStore
import org.ort.app.fieldreport.upload.FieldReportUploadClientFactory
import org.ort.app.ui.data.RealCaptureModeFacts
import org.ort.app.ui.data.realCaptureConfigurationStore
import org.ort.app.ui.setup.SetupActivity
import org.ort.app.ui.setup.SetupStep
import org.ort.app.ui.theme.OrtSpacing
import org.ort.core.fieldreport.FieldReportDestination
import org.ort.core.fieldreport.FieldReportDestinationVisibility
import org.ort.core.fieldreport.FieldReportUploadCategory
import org.ort.core.fieldreport.FieldReportUploadClient
import org.ort.core.fieldreport.FieldReportUploadRequest
import org.ort.pipeline.capture.CaptureState
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.Locale

/**
 * WP10 (register R-090): the stateful entry point `OrtNavHost` dispatches `SETTINGS` to — owns the
 * [SettingsStore] instance, drives [SettingsPolling], and holds which of the nine sub-screens is
 * currently showing as its own local navigation state (never lifted into `OrtNavHost`, which this
 * package may only edit for the SETTINGS/IMPROVE/DIGEST/SESSIONS dispatch swap itself). `Assets`
 * dispatches into [ModelsContent] — the one entry WP3 already moved into this package — passing its
 * own `onBack` so it renders WP2's [org.ort.app.ui.components.DrillInHeader] back to `Settings`
 * rather than the bare, header-less screen it had before this destination had a root to return to.
 *
 * [initialScreen] (round 3, WP3's host find): lets a caller land directly on one sub-screen —
 * `Assets` (Now's "Install a model", Setup S12's `Install`), `Storage` (F6's "Free space"), `Rig`
 * (F9's "Reconnect"), `Capture` (N06's `Adjust`) — instead of always opening the `Settings` root
 * first. A fresh `remember` seeded once, matching [org.ort.app.ui.navigation.rememberReaderNavigator]'s
 * own `initialDestination` contract ("opens there on launch", not "always jumps there" — a later
 * change to this parameter after first composition has no effect); `null` (the default, so every
 * existing caller keeps compiling unchanged) opens the root, exactly as before this parameter
 * existed. `Back` from that sub-screen still returns to the root, not out of this composable —
 * this only changes where the screen starts, not the navigation shape.
 *
 * [onOpenLevelMeter] (round 4, System validator, R-132): `Settings-Capture`'s `Meter` action needs
 * WP4's `Level-Meter` destination, which this package cannot reach on its own (no drill-in of that
 * shape exists inside `ui/settings`, and `OrtNavHost.kt` is outside this round's file ownership).
 * Defaults to a no-op so every existing caller (`OrtNavHost.kt`) keeps compiling unchanged; the
 * host is expected to wire it the same way it wires every other cross-package drill-in.
 *
 * **Round 6 (WP3's own smoke test find):** the root now draws its own [org.ort.app.ui.components.ScreenHeader]
 * again (drawer icon via [onDrawer], live dot, search via the new [onSearch]) — R-130 (round 4) had
 * removed it on the premise that `OrtNavHost`'s host header always covers this destination, but
 * that host header is keyed only on the current drawer *destination*, not on this composable's own
 * internal root/sub-screen state: once WP3 started landing directly on a sub-screen via
 * [initialScreen], the host's `ScreenHeader` and that sub-screen's own `DrillInHeader` rendered
 * stacked (the double-header bug again, just one level down). Root screens across every other WP10
 * sub-package ([org.ort.app.ui.improve.ImproveContent], [org.ort.app.ui.digest.SessionsContent])
 * are unaffected — neither exposes an `initialScreen`-shaped external entry into a sub-screen, so
 * the host's own header, drawn once for the whole destination, is still the only one for them.
 * **`OrtNavHost.kt`'s own `ScreenHeader` for the `SETTINGS` destination must be removed to match** —
 * outside this round's file ownership, reported for WP3 to make.
 */
@Composable
public fun SettingsContent(
    context: Context,
    onDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    initialScreen: SettingsScreenId? = null,
    onOpenLevelMeter: () -> Unit = {},
    onSearch: () -> Unit = {},
    // R-133 (round 8): `Settings-Storage`'s "Next deletion" row `Review` link needs the digest's
    // `Session` (DG04) destination for a specific `sessionId`, which this package cannot reach on
    // its own (no drill-in of that shape exists inside `ui/settings`, and `OrtNavHost.kt` is
    // outside this round's file ownership) — the same reason [onOpenLevelMeter] (R-132) exists.
    // Defaults to a no-op so every existing caller (`OrtNavHost.kt`) keeps compiling unchanged; the
    // host is expected to wire it the same way it wires every other cross-package drill-in.
    onReviewSession: (sessionId: String) -> Unit = {},
) {
    val store = remember {
        SharedPreferencesSettingsStore(
            context.getSharedPreferences(SharedPreferencesSettingsStore.PREFS_NAME, Context.MODE_PRIVATE),
        )
    }
    var screen by remember { mutableStateOf(initialScreen) }
    // Bumped after every write so a sub-screen's own `remember(storeVersion)` recomputes from the
    // store it just wrote to, and the root's sub-lines catch up the next time it is shown.
    var storeVersion by remember { mutableStateOf(0) }
    var root by remember { mutableStateOf<SettingsRootViewState?>(null) }
    LaunchedEffect(storeVersion, screen == null) {
        if (screen == null) root = SettingsPolling.root(context, store)
    }

    val current = screen
    if (current == null) {
        val state = root
        if (state != null) {
            SettingsRootScreen(
                state = state,
                onDrawer = onDrawer,
                onSearch = onSearch,
                onOpen = { screen = it },
                modifier = modifier,
            )
        } else {
            LoadingSettings(modifier = modifier)
        }
    } else {
        SettingsSubScreen(
            context = context,
            screen = current,
            store = store,
            storeVersion = storeVersion,
            onStoreChanged = { storeVersion++ },
            onBack = { screen = null },
            modifier = modifier,
            crossPackage = SettingsCrossPackageActions(
                onOpenLevelMeter = onOpenLevelMeter,
                onReviewSession = onReviewSession,
                onOpenModeSettings = { screen = SettingsScreenId.MODE },
            ),
        )
    }
}

/** The nine sub-screens, split out of [SettingsContent] purely to keep that function under
 * detekt's length/complexity limits — the same reason `OrtNavHost`'s own `DestinationContent` was
 * extracted before this package existed. [crossPackage] bundles the cross-package/cross-screen
 * drill-in callbacks (see [SettingsCrossPackageActions]'s own doc comment) purely to keep this
 * function's own parameter list under the same threshold. */
@Composable
private fun SettingsSubScreen(
    context: Context,
    screen: SettingsScreenId,
    store: SettingsStore,
    storeVersion: Int,
    onStoreChanged: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
    crossPackage: SettingsCrossPackageActions,
) {
    when (screen) {
        SettingsScreenId.CAPTURE -> SettingsCaptureSubScreen(
            context = context,
            store = store,
            storeVersion = storeVersion,
            onStoreChanged = onStoreChanged,
            onBack = onBack,
            modifier = modifier,
            onOpenLevelMeter = crossPackage.onOpenLevelMeter,
            onOpenModeSettings = crossPackage.onOpenModeSettings,
        )

        SettingsScreenId.RIG -> SettingsRigScreen(
            state = remember(storeVersion) { SettingsPolling.rig(context) },
            onBack = onBack,
            modifier = modifier,
            // WPC2 (`RigSupervisor`'s own doc comment, confirmed by reading `RigSupervisor.kt`
            // before wiring this): reconnection is the transport's own job — `UsbSerialTransport`/
            // `BluetoothSppTransport` each self-heal on their own backoff ladder; there is no
            // separate "reconnect" entry point for this screen to call. `Reconnect` re-reads
            // `RigStatus` fresh via the same `onStoreChanged` bump every other real write in this
            // file already uses — an honest refresh, never a claim of having reconnected anything.
            onReconnect = onStoreChanged,
            onSwitchTransport = { openSetupAtStep(context, SETUP_STEP_RIG_TRANSPORT) },
        )

        SettingsScreenId.TIER -> SettingsTierScreen(
            state = remember(storeVersion) { SettingsPolling.tier(store) },
            onBack = onBack,
            onSelectOverride = {
                store.tierOverrideName = it
                onStoreChanged()
            },
            modifier = modifier,
        )

        SettingsScreenId.STORAGE -> SettingsStorageSubScreen(
            context = context,
            store = store,
            storeVersion = storeVersion,
            onStoreChanged = onStoreChanged,
            onBack = onBack,
            modifier = modifier,
            onReviewSession = crossPackage.onReviewSession,
        )

        SettingsScreenId.ASSETS -> ModelsContent(context = context, modifier = modifier, onBack = onBack)

        SettingsScreenId.EXPORT -> {
            var exportState by remember { mutableStateOf<SettingsExportViewState?>(null) }
            LaunchedEffect(Unit) { exportState = SettingsPolling.export(context) }
            val state = exportState
            if (state != null) {
                SettingsExportScreen(state = state, onBack = onBack, modifier = modifier)
            } else {
                LoadingSettings(modifier = modifier)
            }
        }

        SettingsScreenId.CONTRIBUTE -> SettingsContributeScreen(
            state = remember(storeVersion) { SettingsPolling.contribute(store) },
            onBack = onBack,
            onToggleCategory = { index, value ->
                applyContributeToggle(store, index, value)
                onStoreChanged()
            },
            modifier = modifier,
        )

        SettingsScreenId.DIAGNOSTICS -> SettingsDiagnosticsSubScreen(
            context = context,
            onBack = onBack,
            modifier = modifier,
        )

        SettingsScreenId.ABOUT -> SettingsAboutScreen(
            state = remember { SettingsPolling.about(context) },
            onBack = onBack,
            modifier = modifier,
        )

        SettingsScreenId.MODE -> SettingsModeSubScreen(
            context = context,
            storeVersion = storeVersion,
            onStoreChanged = onStoreChanged,
            onBack = onBack,
            modifier = modifier,
        )
    }
}

/**
 * CF11's own branch, split out purely to keep [SettingsSubScreen] under detekt's length limit — the
 * same reason every other real-I/O branch here already is its own function.
 *
 * Picking a mode always writes through [org.ort.pipeline.rig.CaptureConfigurationStore.update]
 * (WPC2, merged `e464820`) — that store itself is what decides whether the write lands as [current]
 * immediately or as [pendingConfiguration] instead, per [CaptureState.isCapturing]
 * (FR-CAP-12, AC-131); this screen never re-implements that freeze rule. Only [mode] changes on the
 * write — [selectedInputId]/[rigId]/[rigTransportKind]/[rigParams] are carried forward from
 * [CaptureConfigurationStore.current] unchanged, since a real device/rig re-selection needs the
 * real enumeration only Setup can do; the two "what the mode set" rows' own `Change` re-enter Setup
 * for exactly that (S04/S09b — S09b is WPD's still-in-flight `RIG_TRANSPORT` step name, a forward-
 * compatible, currently-inert string literal — see [openSetupAtStep]'s own doc comment).
 */
@Composable
private fun SettingsModeSubScreen(
    context: Context,
    storeVersion: Int,
    onStoreChanged: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val modeFacts = remember(context) { RealCaptureModeFacts(context) }
    val modeState = remember(storeVersion) { SettingsPolling.modeScreen(context, modeFacts) }
    SettingsModeScreen(
        state = modeState,
        onBack = onBack,
        onSelectMode = { mode ->
            val configStore = realCaptureConfigurationStore(context)
            configStore.update(configStore.current().copy(mode = mode))
            onStoreChanged()
        },
        onChangeAudioRoute = { openSetupAtStep(context, SetupStep.INPUT.name) },
        onChangeRigLink = { openSetupAtStep(context, SETUP_STEP_RIG_TRANSPORT) },
        modifier = modifier,
    )
}

/** WPD's rig-transport re-entry step (`spec/e2e-capture-modes-plan.md` §WPD) — not yet added to
 * `SetupStep` on `main`. A plain string literal, not `SetupStep.RIG_TRANSPORT.name` (which does not
 * compile against today's enum): `SetupActivity`'s own `tryOpenAtRequestedStep` already falls back
 * to its ordinary `refreshStep()` for any name it does not recognise (confirmed by reading
 * `SetupActivity.kt` before wiring this), so this literal is an inert, harmless no-op today and
 * becomes a real re-entry point the moment WPD's branch adds the matching `SetupStep` entry — no
 * second edit needed here when that lands. */
private const val SETUP_STEP_RIG_TRANSPORT: String = "RIG_TRANSPORT"

private fun openSetupAtStep(context: Context, stepName: String) {
    val intent = Intent(context, SetupActivity::class.java)
    intent.putExtra(SetupActivity.EXTRA_STEP, stepName)
    context.startActivity(intent)
}

/** The `CAPTURE` branch of [SettingsSubScreen], split out purely to keep that function's own
 * length under detekt's limit — the same reason [SettingsSubScreen] itself was split out of
 * [SettingsContent].
 *
 * CF02 (amended 2026-09-10): the leading Capture-mode row reads through
 * [org.ort.app.ui.data.CaptureModeFacts], backed by WPC2's `CaptureConfigurationStore` — plain
 * `SharedPreferences`, so `SettingsPolling.capture` stays the same synchronous
 * `remember(storeVersion)` read every other cheap fact on this screen already uses.
 * [onOpenModeSettings] is CF02's own `Change` action → CF11.
 */
@Composable
private fun SettingsCaptureSubScreen(
    context: Context,
    store: SettingsStore,
    storeVersion: Int,
    onStoreChanged: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
    onOpenLevelMeter: () -> Unit,
    onOpenModeSettings: () -> Unit,
) {
    SettingsCaptureScreen(
        state = remember(storeVersion) { SettingsPolling.capture(context, store) },
        onBack = onBack,
        toggles = SettingsCaptureToggleActions(
            onToggleLevelWarn = {
                store.levelWarnEnabled = it
                onStoreChanged()
            },
            onToggleNoiseReduction = {
                store.noiseReductionEnabled = it
                onStoreChanged()
            },
            onToggleBandPass = {
                store.bandPassFilterEnabled = it
                onStoreChanged()
            },
        ),
        // R-132: both real actions land on the same place — see `SettingsCaptureScreen`'s own doc
        // comment for why there is no narrower "re-verify only" entry point yet.
        onOpenInputSetup = {
            val intent = Intent(context, SetupActivity::class.java)
            intent.putExtra(SetupActivity.EXTRA_STEP, SetupStep.INPUT.name)
            context.startActivity(intent)
        },
        onOpenLevelMeter = onOpenLevelMeter,
        onOpenModeSettings = onOpenModeSettings,
        onEditManualFrequency = {
            store.manualFrequencyMhz = it
            onStoreChanged()
        },
        modifier = modifier,
    )
}

/** The `STORAGE` branch of [SettingsSubScreen], split out purely to keep that function's own
 * length/parameter-count under detekt's limits — the same reason [SettingsCaptureSubScreen] was.
 *
 * R-133 (round 8): `SettingsPolling.storage` became `suspend` once it started calling
 * `:pipeline`'s real `measureStorageAccounting`/`collectSessionStorageSummaries`/
 * `computeNextDeletion` (real file/database I/O) — the same `LaunchedEffect`-backed async-load
 * shape `EXPORT`'s own branch above uses, keyed on `storeVersion` (unlike `EXPORT`'s own `Unit`
 * key) so a budget/auto-prune write re-measures, matching every other `remember(storeVersion)`
 * sub-screen's own reload contract.
 */
@Composable
private fun SettingsStorageSubScreen(
    context: Context,
    store: SettingsStore,
    storeVersion: Int,
    onStoreChanged: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
    onReviewSession: (sessionId: String) -> Unit,
) {
    var storageState by remember { mutableStateOf<SettingsStorageViewState?>(null) }
    LaunchedEffect(storeVersion) { storageState = SettingsPolling.storage(context, store) }
    val state = storageState
    if (state != null) {
        SettingsStorageScreen(
            state = state,
            onBack = onBack,
            onSetBudgetGb = {
                store.audioBudgetGb = it
                onStoreChanged()
            },
            onToggleAutoPrune = {
                store.autoPruneEnabled = it
                onStoreChanged()
            },
            onReviewSession = onReviewSession,
            modifier = modifier,
        )
    } else {
        LoadingSettings(modifier = modifier)
    }
}

/** The `DIAGNOSTICS` branch of [SettingsSubScreen], split out purely to keep that function's own
 * length/parameter-count under detekt's limits — the same reason [SettingsStorageSubScreen] was.
 *
 * R-137 (round 9, register): `SettingsPolling.diagnostics` became `suspend` once it started
 * calling WP11e's real `DiagnosticsBundleBuilder.preview` — the same async-load shape every other
 * real-I/O sub-screen here uses. `Save bundle` writes through the Storage Access Framework
 * ([ActivityResultContracts.CreateDocument]) on [Dispatchers.IO], then names the *real* file the
 * system actually created (its own `DISPLAY_NAME` column — a user can rename the suggested name in
 * the picker, so this never assumes the suggestion was kept). `Preview` is a local `previewOpen`
 * toggle — see [SettingsDiagnosticsScreen]'s own doc comment for why it is an in-app listing
 * rather than the board's own per-file external-reader wording.
 */
@Composable
private fun SettingsDiagnosticsSubScreen(context: Context, onBack: () -> Unit, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var diagnosticsState by remember { mutableStateOf<SettingsDiagnosticsViewState?>(null) }
    LaunchedEffect(Unit) { diagnosticsState = SettingsPolling.diagnostics(context) }
    var previewOpen by remember { mutableStateOf(false) }
    var saveConfirmationLabel by remember { mutableStateOf<String?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    DiagnosticsBundleBuilder.write(context, out)
                }
            }
            saveConfirmationLabel = "Saved ${realFileName(context, uri)}"
        }
    }

    val state = diagnosticsState
    FieldReportHost(context = context, modifier = modifier) { fieldReport, fieldReportActions ->
        if (state != null) {
            SettingsDiagnosticsScreen(
                state = state.copy(fieldReport = fieldReport),
                onBack = onBack,
                bundleActions = SettingsDiagnosticsBundleActions(
                    onPreview = { previewOpen = true },
                    onSaveBundle = { saveLauncher.launch("diagnostics-${LocalDate.now()}.zip") },
                ),
                previewOpen = previewOpen,
                onDismissPreview = { previewOpen = false },
                saveConfirmationLabel = saveConfirmationLabel,
                fieldReportActions = fieldReportActions,
                modifier = modifier,
            )
        } else {
            LoadingSettings(modifier = modifier)
        }
    }
}

/**
 * WPR2 (FR-OBS-6..12, D37/D38): everything the field-report feature owns — the FR-OBS-10
 * `FieldReportSettingsStore` (see that file's own doc comment for why it lives in
 * `fieldreport/settings`, not `SettingsStore.kt`), the FR-OBS-9 consent screen's own open/toggle
 * state, and the real `FieldReportBundleBuilder`/`FieldReportUploadClient` calls — split out of
 * [SettingsDiagnosticsSubScreen] purely to keep that function under detekt's length limit.
 *
 * When the consent screen is not open, [content] is invoked with the current
 * [FieldReportSectionViewState] (`null` in a release build, FR-OBS-6) and the two actions that
 * open it / flip the FR-OBS-10 switch — [SettingsDiagnosticsSubScreen] renders its own
 * `SettingsDiagnosticsScreen` there. When it is open, this function renders
 * [FieldReportConsentScreen] itself instead, so [content] is never composed underneath it.
 */
@Composable
private fun FieldReportHost(
    context: Context,
    modifier: Modifier,
    content: @Composable (FieldReportSectionViewState?, FieldReportSectionActions) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val fieldReportSettingsStore = remember {
        SharedPreferencesFieldReportSettingsStore(
            context.getSharedPreferences(SharedPreferencesFieldReportSettingsStore.PREFS_NAME, Context.MODE_PRIVATE),
        )
    }
    // Bumped after every write so the plain `SharedPreferences` write below (which Compose has no
    // observer on) still triggers a recomposition — the same idiom `storeVersion` uses one level
    // up in `SettingsContent`.
    var fieldReportGuardVersion by remember { mutableStateOf(0) }
    var fieldReportConsentOpen by remember { mutableStateOf(false) }
    // AC-144: a fresh `FieldReportToggleState()` every time the consent screen opens — `remember`
    // keyed on `fieldReportConsentOpen` so re-opening after a `Cancel`/`Send` starts from every
    // toggle off again, never from whatever the operator picked last time.
    var fieldReportToggles by remember(fieldReportConsentOpen) { mutableStateOf(FieldReportToggleState()) }
    var fieldReportPreview by remember(fieldReportConsentOpen) { mutableStateOf<FieldReportBundlePreview?>(null) }
    var fieldReportDestination by remember(fieldReportConsentOpen) {
        mutableStateOf<FieldReportDestination?>(null)
    }
    // WPR3 (FR-OBS-11/FR-OBS-12): `null` is the honest "not configured" state
    // `SettingsContributeScreen.kt` already uses for its own not-yet-built upload client, never a
    // fabricated destination — see `FieldReportUploadClientFactory`'s own doc comment for exactly
    // when that is (a release build, or a debug build with no token in the environment).
    val fieldReportClient: FieldReportUploadClient? = remember { FieldReportUploadClientFactory.create() }

    LaunchedEffect(fieldReportConsentOpen, fieldReportToggles) {
        if (fieldReportConsentOpen) {
            fieldReportPreview = FieldReportBundleBuilder.preview(context, fieldReportToggles.toCategorySet())
            fieldReportDestination = fieldReportClient?.destination()
        }
    }

    if (!fieldReportConsentOpen) {
        val fieldReport = if (BuildConfig.DEBUG) {
            FieldReportSectionViewState(
                publicGuardEnabled = run {
                    fieldReportGuardVersion // see this function's own doc comment on the field above
                    fieldReportSettingsStore.publicDestinationGuardEnabled
                },
            )
        } else {
            null
        }
        content(
            fieldReport,
            FieldReportSectionActions(
                onOpenFieldReport = { fieldReportConsentOpen = true },
                onSetPublicDestinationGuardEnabled = {
                    fieldReportSettingsStore.publicDestinationGuardEnabled = it
                    fieldReportGuardVersion++
                },
            ),
        )
        return
    }

    val preview = fieldReportPreview
    if (preview == null) {
        LoadingSettings(modifier = modifier)
        return
    }
    FieldReportConsentScreen(
        state = fieldReportConsentViewState(
            preview = preview,
            destination = fieldReportDestination,
            guardEnabled = fieldReportSettingsStore.publicDestinationGuardEnabled,
            toggles = fieldReportToggles,
        ),
        onToggleRetainedAudio = { fieldReportToggles = fieldReportToggles.copy(retainedAudio = it) },
        onToggleVoiceprintEmbeddings = { fieldReportToggles = fieldReportToggles.copy(voiceprintEmbeddings = it) },
        onToggleScreenFrames = { fieldReportToggles = fieldReportToggles.copy(screenFrames = it) },
        onCancel = { fieldReportConsentOpen = false },
        onSend = {
            val client = fieldReportClient
            if (client != null) {
                scope.launch {
                    uploadFieldReportBundle(context, client, fieldReportToggles.toCategorySet())
                    fieldReportConsentOpen = false
                }
            }
        },
        // WPR3 lands the real `:net`-backed `FieldReportUploadClient` (see that interface's own
        // doc comment) — `Send` becomes reachable the moment `fieldReportClient` above stops being
        // `null`, with no further change here.
        canSend = fieldReportClient != null,
        modifier = modifier,
    )
}

/** [FieldReportToggleState] → the [FieldReportGatedCategory] set [FieldReportBundleBuilder] reads —
 * this file's own UI-to-domain mapping, kept here rather than on the data class itself so
 * `SettingsViewData.kt` stays a plain view-state file with no bundle-building knowledge. */
private fun FieldReportToggleState.toCategorySet(): Set<FieldReportGatedCategory> = buildSet {
    if (retainedAudio) add(FieldReportGatedCategory.RETAINED_AUDIO)
    if (voiceprintEmbeddings) add(FieldReportGatedCategory.VOICEPRINT_EMBEDDINGS)
    if (screenFrames) add(FieldReportGatedCategory.SCREEN_FRAMES)
}

/** [FieldReportHost]'s own `FieldReportConsentViewState` construction, split out purely to keep
 * that function under detekt's length limit.
 *
 * FR-OBS-10 / constitution I: [FieldReportDestinationVisibility.UNKNOWN] — the destination could
 * not be read at upload time — maps to `destinationPublic = true`, exactly like a confirmed
 * [FieldReportDestinationVisibility.PUBLIC]. `FieldReportGuard.gatedCategoriesAllowed` (this
 * round's own file-ownership map puts that file out of reach) takes a plain `Boolean` and refuses
 * the gated categories whenever it is `true` and the guard switch is on — feeding it `true` on
 * "unknown" is what makes this refuse rather than silently trust a guess in the dangerous
 * direction, without needing to touch that file at all. */
private fun fieldReportConsentViewState(
    preview: FieldReportBundlePreview,
    destination: FieldReportDestination?,
    guardEnabled: Boolean,
    toggles: FieldReportToggleState,
): FieldReportConsentViewState = FieldReportConsentViewState(
    files = preview.entries.map {
        FieldReportConsentFileViewState(it.fileName, formatFieldReportSize(it.sizeBytes), it.category)
    },
    totalSizeLabel = formatFieldReportSize(preview.totalBytes),
    destinationKnown = destination != null,
    destinationLabel = fieldReportDestinationLabel(destination),
    destinationPublic = destination?.visibility != FieldReportDestinationVisibility.PRIVATE,
    publicGuardEnabled = guardEnabled,
    toggles = toggles,
)

/** `null` keeps WPR2's own "not configured" copy; a destination whose visibility could not be
 * determined says so plainly rather than silently showing it as though it were a confirmed public
 * or private repository (constitution I: uncertainty is content, not something to paper over). */
private fun fieldReportDestinationLabel(destination: FieldReportDestination?): String = when {
    destination == null -> "No upload destination is configured in this build"
    destination.visibility == FieldReportDestinationVisibility.UNKNOWN ->
        "${destination.label} (visibility could not be confirmed)"
    else -> destination.label
}

/** [FieldReportHost]'s own `Send` action — split out purely to keep that function under detekt's
 * length limit. Builds the real zip via [FieldReportBundleBuilder.write] (never a second, drifting
 * render) and hands it to [client] exactly as [categories] named it.
 *
 * FR-OBS-11: reads [CaptureState.isCapturing] fresh, at the moment of send, and carries it as
 * [FieldReportUploadRequest.captureActive] — the one caller in this codebase satisfying that
 * parameter's own contract (see its doc comment in `:core` for what this obligation is, and is
 * not, enforced by). */
private suspend fun uploadFieldReportBundle(
    context: Context,
    client: FieldReportUploadClient,
    categories: Set<FieldReportGatedCategory>,
) {
    val bytes = ByteArrayOutputStream().also { out ->
        FieldReportBundleBuilder.write(context, out, categories)
    }.toByteArray()
    client.upload(
        FieldReportUploadRequest(
            bundle = bytes,
            fileName = "field-report-${LocalDate.now()}.zip",
            categoriesIncluded = categories.mapTo(mutableSetOf()) { it.toUploadCategory() },
            captureActive = CaptureState.isCapturing,
            deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}",
            buildLabel = BuildConfig.VERSION_NAME,
            commitLabel = BuildConfig.GIT_SHORT_COMMIT,
        ),
    )
}

/** [FieldReportGatedCategory] (`:app`) -> [FieldReportUploadCategory] (`:core`) — the 1:1 mapping
 * this file's own call site performs because `:core` cannot depend on `:app` (see
 * [FieldReportUploadCategory]'s own doc comment). */
private fun FieldReportGatedCategory.toUploadCategory(): FieldReportUploadCategory = when (this) {
    FieldReportGatedCategory.RETAINED_AUDIO -> FieldReportUploadCategory.RETAINED_AUDIO
    FieldReportGatedCategory.VOICEPRINT_EMBEDDINGS -> FieldReportUploadCategory.VOICEPRINT_EMBEDDINGS
    FieldReportGatedCategory.SCREEN_FRAMES -> FieldReportUploadCategory.SCREEN_FRAMES
}

/** The same "MB above 1, else KB" shape `SettingsPolling.kt`'s own `formatDiagnosticsSize` uses for
 * the FR-OBS-3 bundle — kept as this file's own small copy rather than a call into that `private`
 * function, since `SettingsPolling.kt` is outside this round's own file-ownership map. `Locale.ROOT`
 * throughout, the same guard against a comma-decimal locale that function's own history exists to
 * avoid repeating (constitution II: never assert this exact string in a test either). */
private fun formatFieldReportSize(bytes: Long): String {
    val mb = bytes / 1_000_000.0
    val kb = bytes / 1_000.0
    return if (mb >= 1.0) "%.1f MB".format(Locale.ROOT, mb) else "%.0f KB".format(Locale.ROOT, kb)
}

/** The real name of the document the Storage Access Framework picker actually created — never the
 * suggested name assumed unchanged, since a user can rename it in the picker itself. */
private fun realFileName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) return cursor.getString(nameIndex)
    }
    return uri.lastPathSegment ?: "the diagnostics bundle"
}

private fun applyContributeToggle(store: SettingsStore, index: Int, value: Boolean) {
    when (index) {
        0 -> store.contributeAudioOfLabelled = value
        1 -> store.contributeTranscriptsAndCorrections = value
        2 -> store.contributeRejectedSegments = value
        3 -> store.contributeResolverStatistics = value
    }
    store.contributionEnabled = store.contributeAudioOfLabelled ||
        store.contributeTranscriptsAndCorrections ||
        store.contributeRejectedSegments ||
        store.contributeResolverStatistics
}

@Composable
private fun LoadingSettings(modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(OrtSpacing.lg)) {
        Text(text = "Loading…", modifier = Modifier.semantics { contentDescription = "Loading settings" })
    }
}
