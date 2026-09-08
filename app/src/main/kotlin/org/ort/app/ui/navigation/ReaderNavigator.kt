package org.ort.app.ui.navigation

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
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
 */
public class ReaderNavigator internal constructor(
    internal val currentState: MutableState<ReaderDestination>,
    private val context: Context,
) {
    /** Switches the drawer's current destination — the same effect as tapping its drawer row. */
    public fun open(destination: ReaderDestination) {
        currentState.value = destination
    }

    /**
     * FR-STO-3/R-105's "Free up space"/"Retention" actions on a storage warning or halt. WP10's
     * `SettingsContent` (register R-090) has no external "land on this sub-screen" entry — its own
     * `screen` state is entirely internal (confirmed by reading `ui/settings/SettingsContent.kt`
     * before writing this) — so this can only open the `Settings` *root*, not its `Storage`
     * sub-screen directly; the operator taps `Storage` from there themselves. Named separately
     * from [open] so that gap stays visible at every call site, not just in a comment — the same
     * treatment [org.ort.app.ui.screens.NowContent]'s `onOpenModels` gets for the `Assets`
     * sub-screen, which has the identical limitation.
     */
    public fun openSettingsStorage() {
        currentState.value = ReaderDestination.SETTINGS
    }

    /**
     * "Choose another input" (F3/F16 — route mismatch, USB permission denied) and "Set the
     * frequency by hand" both name Setup's `Input` step as their real destination. WP9 merged
     * `SetupActivity.EXTRA_STEP` for exactly this (ui-conformance-plan WP9, round 3 — confirmed by
     * reading `ui/setup/SetupActivity.kt`'s own doc comment before wiring this, which names this
     * exact call site): the requested step is honored only when the store's own gates before it are
     * already satisfied, so this reliably reaches `Input` once setup has completed at least that far
     * (true for both actions above, which only fire *during a running session*) and otherwise falls
     * back to `SetupStateMachine`'s ordinary resume point rather than skipping ahead of an unmet
     * gate — never a way around a verification the guide requires.
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
 */
@Composable
public fun rememberReaderNavigator(
    initialDestination: ReaderDestination = ReaderDestination.NOW,
    context: Context = LocalContext.current,
): ReaderNavigator {
    val current = rememberSaveable { mutableStateOf(initialDestination) }
    return remember(context) { ReaderNavigator(current, context) }
}
