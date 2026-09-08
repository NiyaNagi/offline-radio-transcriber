package org.ort.app.ui.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.RadioRow
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType

/**
 * S04 (`Setup-Input.dc.html`, R-081) — every real route [InputRouteEnumerator] found, each as a
 * WP2 [RadioRow] (the closest existing selectable-row component; see this package's report for the
 * gap this leaves — WP2 ships no icon+subtitle selectable row, so the type/native-rate text rides
 * in `RadioRow`'s trailing `count` slot rather than a second line under the label, and the
 * built-in mic's refusal warning cannot be rendered in amber through that slot). `Refresh` sits
 * beside the title (guide's title-trailing action), `Verify this input` is disabled until a route
 * is chosen.
 */
@Composable
public fun InputScreen(
    state: InputViewState,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onVerify: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    SetupScaffold(
        step = SetupStep.INPUT,
        title = "Input",
        subtitle = "Which of these is the radio?",
        onBack = onBack,
        titleTrailing = {
            TextAction(text = "Refresh", onClick = onRefresh, modifier = Modifier.testTag("setup-input-refresh"))
        },
        bottomActions = {
            PrimaryButton(
                text = "Verify this input",
                onClick = onVerify,
                enabled = state.selectedId != null,
                modifier = Modifier.fillMaxWidth().testTag("setup-input-verify"),
            )
        },
    ) {
        if (state.routes.isEmpty()) {
            Text(
                text = "No input devices were found. Connect the radio's audio adapter and tap Refresh.",
                style = OrtType.bodyProse,
                color = OrtColors.textMuted,
            )
        }
        Column {
            state.routes.forEach { route ->
                RadioRow(
                    label = route.label,
                    selected = route.id == state.selectedId,
                    onClick = { onSelect(route.id) },
                    count = route.subtitle,
                    modifier = Modifier.testTag("setup-input-route-${route.id}"),
                )
            }
        }
        Text(
            text = "Most USB adapters do not offer 16 kHz. The app takes the device's native rate " +
                "and resamples deterministically, so the same audio always produces the same samples.",
            style = OrtType.cardBody,
            color = OrtColors.textDim,
        )
    }
}
