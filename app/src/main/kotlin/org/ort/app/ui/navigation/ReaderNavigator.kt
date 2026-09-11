package org.ort.app.ui.navigation

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import org.ort.app.ui.settings.SettingsScreenId
import org.ort.app.ui.setup.SetupActivity
import org.ort.app.ui.setup.SetupStep

/**
 * A navigation handle hoisted out of [OrtNavHost] (ui-conformance-plan WP3, round 3) so code
 * mounted *above* the nav host in composition — [org.ort.app.ui.ReaderActivity]'s
 * `FailureHostActions`, specifically — can still drive it: switch the current drawer destination,
 * or launch WP9's `SetupActivity`. [currentState] is exactly the `rememberSaveable` state
 * `OrtNavHost` used to own privately; [rememberReaderNavigator] creates it once, and `OrtNavHost`
 * now reads/writes it through this object instead of its own local `var` — save/restore behaviour
 * is unchanged, just relocated so a caller outside `OrtNavHost`'s own composition can reach it too.
 *
 * [settingsScreenState] (round 5): WP10 merged `SettingsContent(..., initialScreen:
 * SettingsScreenId? = null)` (confirmed by reading `ui/settings/SettingsContent.kt` before wiring
 * this) — a real "land on this sub-screen" entry that the round-3 `openSettingsStorage` (now
 * removed) could not reach. [openSettings] is the new, general call this state backs.
 *
 * [drawerOpenState] (R-803, coordinator-approved cross-boundary this round): mirrors
 * [OrtNavHost]'s own local `DrawerState.isOpen`, the one live fact this handle did not already
 * expose — `ScreenshotTourActivity` (`app/src/debug`) polls it, alongside [currentState], to prove
 * a just-composed screen has actually settled to what a tour step asked for before capturing,
 * rather than capturing on elapsed time alone (the bug class behind a `@2x` step's screenshot
 * showing the still-open drawer over the right destination underneath it).
 *
 * [reviewSessionViewState] (R-840, same round): mirrors `OrtNavHost`'s own local
 * `NavHostNavState.reviewSessionView` — `Earlier nights`' `Session`/`Digest` fact, the second live
 * value the tour's own settle-wait needs alongside [drawerOpenState] once a step's own seed carries
 * `pendingReviewSessionId` (a `DG05`/`DG01` step landing on the still-composing `Session` screen,
 * captured before `Digest` finished swapping in, is the identical race class `drawerOpenState`
 * exists to close).
 */
public class ReaderNavigator internal constructor(
    internal val currentState: MutableState<ReaderDestination>,
    internal val settingsScreenState: MutableState<SettingsScreenId?>,
    internal val drawerOpenState: MutableState<Boolean>,
    internal val reviewSessionViewState: MutableState<ReviewSessionView>,
    private val context: Context,
) {
    /** Switches the drawer's current destination — the same effect as tapping its drawer row. */
    public fun open(destination: ReaderDestination) {
        currentState.value = destination
    }

    /**
     * Opens `Settings`, landing directly on [screen] (`null`, the default, opens the root — the
     * same "opens there on launch, not always jumps there" contract `SettingsContent.initialScreen`
     * itself documents: this only takes effect while `Settings` is not already the current
     * destination, since re-entering it is what gives that composable's own `initialScreen` read a
     * fresh first composition — see `OrtNavHost.kt`'s own doc comment on why that is sound). Round
     * 5 (register R-139/F6/F9): replaces [openSettingsStorage], which could only ever reach the
     * root; every call site that used it now names the real sub-screen it means instead.
     */
    public fun openSettings(screen: SettingsScreenId? = null) {
        settingsScreenState.value = screen
        currentState.value = ReaderDestination.SETTINGS
    }

    /**
     * "Choose another input" (F3/F16 — route mismatch, USB permission denied) names Setup's
     * `Input` step as its real destination. WP9 merged `SetupActivity.EXTRA_STEP` for exactly this
     * (ui-conformance-plan WP9, round 3 — confirmed by reading `ui/setup/SetupActivity.kt`'s own
     * doc comment before wiring this, which names this exact call site): the requested step is
     * honored only when the store's own gates before it are already satisfied, so this reliably
     * reaches `Input` once setup has completed at least that far (true for the failure this backs,
     * which only fires *during a running session*) and otherwise falls back to `SetupStateMachine`'s
     * ordinary resume point rather than skipping ahead of an unmet gate — never a way around a
     * verification the guide requires. Round 5: "Set the frequency by hand" no longer routes here —
     * see [openSettings]'s call site in `ReaderActivity.kt` for where it goes now that `Settings-
     * Capture`'s manual-frequency row (`SettingsCaptureScreen.kt`'s "Log overs against, MHz") is a
     * real, reachable destination instead.
     */
    public fun openSetupInput() {
        context.startActivity(
            Intent(context, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_STEP, SetupStep.INPUT.name),
        )
    }
}

/**
 * Creates and remembers the [ReaderNavigator] [OrtNavHost] — and whatever is mounted above it in
 * the same composition, such as [org.ort.app.ui.ReaderActivity]'s `FailureHostActions` — shares
 * for the lifetime of this composition. [initialDestination] seeds the drawer's starting
 * destination once (a fresh `rememberSaveable`, so a later change to this parameter after the
 * first composition has no effect — matching "opens there on launch", not "always jumps there").
 * [initialSettingsScreen] (round 5) does the same for [ReaderNavigator.openSettings]'s sub-screen —
 * [org.ort.app.ui.ReaderActivity]'s `EXTRA_SETTINGS_SCREEN` is its one caller today, for Setup
 * S12's `Install` action (`EXTRA_DESTINATION=SETTINGS` alongside it); only meaningful when
 * [initialDestination] is itself `SETTINGS`, exactly as `SettingsContent.initialScreen` is only
 * consulted while that composable is the one showing.
 *
 * [seed] (round 13, WP12's screenshot-tour seam): when non-null and it implies a destination
 * ([NavSeed.initialDestination]), that destination — and, for `Settings`, [NavSeed.settingsScreen]
 * — wins over [initialDestination]/[initialSettingsScreen], the same "seeded state takes over
 * entirely, never merges with the ordinary defaults" contract [OrtNavHost]'s own `seed` uses for
 * `NavHostNavState`. `null` (the default) changes nothing here — every existing caller keeps
 * compiling and behaving unchanged.
 */
@Composable
public fun rememberReaderNavigator(
    initialDestination: ReaderDestination = ReaderDestination.NOW,
    initialSettingsScreen: SettingsScreenId? = null,
    seed: NavSeed? = null,
    context: Context = LocalContext.current,
): ReaderNavigator {
    val current = rememberSaveable { mutableStateOf(seed?.initialDestination() ?: initialDestination) }
    val settingsScreen = rememberSaveable { mutableStateOf(seed?.settingsScreen ?: initialSettingsScreen) }
    // R-803: never `rememberSaveable` — the drawer's own open/closed fact is `OrtNavHost`'s own
    // `DrawerState` to own, mirrored in every time `OrtNavHost` itself (re)composes, never restored
    // independently across a process death (a restored "open" with no drawer composed yet to match
    // it would be a stale, unearned fact).
    val drawerOpen = remember { mutableStateOf(seed?.openDrawer == true) }
    // R-840: same reasoning as `drawerOpen` above — never `rememberSaveable`, `OrtNavHost`'s own
    // `NavHostNavState.reviewSessionView` (itself `rememberSaveable`) is the one source of truth;
    // this is only ever a live mirror of it.
    val reviewSessionView = remember { mutableStateOf(seed?.reviewSessionView ?: ReviewSessionView.SESSION) }
    return remember(context) { ReaderNavigator(current, settingsScreen, drawerOpen, reviewSessionView, context) }
}
