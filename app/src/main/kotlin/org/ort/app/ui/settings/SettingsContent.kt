package org.ort.app.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.ort.app.ui.setup.SetupActivity
import org.ort.app.ui.setup.SetupStep
import org.ort.app.ui.theme.OrtSpacing

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
            onOpenLevelMeter = onOpenLevelMeter,
        )
    }
}

/** The nine sub-screens, split out of [SettingsContent] purely to keep that function under
 * detekt's length/complexity limits — the same reason `OrtNavHost`'s own `DestinationContent` was
 * extracted before this package existed. */
@Composable
private fun SettingsSubScreen(
    context: Context,
    screen: SettingsScreenId,
    store: SettingsStore,
    storeVersion: Int,
    onStoreChanged: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
    onOpenLevelMeter: () -> Unit,
) {
    when (screen) {
        SettingsScreenId.CAPTURE -> SettingsCaptureSubScreen(
            context = context,
            store = store,
            storeVersion = storeVersion,
            onStoreChanged = onStoreChanged,
            onBack = onBack,
            modifier = modifier,
            onOpenLevelMeter = onOpenLevelMeter,
        )

        SettingsScreenId.RIG -> SettingsRigScreen(
            state = remember(storeVersion) { SettingsPolling.rig() },
            onBack = onBack,
            modifier = modifier,
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

        SettingsScreenId.STORAGE -> SettingsStorageScreen(
            state = remember(storeVersion) { SettingsPolling.storage(context, store) },
            onBack = onBack,
            onSetBudgetGb = {
                store.audioBudgetGb = it
                onStoreChanged()
            },
            onToggleAutoPrune = {
                store.autoPruneEnabled = it
                onStoreChanged()
            },
            modifier = modifier,
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

        SettingsScreenId.DIAGNOSTICS -> SettingsDiagnosticsScreen(
            state = remember { SettingsPolling.diagnostics() },
            onBack = onBack,
            modifier = modifier,
        )

        SettingsScreenId.ABOUT -> SettingsAboutScreen(
            state = remember { SettingsPolling.about(context) },
            onBack = onBack,
            modifier = modifier,
        )
    }
}

/** The `CAPTURE` branch of [SettingsSubScreen], split out purely to keep that function's own
 * length under detekt's limit — the same reason [SettingsSubScreen] itself was split out of
 * [SettingsContent]. */
@Composable
private fun SettingsCaptureSubScreen(
    context: Context,
    store: SettingsStore,
    storeVersion: Int,
    onStoreChanged: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
    onOpenLevelMeter: () -> Unit,
) {
    SettingsCaptureScreen(
        state = remember(storeVersion) { SettingsPolling.capture(store) },
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
        onEditManualFrequency = {
            store.manualFrequencyMhz = it
            onStoreChanged()
        },
        modifier = modifier,
    )
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
