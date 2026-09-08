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
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType

/**
 * S05 (`Setup-Verify.dc.html`, R-081) — the four checks progressing in board order, driven by
 * [RouteCheck]. `Continue` (guide §6.10: a setup primary stays disabled until its verify passes)
 * enables only on [RouteCheckState.Passed]; a [RouteCheckState.Mismatch] hands off to
 * [RouteMismatchScreen] instead (this screen never renders the halt itself — [SetupActivity]
 * switches screens on that state).
 */
@Composable
public fun VerifyScreen(state: VerifyViewState, onContinue: () -> Unit) {
    val passed = when (val check = state.check) {
        is RouteCheckState.InProgress -> check.passed
        is RouteCheckState.Passed -> RouteCheckStage.entries.toSet()
        else -> emptySet()
    }
    val allPassed = state.check is RouteCheckState.Passed

    SetupScaffold(
        step = SetupStep.VERIFY,
        title = "Verifying the route",
        subtitle = state.inputLabel,
        onBack = null,
        bottomActions = {
            PrimaryButton(
                text = "Continue",
                onClick = onContinue,
                enabled = allPassed,
                modifier = Modifier.fillMaxWidth().testTag("setup-verify-continue"),
            )
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
            detail = "Key the radio, or wait for traffic — up to 30 s",
            done = RouteCheckStage.SIGNAL in passed,
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
                color = if (done || inProgress) OrtColors.textHigh else OrtColors.textLow,
            )
            detail?.let { Text(text = it, style = OrtType.timeFreq, color = OrtColors.textDim) }
        }
    }
}
