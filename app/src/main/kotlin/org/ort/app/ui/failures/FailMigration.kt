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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType

/**
 * F20 — `Fail-Migration.dc.html`. WP11b: a standalone screen shown once, right after an update
 * whose migration failed a step. **No runtime signal today** — this codebase has exactly one
 * schema version, so no migration has ever failed; see [DebugFailureOverride]'s kdoc.
 */
@Composable
public fun FailMigrationScreen(
    state: MigrationViewState,
    onRebuildNow: () -> Unit,
    onSaveDiagnosticBundle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OrtColors.bgScreen)
            .failureScreenInset()
            .testTag("failure-migration-screen"),
    ) {
        FailureActionBarScaffold(
            content = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 30.dp),
                ) {
                    Icon(imageVector = OrtIcons.models, contentDescription = null, tint = OrtColors.accentGreen)
                    Text(text = state.versionLabel, style = OrtType.columnHeader, color = OrtColors.textFaint)
                }
                Text(
                    text = state.headline,
                    style = OrtType.screenTitle,
                    color = OrtColors.textHigh,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                SectionLabel("What happened", modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
                state.steps.forEach { step -> MigrationStepRow(step) }
                Text(
                    // Register R-562: the board's own second sentence (`Fail-Migration.dc.html`)
                    // was missing entirely — the build stopped at "ships.". Restored verbatim,
                    // including the board's own italic "pattern rebuilding" span.
                    text = buildAnnotatedString {
                        append(
                            "A migration can never destroy audio or a superseded transcript — that is " +
                                "tested against every released version before this one ships. Station and " +
                                "frequency views will show ",
                        )
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("pattern rebuilding") }
                        append(" until the marked overs are re-derived.")
                    },
                    style = OrtType.cardBody,
                    color = OrtColors.textDim,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            },
            actionBar = {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PrimaryButton(
                        text = "Rebuild now and continue",
                        onClick = onRebuildNow,
                        modifier = Modifier.fillMaxWidth().testTag("failure-migration-rebuild"),
                    )
                    TextAction(
                        text = "Save a diagnostic bundle first",
                        onClick = onSaveDiagnosticBundle,
                        modifier = Modifier.fillMaxWidth().testTag("failure-migration-save-diagnostics"),
                    )
                }
            },
        )
    }
}

@Composable
private fun MigrationStepRow(step: MigrationStep, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        val dotColor = if (step.ok) OrtColors.accentGreen else OrtColors.accentAmber
        Box(modifier = Modifier.size(9.dp).background(dotColor, CircleShape))
        Column {
            Text(text = step.label, style = OrtType.rowTitle, color = OrtColors.textHigh)
            Text(
                text = step.detail,
                style = OrtType.cardBody,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
