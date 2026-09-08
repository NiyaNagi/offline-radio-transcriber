package org.ort.app.ui.setup

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType
import org.ort.pipeline.capture.AsrAvailability
import org.ort.pipeline.capture.RigStatus

/** One `Setup-Done.dc.html` summary row — real, not fabricated: every field below is read from
 * [SetupStore], live permission state, [RigStatus] or [AsrAvailability], never invented. */
public data class ReadyRow(
    val label: String,
    val value: String,
    val ok: Boolean,
    val statusText: String?,
    val actionLabel: String?,
    val onAction: (() -> Unit)? = null,
)

/** S12's whole view-state. */
public data class ReadyViewState(val rows: List<ReadyRow>)

/**
 * S12 (`Setup-Done.dc.html`, R-080..R-084) — the summary rows with verified / in band / skipped
 * (amber) / installed-or-missing, and `Start capture`. Every row's action ([ReadyRow.onAction]) is
 * wired by [SetupActivity]; the model row's `Install` has nowhere real to navigate to from setup
 * (the Models destination lives inside `OrtNavHost`, WP3's file, only reachable once
 * `ReaderActivity` exists after capture starts) — see this package's report for that gap.
 */
@Composable
public fun ReadyScreen(state: ReadyViewState, onStartCapture: () -> Unit) {
    SetupScaffold(
        step = SetupStep.READY,
        title = "Ready",
        subtitle = "Everything below can be changed later in Settings",
        onBack = null,
        bottomActions = {
            PrimaryButton(
                text = "Start capture",
                onClick = onStartCapture,
                modifier = Modifier.fillMaxWidth().testTag("setup-ready-start-capture"),
            )
        },
    ) {
        state.rows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .testTag("setup-ready-row-${row.label.lowercase()}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (row.ok) {
                    Box(modifier = Modifier.size(9.dp).background(OrtColors.accentGreen, CircleShape))
                } else {
                    AmberHalfMarker()
                }
                Text(
                    text = row.label,
                    style = OrtType.subtitle,
                    color = OrtColors.textDim,
                    modifier = Modifier.padding(start = 12.dp).size(width = 82.dp, height = 18.dp),
                )
                Text(
                    text = row.value,
                    style = OrtType.control,
                    color = OrtColors.textHigh,
                    modifier = Modifier.weight(1f),
                )
                when {
                    row.statusText != null ->
                        Text(text = row.statusText, style = OrtType.signal, color = OrtColors.accentGreenDim)
                    row.actionLabel != null ->
                        TextAction(text = row.actionLabel, onClick = { row.onAction?.invoke() })
                }
            }
        }
        Text(
            text = "Capture works without a model — audio is kept, and every over already " +
                "recorded is transcribed once one is installed. The two amber items are worth " +
                "fixing before an overnight run.",
            style = OrtType.cardBody,
            color = OrtColors.textDim,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun AmberHalfMarker() {
    Canvas(modifier = Modifier.size(9.dp)) {
        val d = size.minDimension
        drawCircle(
            color = OrtColors.accentAmber,
            radius = d / 2 - 0.75.dp.toPx(),
            style = Stroke(1.5.dp.toPx()),
        )
        clipRect(right = d / 2) {
            drawCircle(color = OrtColors.accentAmber, radius = d / 2 - 0.75.dp.toPx())
        }
    }
}

/** The five "Fix"/"Install" callbacks S12's rows can carry, bundled so [readyRowsFor] stays under
 * detekt's parameter-count threshold — each is a real navigation, not a placeholder, except
 * [onInstallModel] (see that parameter's own doc comment for why). */
public data class ReadyActions(
    val onFixInput: () -> Unit,
    val onFixLevel: () -> Unit,
    val onFixOvernight: () -> Unit,
    val onFixRadio: () -> Unit,
    /** No destination exists from setup for this today — the Models destination lives inside
     * `OrtNavHost` (WP3's file), reachable only once `ReaderActivity` exists after capture starts.
     * See this package's report for this gap. */
    val onInstallModel: () -> Unit,
)

/**
 * Builds S12's five rows from real state only — [SetupStore.snapshot] for input/level/overnight/
 * radio choice, live [batteryExempt] (`PowerManager.isIgnoringBatteryOptimizations`, diagnostic
 * only per constitution IV), [rigStatus] and [asrState]. Pure and unit-testable without a screen.
 */
public fun readyRowsFor(
    store: SetupStore,
    batteryExempt: Boolean,
    rigStatus: RigStatus.State,
    asrState: AsrAvailability.State,
    actions: ReadyActions,
): List<ReadyRow> = listOf(
    ReadyRow(
        label = "Input",
        value = store.selectedInputLabel ?: "Not set",
        ok = store.inputVerified,
        statusText = "verified".takeIf { store.inputVerified },
        actionLabel = "Fix".takeIf { !store.inputVerified },
        onAction = actions.onFixInput,
    ),
    ReadyRow(
        label = "Level",
        value = store.levelPeakDbfs?.let { "Peaks %.0f dBFS".format(it) } ?: "Not measured",
        ok = store.levelInBand,
        statusText = "in band".takeIf { store.levelInBand },
        actionLabel = "Fix".takeIf { !store.levelInBand },
        onAction = actions.onFixLevel,
    ),
    ReadyRow(
        label = "Overnight",
        value = if (batteryExempt) "Battery exemption granted" else "Battery exemption skipped",
        ok = batteryExempt,
        statusText = "verified".takeIf { batteryExempt },
        actionLabel = "Fix".takeIf { !batteryExempt },
        onAction = actions.onFixOvernight,
    ),
    radioRow(store, rigStatus, actions.onFixRadio),
    modelRow(asrState, actions.onInstallModel),
)

private fun radioRow(store: SetupStore, rigStatus: RigStatus.State, onFixRadio: () -> Unit): ReadyRow =
    when (store.radioChoice) {
        RadioChoice.NONE, null -> ReadyRow(
            label = "Radio",
            value = store.manualFrequencyHz?.let { "No radio · %.3f MHz by hand".format(it / 1_000_000.0) }
                ?: "No radio · frequency by hand",
            ok = true,
            statusText = null,
            actionLabel = null,
        )
        RadioChoice.TH_D75A, RadioChoice.OTHER_CAT_RIG -> radioRowForCatRig(rigStatus, onFixRadio)
    }

private fun radioRowForCatRig(rigStatus: RigStatus.State, onFixRadio: () -> Unit): ReadyRow = when (rigStatus) {
    is RigStatus.State.Connected -> {
        val bandCount = rigStatus.bands.size
        ReadyRow(
            label = "Radio",
            value = "${rigStatus.descriptor} · $bandCount band${if (bandCount == 1) "" else "s"}",
            ok = true,
            statusText = "verified",
            actionLabel = null,
        )
    }
    is RigStatus.State.Stale -> ReadyRow(
        label = "Radio",
        value = "${rigStatus.lastKnown.descriptor} · stale",
        ok = false,
        statusText = null,
        actionLabel = "Fix",
        onAction = onFixRadio,
    )
    is RigStatus.State.Absent -> ReadyRow(
        label = "Radio",
        value = "No rig support in this build yet",
        ok = false,
        statusText = null,
        actionLabel = "Fix",
        onAction = onFixRadio,
    )
}

private fun modelRow(asrState: AsrAvailability.State, onInstallModel: () -> Unit): ReadyRow = when (asrState) {
    is AsrAvailability.State.Available -> ReadyRow(
        label = "Model",
        value = asrState.modelRef,
        ok = true,
        statusText = "installed",
        actionLabel = null,
    )
    is AsrAvailability.State.Unavailable, AsrAvailability.State.NotYetChecked -> ReadyRow(
        label = "Model",
        value = "No transcription model yet",
        ok = false,
        statusText = null,
        actionLabel = "Install",
        onAction = onInstallModel,
    )
}
