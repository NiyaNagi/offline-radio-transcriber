package org.ort.app.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.InProgressRing
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType

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
            }
        },
    ) {
        val nativeRateHz = when (val check = state.check) {
            is RouteCheckState.InProgress -> check.nativeRateHz
            is RouteCheckState.Passed -> check.nativeRateHz
            else -> null
        }
        CheckRow(
            title = "Opened at native rate",
            detail = nativeRateHz?.let { "$it Hz · mono · 16-bit" },
            done = RouteCheckStage.NATIVE_RATE in passed,
            testTag = "setup-verify-check-native-rate",
        )
        CheckRow(
            title = "Routed device matches the one you chose",
            detail = null,
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
            testTag = "setup-verify-check-signal",
        )
        CheckRow(
            title = "Resampler identity recorded",
            detail = null,
            done = RouteCheckStage.RESAMPLER in passed,
            testTag = "setup-verify-check-resampler",
        )
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
                else -> Box(modifier = Modifier.size(20.dp).background(OrtColors.bgScreen, CircleShape))
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
    }
}
