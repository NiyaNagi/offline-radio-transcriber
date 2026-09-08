package org.ort.app.ui.failures

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.Banner
import org.ort.app.ui.components.BannerTone
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * F19 — `Fail-Reconcile.dc.html`. WP11b: a standalone screen (records and files disagree on
 * launch). **No runtime signal today** — nothing walks `:data`'s records against the audio
 * directory looking for orphans on either side; see [DebugFailureOverride]'s kdoc.
 */
@Composable
public fun FailReconcileScreen(
    state: ReconcileViewState,
    onImport: () -> Unit,
    onLeaveAsIs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OrtColors.bgScreen)
            .failureScreenInset()
            .verticalScroll(rememberScrollState())
            .testTag("failure-reconcile-screen"),
    ) {
        Text(
            text = "Records and files disagree",
            style = OrtType.screenTitle,
            color = OrtColors.textHigh,
            modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = 10.dp),
        )
        Banner(
            title = "${state.recordsNoFile.size} records have no audio file. " +
                "${state.filesNoRecord.size} audio files have no record.",
            body = "Both directions are reported. Either side could be the one worth keeping, so the app " +
                "decides neither — it shows you and waits.",
            tone = BannerTone.DEGRADED,
            modifier = Modifier.padding(horizontal = OrtSpacing.lg),
        )
        SectionLabel(
            "Record, no file · ${state.recordsNoFile.size}",
            modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = 12.dp),
        )
        state.recordsNoFile.forEach { record ->
            ReconcileRow(title = "${record.label} · ${record.whenLabel}", note = record.note)
        }
        SectionLabel(
            "File, no record · ${state.filesNoRecord.size}",
            modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = 12.dp),
        )
        state.filesNoRecord.forEach { file ->
            ReconcileRow(title = "${file.path} · ${file.durationLabel}", note = file.note)
        }
        SectionLabel("Likely cause", modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = 12.dp))
        Text(
            text = state.causeText,
            style = OrtType.control,
            color = OrtColors.textBody,
            modifier = Modifier.padding(horizontal = OrtSpacing.lg),
        )
        SectionLabel("What you can do", modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = 12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = OrtSpacing.lg, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Import the ${state.filesNoRecord.size} orphan files as new overs",
                style = OrtType.control,
                color = OrtColors.textHigh,
                modifier = Modifier.weight(1f),
            )
            TextAction(text = "Import", onClick = onImport, modifier = Modifier.testTag("failure-reconcile-import"))
        }
        TextAction(
            text = "Leave everything as it is",
            onClick = onLeaveAsIs,
            modifier = Modifier
                .padding(horizontal = OrtSpacing.lg, vertical = 12.dp)
                .testTag("failure-reconcile-leave"),
        )
    }
}

@Composable
private fun ReconcileRow(title: String, note: String?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = OrtSpacing.lg, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(9.dp)
                .background(OrtColors.accentGapDim, CircleShape),
        )
        Column {
            Text(text = title, style = OrtType.control, color = OrtColors.textHigh)
            note?.let {
                Text(
                    text = it,
                    style = OrtType.subLine,
                    color = OrtColors.textDim,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
