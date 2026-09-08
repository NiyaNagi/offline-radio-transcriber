package org.ort.app.ui.setup

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.KeyValueRow
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
 *
 * R-226 (validator pass 2): rows used to be a hand-rolled `Row` with a *fixed* 82dp label column
 * (`Modifier.size(width = 82.dp, ...)`), which at font scale 2.0 truncated labels ("Input" ->
 * "Inout", "Overnight" -> "Overni") and overlapped the leading marker dot entirely — confirmed on
 * `setup/S12-ready-pass2@2x.png`. Now built on WP2's [KeyValueRow], whose own R-152 fix already
 * sizes its label column to real content rather than a fixed ceiling (that row's own doc comment
 * names the identical class of bug: "Stations5" colliding at large scale). The leading marker dot
 * has no slot on [KeyValueRow] itself, so it is composed as a sibling in front of it here, the same
 * pattern this package already used for R-122's device-type icons on [InputScreen] — never editing
 * `Rows.kt` (WP2's file) and never hand-rolling a second full row component.
 *
 * R-265 (validator finding, halt): each row's marker/[KeyValueRow]/trailing status-or-action were
 * three separate, non-merged `Text`/marker nodes with no focus action of their own — confirmed by
 * reading `Rows.kt`'s [KeyValueRow] before writing this: its own `Row` carries neither
 * `Modifier.semantics(mergeDescendants = true)` nor `Modifier.focusable()`, so TalkBack's linear
 * swipe traversal skipped every one of S12's five facts entirely (there was no accessibility node
 * to land on). `KeyValueRow` should provide this natively — reported to WP2 rather than fixed in
 * `Rows.kt` (outside this row's owned files) — worked around here by wrapping each row's own outer
 * `Row` in exactly the pair the finding names: `Modifier.focusable()` (a real focus/traversal stop)
 * and `Modifier.semantics(mergeDescendants = true) { contentDescription = ... }` (one merged
 * announcement, built explicitly rather than left to automatic child-`Text` merging, the same
 * `"$title. $subtitle"` pattern `SetupScaffold.kt`'s own `NavigationRow` already established) —
 * `"Input, USB Audio Device, verified"` for a verified row, exactly the string the finding quotes.
 * Semantics merging (not raw touch dispatch) is the only thing this changes: a sighted operator's
 * ordinary tap still only activates the small `Fix`/`Install` text as before; a TalkBack user's
 * double-tap after landing on the merged row now correctly reaches the same action, forwarded up
 * through the merge exactly as Compose's own accessibility merging documents.
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
                    .testTag("setup-ready-row-${row.label.lowercase()}")
                    .focusable()
                    .semantics(mergeDescendants = true) { contentDescription = readyRowDescription(row) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (row.ok) {
                    Box(modifier = Modifier.size(9.dp).background(OrtColors.accentGreen, CircleShape))
                } else {
                    AmberHalfMarker()
                }
                KeyValueRow(
                    key = row.label,
                    value = row.value,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                    trailingMarker = {
                        when {
                            row.statusText != null ->
                                Text(text = row.statusText, style = OrtType.signal, color = OrtColors.accentGreenDim)
                            row.actionLabel != null ->
                                TextAction(text = row.actionLabel, onClick = { row.onAction?.invoke() })
                        }
                    },
                )
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

/** R-265: the exact merged announcement for one row — `"Input, USB Audio Device, verified"` when
 * verified, `"Overnight, Battery exemption skipped, Fix"` when it needs one, naming whichever of
 * [ReadyRow.statusText]/[ReadyRow.actionLabel] is actually present (never both — [ReadyRowsForTest]
 * and every `readyRowsFor` builder below only ever sets one or neither). */
internal fun readyRowDescription(row: ReadyRow): String = listOfNotNull(
    row.label,
    row.value,
    row.statusText,
    row.actionLabel,
).joinToString(", ")

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
 * detekt's parameter-count threshold. */
public data class ReadyActions(
    val onFixInput: () -> Unit,
    val onFixLevel: () -> Unit,
    val onFixOvernight: () -> Unit,
    val onFixRadio: () -> Unit,
    /** Opens `ReaderActivity` at its `SETTINGS` destination (WP9 round 3) — see
     * [SetupActivity.onInstallModel]'s own doc comment for exactly where it lands and why (Settings'
     * root, not its `Assets` sub-screen directly — no entry point for that exists yet). */
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
    levelRow(store, actions.onFixLevel),
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

/**
 * Validator finding (ui-conformance-plan WP9, register R-120..R-125 follow-up): this row must
 * never show a green "in band" marker beside "Not measured" — [SetupStore.levelInBand] and
 * [SetupStore.levelPeakDbfs] are two independently-stored preferences (`onLevelStateChanged`
 * writes them together on the real path, but nothing enforces that they can never drift — a
 * test setting one without the other is exactly how this surfaced), so `ok` is derived from the
 * peak actually being present, never the flag alone (constitution I: an attribution — here, "the
 * level is fine" — without its backing measurement is a bug, not a UI nicety).
 */
private fun levelRow(store: SetupStore, onFixLevel: () -> Unit): ReadyRow {
    val measured = store.levelPeakDbfs
    val inBand = measured != null && store.levelInBand
    return ReadyRow(
        label = "Level",
        value = measured?.let { "Peaks %.0f dBFS".format(it) } ?: "Not measured",
        ok = inBand,
        statusText = "in band".takeIf { inBand },
        actionLabel = "Fix".takeIf { !inBand },
        onAction = onFixLevel,
    )
}

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
