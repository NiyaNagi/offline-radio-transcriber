package org.ort.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.ort.app.ui.components.AttributionMarker
import org.ort.app.ui.components.CheckboxRow
import org.ort.app.ui.components.FilterChip
import org.ort.app.ui.components.FilterChipRow
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.Sheet
import org.ort.app.ui.data.LogFilterSheetViewState
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType
import org.ort.core.Attribution
import org.ort.core.AttributionState

/**
 * R-042 (ui-conformance WP5): the Log's filter sheet (`design/canvas/Log-Filter.dc.html`, FR-UI-3).
 * Built entirely on WP2's [Sheet]/[FilterChip]/[CheckboxRow] — the frequency row, the four
 * attribution checkboxes (each with its real count and the same marker shape [LogRow] renders,
 * via [AttributionMarker]'s shape-only form), "Also show" for rejected segments and gaps, and the
 * `Show N overs` primary action. Filtering itself is pure, in `ui/data/LogViewData.kt`
 * ([org.ort.app.ui.data.LogItemsMapper]) — this composable only renders what it is given and
 * reports intent back through callbacks.
 */
@Composable
public fun LogFilterSheet(
    state: LogFilterSheetViewState,
    onFrequencySelect: (Long?) -> Unit,
    onAttributionToggle: (AttributionState, Boolean) -> Unit,
    onShowRejectedToggle: (Boolean) -> Unit,
    onShowGapsToggle: (Boolean) -> Unit,
    onClearAll: () -> Unit,
    onShow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Sheet(title = "Filter the log", modifier = modifier, onClearAll = onClearAll) {
        SectionHeader(label = "Frequency", modifier = Modifier.padding(top = OrtSpacing.xs))
        FilterChipRow(modifier = Modifier.fillMaxWidth().padding(vertical = OrtSpacing.sm)) {
            state.frequencyOptions.forEach { option ->
                val label = if (option.hz == null) option.label else "${option.label} ${option.count}"
                FilterChip(label = label, selected = option.selected, onClick = { onFrequencySelect(option.hz) })
            }
        }

        SectionHeader(label = "Attribution")
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = OrtSpacing.xs)) {
            state.attributionOptions.forEach { option ->
                CheckboxRow(
                    label = option.label,
                    checked = option.checked,
                    onCheckedChange = { checked -> onAttributionToggle(option.state, checked) },
                    count = option.count.toString(),
                    leading = { AttributionMarker(attribution = markerFor(option.state), showConfidence = false) },
                )
            }
        }

        SectionHeader(label = "Also show")
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = OrtSpacing.xs)) {
            CheckboxRow(
                label = "Rejected segments",
                checked = state.rejectedShown,
                onCheckedChange = onShowRejectedToggle,
                count = state.rejectedCount.toString(),
            )
            CheckboxRow(
                label = "Not-listening gaps",
                checked = state.gapsShown,
                onCheckedChange = onShowGapsToggle,
                count = state.gapsCount.toString(),
            )
        }

        SectionHeader(label = "Time")
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = OrtSpacing.sm)) {
            TimeField(label = "from", value = state.fromLabel, modifier = Modifier.weight(1f))
            TimeField(label = "to", value = state.toLabel, modifier = Modifier.weight(1f))
        }

        PrimaryButton(
            text = "Show ${state.matchingCount} overs",
            onClick = onShow,
            modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.sm),
        )
    }
}

@Composable
private fun TimeField(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.padding(end = OrtSpacing.sm)) {
        Text(text = label, style = OrtType.subLine, color = OrtColors.textLow)
        Text(
            text = value,
            style = OrtType.timeFreq,
            color = OrtColors.textHigh,
            modifier = Modifier.padding(start = OrtSpacing.sm),
        )
    }
}

/**
 * A minimal [Attribution] carrying nothing but the closed-set [state] — for the filter sheet's
 * checkbox marker, which needs only the shape ([AttributionMarker]'s `showConfidence = false`
 * form), never a real station or score.
 */
private fun markerFor(state: AttributionState): Attribution = when (state) {
    AttributionState.CONFIRMED -> Attribution.confirmed("X", 1.0)
    AttributionState.INFERRED -> Attribution.inferred("X", 1.0)
    AttributionState.AMBIGUOUS -> Attribution.ambiguous()
    AttributionState.UNKNOWN -> Attribution.unknown()
}
