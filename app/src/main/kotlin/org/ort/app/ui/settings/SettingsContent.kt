package org.ort.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import org.ort.app.diagnostics.DiagnosticsBundleBuilder
import org.ort.app.ui.data.RealCaptureModeFacts
import org.ort.app.ui.setup.SetupActivity
import org.ort.app.ui.setup.SetupStep
import org.ort.app.ui.theme.OrtSpacing
import org.ort.core.capture.CaptureMode
import java.time.LocalDate

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
            onOpenModeSettings = { screen = SettingsScreenId.MODE },
            modifier = modifier,
            crossPackage = SettingsCrossPackageActions(
                onOpenLevelMeter = onOpenLevelMeter,
                onReviewSession = onReviewSession,
            ),
        )
    }
}

/** The nine sub-screens, split out of [SettingsContent] purely to keep that function under
 * detekt's length/complexity limits — the same reason `OrtNavHost`'s own `DestinationContent` was
 * extracted before this package existed. [crossPackage] bundles the two cross-package drill-in
 * callbacks (see [SettingsCrossPackageActions]'s own doc comment) purely to keep this function's
 * own parameter list under the same threshold. */
@Composable
private fun SettingsSubScreen(
    context: Context,
    screen: SettingsScreenId,
    store: SettingsStore,
    storeVersion: Int,
    onStoreChanged: () -> Unit,
    onBack: () -> Unit,
    onOpenModeSettings: () -> Unit,
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
            onOpenModeSettings = onOpenModeSettings,
        )

        SettingsScreenId.RIG -> SettingsRigScreen(
            state = remember(storeVersion) { SettingsPolling.rig() },
            onBack = onBack,
            modifier = modifier,
            // TODO(WPC2): a real reconnect entry point does not exist yet (no rig-supervision call
            // site anywhere in `:pipeline` today — grepped before writing this). Until it lands,
            // `Reconnect` re-reads `RigStatus` fresh via the same `onStoreChanged` bump every other
            // real write in this file already uses, which is honest (this screen never claims to
            // have reconnected anything) rather than a no-op button with nothing behind it at all.
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
            store = store,
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
 * Picking a mode while a session is live records it as pending
 * ([SettingsStore.pendingCaptureModeName] — see that property's own doc comment for exactly why
 * this, and not [org.ort.app.ui.data.CaptureModeFacts], is the write path) and leaves the banner
 * showing; picking one while idle re-enters setup at `MODE` (WPD's still-in-flight step name — see
 * [SettingsContent]'s own class kdoc for why a plain string, not `SetupStep.MODE`, is passed: that
 * step does not exist on `main` yet, and an unrecognised [SetupActivity.EXTRA_STEP] value is a
 * documented no-op there, so this call site is forward-compatible and inert today). `Change` on
 * either "what the mode set" row re-enters the step that row's own axis is decided by.
 */
@Composable
private fun SettingsModeSubScreen(
    context: Context,
    store: SettingsStore,
    storeVersion: Int,
    onStoreChanged: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    var modeState by remember { mutableStateOf<SettingsModeViewState?>(null) }
    LaunchedEffect(storeVersion) {
        modeState = SettingsPolling.modeScreen(context, store, RealCaptureModeFacts(context))
    }
    val state = modeState
    if (state != null) {
        SettingsModeScreen(
            state = state,
            onBack = onBack,
            onSelectMode = { mode ->
                if (state.sessionLive) {
                    store.pendingCaptureModeName = mode.name
                    onStoreChanged()
                } else {
                    openSetupAtStep(context, SETUP_STEP_MODE)
                }
            },
            onChangeAudioRoute = { openSetupAtStep(context, SetupStep.INPUT.name) },
            onChangeRigLink = { openSetupAtStep(context, SETUP_STEP_RIG_TRANSPORT) },
            modifier = modifier,
        )
    } else {
        LoadingSettings(modifier = modifier)
    }
}

/** WPD's setup steps for capture mode/rig-transport re-entry (`spec/e2e-capture-modes-plan.md`
 * §WPD) — not yet added to `SetupStep` on `main`. Plain string literals, not `SetupStep.MODE.name`/
 * `SetupStep.RIG_TRANSPORT.name` (which do not compile against today's enum): `SetupActivity`'s own
 * `tryOpenAtRequestedStep` already falls back to its ordinary `refreshStep()` for any name it does
 * not recognise (confirmed by reading `SetupActivity.kt` before wiring this), so these two literals
 * are inert, harmless no-ops today and become real re-entry points the moment WPD's branch adds the
 * matching `SetupStep` entries — no second edit needed here when that lands. */
private const val SETUP_STEP_MODE: String = "MODE"
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
 * CF02 (amended 2026-09-10): `SettingsPolling.capture` became `suspend` (the leading Capture-mode
 * row now reads through [org.ort.app.ui.data.CaptureModeFacts], real `:data` I/O) — the same
 * `LaunchedEffect`-backed async-load shape [SettingsStorageSubScreen] already uses, keyed on
 * [storeVersion] so picking a mode (which bumps it via [onStoreChanged]) re-reads the row.
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
    var captureState by remember { mutableStateOf<SettingsCaptureViewState?>(null) }
    LaunchedEffect(storeVersion) { captureState = SettingsPolling.capture(context, store) }
    val state = captureState
    if (state == null) {
        LoadingSettings(modifier = modifier)
        return
    }
    SettingsCaptureScreen(
        state = state,
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
    if (state != null) {
        SettingsDiagnosticsScreen(
            state = state,
            onBack = onBack,
            onPreview = { previewOpen = true },
            onSaveBundle = { saveLauncher.launch("diagnostics-${LocalDate.now()}.zip") },
            previewOpen = previewOpen,
            onDismissPreview = { previewOpen = false },
            saveConfirmationLabel = saveConfirmationLabel,
            modifier = modifier,
        )
    } else {
        LoadingSettings(modifier = modifier)
    }
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
