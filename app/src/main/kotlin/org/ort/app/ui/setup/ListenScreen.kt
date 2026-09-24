package org.ort.app.ui.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType

/**
 * **P39 (D58, AC-198): the one screen that carries the whole audio decision.**
 *
 * `INPUT`, `VERIFY` and `LEVEL` were three steps of a twelve-step wizard. They are one screen now,
 * and the reason is not brevity for its own sake: they are one task with one failure mode, and that
 * failure mode is the only *silent* one in the flow. Constitution IV lives here — a route that is not
 * the selected device halts capture, and recording the room instead of the radio is the
 * highest-consequence silent failure in the system — so D58 made this "the only screen that must be
 * full-screen", and then spent the attention the three departing screens were consuming on it.
 *
 * Three sections, in the order the work happens:
 * 1. [InputSection] — the enumerated routes, with the mode's preset chip.
 * 2. [VerifySection] — the four-stage route check, expanding in place once it is running (never a
 *    screen of its own to navigate to and back from).
 * 3. [LevelSection] — the live meter and the gain slider, shown only once the route has actually
 *    verified, which is also the first moment a level reading means anything.
 *
 * **What did not move, and must not.** A [RouteCheckState.Mismatch] still leaves this screen for
 * [RouteMismatchScreen]: that is the one deliberate hard halt in the product, and merging Verify in
 * here changed where the check is *shown*, never whether it blocks. [onContinue] is still only ever
 * invoked once [ListenViewState.inputVerified] is true (AC-201's own closing sentence: "an unverified
 * audio route still never proceeds — the block is communicated, not removed").
 *
 * **AC-201**: no primary here is ever disabled for validation state. It stays lit and focusable, and a
 * tap that cannot proceed says specifically what is missing through [SetupValidationNotice].
 */
@Composable
public fun ListenScreen(
    state: ListenViewState,
    actions: ListenActions,
    onBack: (() -> Unit)? = null,
    totalSteps: Int = SETUP_STEPS_WITHOUT_DOWNLOAD,
    onExitToApp: (() -> Unit)? = null,
) {
    SetupScaffold(
        step = SetupStep.LISTEN,
        totalSteps = totalSteps,
        title = "Find the radio's audio",
        subtitle = "Pick the input, prove it, then set the level.",
        onBack = onBack,
        onExitToApp = onExitToApp,
        titleTrailing = {
            TextAction(
                text = "Refresh",
                onClick = actions.onRefresh,
                modifier = Modifier.testTag("setup-input-refresh"),
            )
        },
        bottomActions = { ListenBottomActions(state = state, actions = actions) },
    ) {
        SectionLabel(text = "Inputs", testTag = "setup-listen-section-inputs")
        InputSection(
            routes = state.routes,
            selectedId = state.selectedId,
            presetLabel = state.presetLabel,
            presetUnavailableText = state.presetUnavailableText,
            onSelect = actions.onSelect,
        )
        if (state.checkRunning || state.check != null || state.inputVerified) {
            SectionLabel(text = "Route check", testTag = "setup-listen-section-route-check")
            VerifySection(inputLabel = state.inputLabel, check = state.check)
        }
        if (state.inputVerified) {
            SectionLabel(text = "Level", testTag = "setup-listen-section-level")
            LevelSection(state = state.level, gainDb = state.gainDb, onGainChange = actions.onGainChange)
        }
    }
}

/**
 * Everything the operator can do on [ListenScreen], bundled — the same shape [ReadyActions] already
 * establishes in this package, and for the same reason: merging three screens into one merged their
 * callbacks too, and seven of them in a parameter list is a signal rather than a formality. Grouping
 * them also says the true thing about this screen, which is that it is one task with several
 * affordances rather than three screens sharing a scaffold.
 */
public data class ListenActions(
    val onSelect: (String) -> Unit,
    val onRefresh: () -> Unit,
    val onVerify: () -> Unit,
    val onContinue: () -> Unit,
    val onTryAgain: () -> Unit,
    val onChooseAnotherInput: () -> Unit,
    val onGainChange: (Int) -> Unit = {},
)

/** The three sections' own headings — the guide's `sectionLabel`, and the only thing that tells an
 * operator scrolling this screen which of the three jobs they are looking at. */
@Composable
private fun SectionLabel(text: String, testTag: String) {
    Text(
        text = text.uppercase(),
        style = OrtType.sectionLabel,
        color = OrtColors.textFaint,
        modifier = Modifier.testTag(testTag),
    )
}

/**
 * [ListenScreen]'s own `bottomActions` slot. Four states, one primary, never disabled for validation
 * (AC-201):
 * - **nothing checked yet** — `Verify this input`; a tap with no route chosen says which of the two
 *   real problems it is ([inputValidationMessage]).
 * - **a check running** — `Continue`, lit; a tap says [VERIFY_NOT_PASSED_YET] and does not advance.
 *   Constitution IV is enforced here, in the tap, not by greying the control out of the focus order.
 * - **timed out** — `Try again`, with `Choose another input` beneath it (R-284: an operator who
 *   already knows the wrong device is selected must not have to sit out the full 30 s first).
 * - **verified** — `Continue`, which actually continues.
 */
@Composable
private fun ColumnScope.ListenBottomActions(state: ListenViewState, actions: ListenActions) {
    var refusal by remember { mutableStateOf<String?>(null) }
    val timedOut = state.check is RouteCheckState.TimedOut
    SetupValidationNotice(message = refusal, modifier = Modifier.testTag("setup-listen-validation"))
    when {
        timedOut && !state.inputVerified -> {
            PrimaryButton(
                text = "Try again",
                onClick = {
                    refusal = null
                    actions.onTryAgain()
                },
                modifier = Modifier.fillMaxWidth().testTag("setup-verify-try-again"),
            )
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextAction(
                    text = "Choose another input",
                    onClick = actions.onChooseAnotherInput,
                    modifier = Modifier.testTag("setup-verify-choose-another"),
                )
            }
        }
        state.inputVerified -> PrimaryButton(
            text = "Continue",
            onClick = {
                refusal = null
                actions.onContinue()
            },
            modifier = Modifier.fillMaxWidth().testTag("setup-listen-continue"),
        )
        state.checkRunning || state.check != null -> {
            PrimaryButton(
                text = "Continue",
                onClick = { refusal = VERIFY_NOT_PASSED_YET },
                modifier = Modifier.fillMaxWidth().testTag("setup-listen-continue"),
            )
            ChooseADifferentInputLink(onClick = actions.onChooseAnotherInput)
        }
        else -> PrimaryButton(
            text = "Verify this input",
            onClick = {
                val missing = inputValidationMessage(state.routes, state.selectedId)
                if (missing == null) {
                    refusal = null
                    actions.onVerify()
                } else {
                    refusal = missing
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("setup-input-verify"),
        )
    }
}

/** R-284's escape, as a lone `Text` rather than [TextAction] — the board's own colour for this link is
 * [OrtColors.textMuted], noticeably dimmer than [TextAction]'s fixed accent green, which has no
 * enabled-but-muted mode of its own. `Role.Button` and a real 44 dp target either way. */
@Composable
private fun ChooseADifferentInputLink(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .clickable(onClickLabel = "Choose a different input", role = Role.Button, onClick = onClick)
                .testTag("setup-verify-choose-different"),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "Choose a different input", style = OrtType.textAction, color = OrtColors.textMuted)
        }
    }
}
