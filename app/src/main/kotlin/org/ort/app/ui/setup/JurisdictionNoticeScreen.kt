package org.ort.app.ui.setup

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType

/**
 * P22 (NFR-6c, AC-166) — shown exactly once, on first run, right after `Welcome`; capture cannot
 * start until it has been dismissed ([SetupStateMachine.stepFor]'s own gate, `SetupSnapshot
 * .jurisdictionNoticeSeen`). No artboard exists for this screen yet (the `design` tree is outside
 * this unit's file ownership) — built to this package's own established [SetupScaffold] shape rather
 * than left undrawn.
 *
 * The legality of recording radio transmissions varies by jurisdiction (amateur/scanner traffic,
 * two-party consent rules for voice recordings, and local law on retaining or sharing it) — this
 * notice states that plainly, once, and asks the operator to confirm they have checked their own
 * local law; it makes no legal claim of its own about any specific jurisdiction (constitution I:
 * a claim this app cannot verify is never asserted as fact).
 */
@Composable
public fun JurisdictionNoticeScreen(onContinue: () -> Unit) {
    SetupScaffold(
        step = SetupStep.JURISDICTION_NOTICE,
        title = "Before you record",
        subtitle = "The legality of recording radio traffic varies by where you are",
        onBack = null,
        bottomActions = {
            PrimaryButton(
                text = "I understand",
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth().testTag("setup-jurisdiction-continue"),
            )
        },
    ) {
        Text(
            text = "Rules on recording amateur and scanner radio traffic — and on retaining or " +
                "sharing what you capture — differ by country, state and sometimes locality. This " +
                "app does not know which rules apply where you are.",
            style = OrtType.bodyProse,
            color = OrtColors.textSecondary,
        )
        DotPointCard(
            points = listOf(
                "Check your own local law before you start an overnight or unattended session.",
                "Everything this app records stays on this device unless you choose to export or " +
                    "share it.",
            ),
        )
    }
}
