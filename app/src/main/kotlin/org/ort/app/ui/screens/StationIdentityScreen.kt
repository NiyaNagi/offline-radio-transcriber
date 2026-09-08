package org.ort.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.KeyValueRow
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.data.StationIdentityViewState
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType
import java.util.Locale

/**
 * `Station-Identity.dc.html` (R-073, FR-SPK-10, constitution III): how this station is known —
 * heard (callsign, lexicon allocation), voice (cluster size, confirmed vs inferred split, nearest
 * other station and its distance where the identity pipeline has written one), given by you (name,
 * note), and the never-leaves-the-device statement. `Rename`/`Add note`/`Split` are reachable
 * actions here; their write paths need a `:data` update query this package does not have (see
 * this package's report) — the callback fires, so the affordance exists and is testable, but does
 * not yet persist.
 */
@Composable
public fun StationIdentityScreen(
    state: StationIdentityViewState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onRename: () -> Unit = {},
    onAddNote: () -> Unit = {},
    onSplit: () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize()) {
        DrillInHeader(parentLabel = state.callsign, onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
            Text(
                text = "How this station is known",
                style = OrtType.screenTitle,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "Three things, kept separately, none of which leave this phone",
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.md),
            )

            SectionHeader(label = "Heard")
            KeyValueRow(key = "Callsign", value = state.callsign, subLine = "heard ${state.heardOverCount} time(s)")
            KeyValueRow(key = "Lexicon", value = state.lexiconLabel ?: "not known")

            SectionHeader(label = "Voice", modifier = Modifier.padding(top = OrtSpacing.md))
            val voiceSubLine = "${state.voice.confirmedCount} with the callsign heard · " +
                "${state.voice.inferredCount} inferred from it"
            KeyValueRow(
                key = "Voiceprint",
                value = "One cluster, ${state.voice.clusterOverCount} overs",
                subLine = voiceSubLine,
            )
            val nearestId = state.voice.nearestOtherStationId
            val nearestDistance = state.voice.nearestOtherDistance
            val nearestValue = if (nearestId != null && nearestDistance != null) {
                "$nearestId at %.2f".format(Locale.ROOT, nearestDistance)
            } else {
                "not computed yet"
            }
            val nearestSubLine = if (nearestId == null) "voice matching does not compare across stations yet" else null
            KeyValueRow(
                key = "Nearest other",
                value = nearestValue,
                subLine = nearestSubLine,
                trailingMarker = { TextAction(text = "Split", onClick = onSplit) },
            )

            SectionHeader(label = "Given by you", modifier = Modifier.padding(top = OrtSpacing.md))
            val nameActionLabel = if (state.givenByYou.name != null) "Rename" else "Add"
            KeyValueRow(
                key = "Name",
                value = state.givenByYou.name ?: "None",
                trailingMarker = { TextAction(text = nameActionLabel, onClick = onRename) },
            )
            val noteActionLabel = if (state.givenByYou.note != null) "Edit" else "Add"
            KeyValueRow(
                key = "Note",
                value = state.givenByYou.note ?: "None",
                trailingMarker = { TextAction(text = noteActionLabel, onClick = onAddNote) },
            )
        }

        Column(modifier = Modifier.padding(OrtSpacing.lg)) {
            NeverLeavesCard()
            Text(
                text = "Split is for when one cluster turns out to be two people — pick the overs " +
                    "that are not this station and they become a new unidentified voice. Every " +
                    "affected over is marked corrected and its old attribution kept.",
                style = OrtType.cardBody,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.md),
            )
        }
    }
}

@Composable
private fun NeverLeavesCard(modifier: Modifier = Modifier) {
    val text = "The voiceprint, the name and the note are never included in a contribution, a " +
        "diagnostic bundle or a backup. The callsign itself is public by nature and is the only " +
        "part of this record that can be exported."
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(OrtColors.bgCard, RoundedCornerShape(10.dp))
            .padding(14.dp)
            .semantics(mergeDescendants = true) { contentDescription = text },
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = OrtIcons.lock,
            contentDescription = null,
            tint = OrtColors.accentGreen,
            modifier = Modifier.padding(top = 1.dp),
        )
        Text(
            text = text,
            style = OrtType.cardBody,
            color = OrtColors.textBody,
            modifier = Modifier.padding(start = OrtSpacing.sm),
        )
    }
}
