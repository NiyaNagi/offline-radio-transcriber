package org.ort.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.KeyValueRow
import org.ort.app.ui.components.LiveBar
import org.ort.app.ui.components.LiveBarViewState
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.data.CaptureStateTone
import org.ort.app.ui.data.CaptureStatusViewState
import org.ort.app.ui.data.KeyValueFacts
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * `Capture-Status.dc.html`, exactly (ui-conformance-plan WP4, R-031/R-032/R-034/R-035/R-038,
 * FR-UI-7) — renamed from `StatusScreen.kt`, which rendered the flat field dump R-032/R-033
 * found. Every fact here comes from [state], a [CaptureStatusViewState] built off the real
 * process-wide capture holders ([org.ort.app.ui.data.CaptureStatusMapper]'s own kdoc names the
 * two facts — Input, Level — this cannot honestly measure yet).
 *
 * Reachable from the drawer's Capture row (R-035) via
 * [org.ort.app.ui.screens.CaptureStatusContent] — the drawer wiring itself is
 * [org.ort.app.ui.navigation.OrtNavHost]'s (WP3's) file, out of this package's row; see this
 * package's report for what WP3 still has to do to land R-035.
 */
@Composable
public fun CaptureStatusScreen(
    state: CaptureStatusViewState,
    modifier: Modifier = Modifier,
    liveBar: LiveBarViewState? = null,
    onStop: () -> Unit = {},
    onOpenLive: () -> Unit = {},
) {
    var confirmingStop by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(OrtSpacing.lg),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        StateDot(tone = state.stateTone, size = 9.dp)
                        Text(
                            text = state.stateLabel,
                            style = OrtType.screenTitle,
                            color = OrtColors.textHigh,
                            modifier = Modifier.testTag("capture-status-title"),
                        )
                    }
                    Text(
                        text = state.sinceElapsedLabel,
                        style = OrtType.subtitle,
                        color = OrtColors.textDim,
                        modifier = Modifier
                            .padding(top = 3.dp)
                            .semantics { contentDescription = state.sinceElapsedLabel },
                    )
                }
                state.haltActionLabel?.let { label ->
                    TextAction(
                        text = label,
                        onClick = { confirmingStop = true },
                        modifier = Modifier.testTag("capture-status-stop"),
                    )
                }
            }

            SectionHeader(label = "Audio", modifier = Modifier.padding(top = OrtSpacing.lg))
            KeyValueRowWithDot("Input", state.input, "capture-status-input")
            KeyValueRowWithDot("Level", state.level, "capture-status-level")
            KeyValueRowWithDot("Radio", state.radio, "capture-status-radio")

            SectionHeader(label = "Processing", modifier = Modifier.padding(top = OrtSpacing.lg))
            KeyValueRowWithDot("Overs", state.overs, "capture-status-overs")
            KeyValueRowWithDot("Backlog", state.backlog, "capture-status-backlog")
            KeyValueRowWithDot("Tier", state.tier, "capture-status-tier")
            KeyValueRowWithDot("Thermal", state.thermal, "capture-status-thermal")

            SectionHeader(label = "Device", modifier = Modifier.padding(top = OrtSpacing.lg))
            KeyValueRowWithDot("Storage", state.storage, "capture-status-storage")
            KeyValueRowWithDot("Battery", state.battery, "capture-status-battery")
        }

        if (liveBar != null) {
            LiveBar(state = liveBar, onClick = onOpenLive, modifier = Modifier.testTag("capture-status-livebar"))
        }
    }

    if (confirmingStop) {
        StopConfirmDialog(
            title = state.haltConfirmTitle,
            body = state.haltConfirmBody,
            onConfirm = {
                confirmingStop = false
                onStop()
            },
            onDismiss = { confirmingStop = false },
        )
    }
}

@Composable
private fun KeyValueRowWithDot(key: String, facts: KeyValueFacts, testTagValue: String, modifier: Modifier = Modifier) {
    val description = buildString {
        append(key)
        append(": ")
        append(facts.value)
        facts.subLine?.let {
            append(". ")
            append(it)
        }
        facts.trailingText?.let {
            append(". ")
            append(it)
        }
    }
    KeyValueRow(
        key = key,
        value = facts.value,
        subLine = facts.subLine,
        modifier = modifier
            .testTag(testTagValue)
            .semantics(mergeDescendants = true) { contentDescription = description },
        trailingMarker = facts.trailingDot?.let { tone ->
            {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StateDot(tone = tone, size = 9.dp)
                    facts.trailingText?.let { Text(text = it, style = OrtType.subLine, color = OrtColors.textDim) }
                }
            }
        },
    )
}

/** The artboard's literal inline state circle — no shared "state dot" component exists in WP2's
 * inventory to reuse (its markers are all attribution-state specific), so this is drawn locally,
 * the same way `Feedback.dc.html`'s `FailedMarker` is (see that composable's own doc comment). */
@Composable
private fun StateDot(tone: CaptureStateTone, size: Dp, modifier: Modifier = Modifier) {
    val color = when (tone) {
        CaptureStateTone.NOMINAL -> OrtColors.accentGreen
        CaptureStateTone.DEGRADED -> OrtColors.accentAmber
        CaptureStateTone.HALTED -> OrtColors.haltFill
        CaptureStateTone.IDLE -> OrtColors.markerUnknown
    }
    Box(modifier = modifier.size(size).background(color, CircleShape))
}

@Composable
private fun StopConfirmDialog(title: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = OrtType.cardTitle, color = OrtColors.textHigh) },
        text = { Text(text = body, style = OrtType.cardBody, color = OrtColors.textMuted) },
        confirmButton = {
            Text(
                text = "Stop capture",
                style = OrtType.control,
                color = OrtColors.haltText,
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clickable(role = Role.Button, onClickLabel = "Stop capture", onClick = onConfirm)
                    .testTag("capture-status-stop-confirm")
                    .semantics { role = Role.Button },
            )
        },
        dismissButton = {
            Text(
                text = "Keep capturing",
                style = OrtType.control,
                color = OrtColors.accentGreen,
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clickable(role = Role.Button, onClickLabel = "Keep capturing", onClick = onDismiss)
                    .testTag("capture-status-stop-cancel")
                    .semantics { role = Role.Button },
            )
        },
        containerColor = OrtColors.bgRaised,
    )
}
