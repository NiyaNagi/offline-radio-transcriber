package org.ort.app.ui.setup

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.InProgressRing
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType
import java.util.Locale

/**
 * S05 (`Setup-Verify.dc.html`, R-081) — the four checks progressing in board order, driven by
 * [RouteCheck]. `Continue` (guide §6.10: a setup primary stays disabled until its verify passes)
 * enables only on [RouteCheckState.Passed]; a [RouteCheckState.Mismatch] hands off to
 * [RouteMismatchScreen] instead (this screen never renders the halt itself — [SetupActivity]
 * switches screens on that state).
 *
 * Validator finding (register R-120..R-125, halt): [RouteCheckState.TimedOut] used to fall through
 * to no branch at all — every check icon reverted to unfilled (the `else -> emptySet()` below), the
 * screen offered no honest explanation, `Continue` stayed disabled forever, and [onBack] was
 * unconditionally `null`, trapping the operator with no way out at all. Now: the two stages that
 * genuinely completed before the 30 s listening window ran out
 * ([RealRouteCheck]'s own flow — `NATIVE_RATE`/`ROUTE_MATCH` always precede the signal wait) stay
 * ticked, the signal check shows an honest failed mark and "No signal heard in 30 s on
 * `<device>`", `Continue` is replaced by `Try again`/`Choose another input` (the same recovery
 * [RouteMismatchScreen] already offers for a route mismatch — this is the same kind of stuck state,
 * just discovered differently), and [onBack] is now always supplied — a genuinely halted
 * verification is exactly the situation where "the back chevron must always exist" matters most.
 *
 * R-284 (validator pass 3): `Setup-Verify.dc.html` draws a `Choose a different input` link under
 * the disabled `Continue` for the whole time a check is still in progress (up to the 30 s signal
 * wait), not only after [RouteCheckState.TimedOut] — an operator who already knows the wrong
 * device is selected should not have to sit out the full timeout first. Built directly here rather
 * than via [TextAction] (`Controls.kt`, WP2's file): the board's own colour for this link,
 * `oklch(0.66 0.008 250)`, is [OrtColors.textMuted] — noticeably dimmer than [TextAction]'s fixed
 * `accent/green`, which has no enabled-but-muted mode of its own to select.
 */
@Composable
public fun VerifyScreen(
    state: VerifyViewState,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    onTryAgain: () -> Unit,
    onChooseAnotherInput: () -> Unit,
) {
    val timedOut = state.check is RouteCheckState.TimedOut
    val passed = when (val check = state.check) {
        is RouteCheckState.InProgress -> check.passed
        is RouteCheckState.Passed -> RouteCheckStage.entries.toSet()
        RouteCheckState.TimedOut -> setOf(RouteCheckStage.NATIVE_RATE, RouteCheckStage.ROUTE_MATCH)
        else -> emptySet()
    }
    val allPassed = state.check is RouteCheckState.Passed

    SetupScaffold(
        step = SetupStep.VERIFY,
        title = "Verifying the route",
        subtitle = state.inputLabel,
        onBack = onBack,
        bottomActions = {
            VerifyBottomActions(timedOut, allPassed, onContinue, onTryAgain, onChooseAnotherInput)
        },
    ) {
        val facts = routeCheckFactsFrom(state.check)

        CheckRow(
            title = "Opened at native rate",
            detail = facts.nativeRateHz?.let { "${formatHzGrouped(it)} Hz · mono · 16-bit" },
            done = RouteCheckStage.NATIVE_RATE in passed,
            testTag = "setup-verify-check-native-rate",
        )
        CheckRow(
            title = "Routed device matches the one you chose",
            detail = facts.routedDeviceLabel?.let { "getRoutedDevice() → $it" },
            done = RouteCheckStage.ROUTE_MATCH in passed,
            testTag = "setup-verify-check-route-match",
        )
        CheckRow(
            title = "Listening for signal",
            detail = if (timedOut) {
                "No signal heard in 30 s on ${state.inputLabel}"
            } else {
                "Key the radio, or wait for traffic — up to 30 s"
            },
            done = RouteCheckStage.SIGNAL in passed,
            failed = timedOut,
            inProgress = state.check is RouteCheckState.InProgress && RouteCheckStage.SIGNAL !in passed,
            // R-943: the board's own live "0:11" counter -- only while genuinely still listening,
            // never a stale or fabricated value once the stage has already passed or failed.
            trailing = facts.elapsedListeningMillis?.takeIf { RouteCheckStage.SIGNAL !in passed }
                ?.let(::formatElapsed),
            testTag = "setup-verify-check-signal",
        )
        CheckRow(
            title = "Resampler identity recorded",
            detail = null,
            done = RouteCheckStage.RESAMPLER in passed,
            testTag = "setup-verify-check-resampler",
        )

        // R-943: the board's own Input waveform card -- real per-sample level history taken during
        // the SIGNAL listen (empty, honestly, before listening has started at all: an empty chart,
        // never a fabricated one, matching LevelScreen's own constitution-I rule for its meter).
        InputWaveformCard(
            bars = facts.levelBars,
            noiseFloorDbfs = facts.noiseFloorDbfs,
            signalHeard = RouteCheckStage.SIGNAL in passed,
        )
    }
}

/** [VerifyScreen]'s own `bottomActions` slot — split out purely to keep that composable under
 * detekt's length ceiling, no behaviour of its own beyond what it always drew inline. */
@Composable
private fun VerifyBottomActions(
    timedOut: Boolean,
    allPassed: Boolean,
    onContinue: () -> Unit,
    onTryAgain: () -> Unit,
    onChooseAnotherInput: () -> Unit,
) {
    if (timedOut) {
        PrimaryButton(
            text = "Try again",
            onClick = onTryAgain,
            modifier = Modifier.fillMaxWidth().testTag("setup-verify-try-again"),
        )
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextAction(
                text = "Choose another input",
                onClick = onChooseAnotherInput,
                modifier = Modifier.testTag("setup-verify-choose-another"),
            )
        }
    } else {
        PrimaryButton(
            text = "Continue",
            onClick = onContinue,
            enabled = allPassed,
            modifier = Modifier.fillMaxWidth().testTag("setup-verify-continue"),
        )
        // R-284: the same "wrong device, do not make me wait out the timeout" escape the board
        // offers for the whole in-progress window, not only once TimedOut arrives.
        if (!allPassed) {
            ChooseADifferentInputLink(onClick = onChooseAnotherInput)
        }
    }
}

/** The handful of real, optional facts [VerifyScreen]'s checklist/waveform card render — bundled
 * so deriving them from [state]'s [RouteCheckState] is one `when`, not five, keeping the composable
 * itself under detekt's complexity ceiling. Every field is `null`/empty for any state that never
 * carries it (constitution I — see [RouteCheckState.InProgress]'s own doc comment for why each
 * exists at all). */
private data class RouteCheckFacts(
    val nativeRateHz: Int?,
    val routedDeviceLabel: String?,
    val elapsedListeningMillis: Long?,
    val levelBars: List<Float>,
    val noiseFloorDbfs: Double?,
)

private fun routeCheckFactsFrom(check: RouteCheckState?): RouteCheckFacts = when (check) {
    is RouteCheckState.InProgress -> RouteCheckFacts(
        check.nativeRateHz,
        check.routedDeviceLabel,
        check.elapsedListeningMillis,
        check.levelBars,
        check.noiseFloorDbfs,
    )
    is RouteCheckState.Passed -> RouteCheckFacts(
        check.nativeRateHz,
        check.routedDeviceLabel,
        null,
        check.levelBars,
        check.noiseFloorDbfs,
    )
    else -> RouteCheckFacts(null, null, null, emptyList(), null)
}

/** `"48000"` → `"48 000"` (space-grouped thousands) — `Setup-Verify.dc.html`'s own formatting for
 * the native-rate detail line. `Locale.ROOT`'s own grouping separator is a comma, replaced with a
 * plain space to match the board rather than assuming any particular locale's own convention. */
internal fun formatHzGrouped(hz: Int): String = String.format(Locale.ROOT, "%,d", hz).replace(',', ' ')

/** Milliseconds elapsed listening, as the board's own `"0:11"` (minutes:seconds, zero-padded
 * seconds) — mirrors the mono, accent-green figure `Setup-Verify.dc.html` draws beside "Listening
 * for signal" while the wait is still live. */
internal fun formatElapsed(millis: Long): String {
    val totalSeconds = millis / MILLIS_PER_SECOND
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L

/** R-284's own doc comment (above, on [VerifyScreen]) explains why this is a lone `Text` rather
 * than [TextAction] — [OrtColors.textMuted], `Setup-Verify.dc.html`'s own colour for this link,
 * `Role.Button` + a real 44dp target either way. */
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

@Composable
private fun CheckRow(
    title: String,
    detail: String?,
    done: Boolean,
    testTag: String,
    modifier: Modifier = Modifier,
    inProgress: Boolean = false,
    failed: Boolean = false,
    trailing: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 13.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when {
                failed -> Icon(
                    imageVector = OrtIcons.dismiss,
                    contentDescription = "did not complete",
                    tint = OrtColors.haltOnFill,
                    modifier = Modifier
                        .size(20.dp)
                        .background(OrtColors.haltFill, CircleShape)
                        .padding(4.dp),
                )
                done -> Icon(
                    imageVector = OrtIcons.check,
                    contentDescription = "done",
                    tint = OrtColors.accentOnGreen,
                    modifier = Modifier
                        .size(20.dp)
                        .background(OrtColors.accentGreen, CircleShape)
                        .padding(4.dp),
                )
                inProgress -> InProgressRing(size = 20.dp, color = OrtColors.accentGreen, strokeWidth = 2.dp)
                // R-943 (register, reviewer A3 run 4a, design): this used to be a filled circle the
                // exact colour of the screen's own background -- invisible, not the board's own dim
                // *ring* (`border: 1.5px solid`, no fill) a not-yet-reached check draws. `textLow`
                // matches this row's own "pending" title colour just below, the same tone the board
                // itself uses for everything about a step it has not reached yet.
                else -> Box(
                    modifier = Modifier
                        .size(20.dp)
                        .border(1.5.dp, OrtColors.textLow, CircleShape)
                        .testTag("$testTag-pending-ring"),
                )
            }
        }
        Column(modifier = Modifier.padding(start = 13.dp).weight(1f)) {
            Text(
                text = title,
                style = OrtType.control,
                color = if (done || inProgress || failed) OrtColors.textHigh else OrtColors.textLow,
            )
            detail?.let { Text(text = it, style = OrtType.timeFreq, color = OrtColors.textDim) }
        }
        // R-943: the board's own live "0:11" elapsed counter, e.g. beside "Listening for signal".
        trailing?.let { Text(text = it, style = OrtType.timeFreq, color = OrtColors.accentGreenDim) }
    }
}

/**
 * R-943 (register, reviewer A3 run 4a, design): `Setup-Verify.dc.html`'s own "Input" waveform card
 * (board lines 71–91) — the real per-sample level history [RouteCheckState.InProgress.levelBars]/
 * [RouteCheckState.Passed.levelBars] built up during the [RouteCheckStage.SIGNAL] listen, drawn the
 * same proportional-bar way [LevelScreen]'s own meter draws S07's (constitution I: an honest flat
 * line — [bars] empty — before listening has produced any real sample at all, never a fabricated
 * waveform). [signalHeard] only ever reads the caption once [RouteCheckStage.SIGNAL] has genuinely
 * passed — never claimed early.
 */
@Composable
private fun InputWaveformCard(
    bars: List<Float>,
    noiseFloorDbfs: Double?,
    signalHeard: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().testTag("setup-verify-input-waveform")) {
        Text(text = "Input".uppercase(), style = OrtType.sectionLabel, color = OrtColors.textFaint)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .padding(top = 10.dp)
                .testTag("setup-verify-input-waveform-canvas"),
        ) {
            val gapPx = 2.dp.toPx()
            levelBarRects(bars, size.width, size.height, gapPx).forEach { rect ->
                drawRect(
                    color = rect.color,
                    topLeft = Offset(rect.x, rect.topY),
                    size = Size(rect.width, size.height - rect.topY),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = noiseFloorDbfs?.let { "noise floor %.0f dBFS".format(it).replace('-', '−') } ?: "noise floor —",
                style = OrtType.axis,
                color = OrtColors.textLow,
            )
            if (signalHeard) {
                Text(text = "signal heard", style = OrtType.axis, color = OrtColors.accentGreenDim)
            }
        }
    }
}
