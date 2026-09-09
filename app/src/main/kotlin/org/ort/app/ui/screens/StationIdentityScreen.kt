package org.ort.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.ActionBar
import org.ort.app.ui.components.AttributionMarker
import org.ort.app.ui.components.Badge
import org.ort.app.ui.components.BadgeKind
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.EmptyState
import org.ort.app.ui.components.KeyValueRow
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.data.StationIdentityViewState
import org.ort.app.ui.data.StationVoiceSplitViewState
import org.ort.app.ui.data.VoiceprintSplitOverViewState
import org.ort.app.ui.data.pluralize
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType
import org.ort.core.Attribution
import java.util.Locale

private enum class EditTarget { NONE, NAME, NOTE }

/**
 * `Station-Identity.dc.html` (R-073, FR-SPK-10, constitution III): how this station is known —
 * heard (callsign, lexicon allocation), voice (cluster size, confirmed vs inferred split, nearest
 * other station and its distance where the identity pipeline has written one), given by you (name,
 * note), and the never-leaves-the-device statement. `Rename`/`Add`/`Edit note` open an inline
 * [TextField] (no WP2 field component exists yet — this package follows `TransmissionDetailScreen`'s
 * own precedent, a plain Material [TextField] with a real content description) and persist through
 * [onRename]/[onAddNote]; `Split` opens [StationSplitScreen].
 */
@Composable
public fun StationIdentityScreen(
    state: StationIdentityViewState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onRename: (String?) -> Unit = {},
    onAddNote: (String?) -> Unit = {},
    onSplit: () -> Unit = {},
) {
    var editing by remember(state.stationId) { mutableStateOf(EditTarget.NONE) }
    var draft by remember(state.stationId) { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize()) {
        DrillInHeader(parentLabel = state.callsign, onBack = onBack)
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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

                HeardAndVoiceFacts(state = state, onSplit = onSplit)

                SectionHeader(label = "Given by you", modifier = Modifier.padding(top = OrtSpacing.md))

                GivenByYouName(
                    state = state,
                    editing = editing == EditTarget.NAME,
                    draft = draft,
                    onDraftChange = { draft = it },
                    onStartEditing = {
                        draft = state.givenByYou.name.orEmpty()
                        editing = EditTarget.NAME
                    },
                    onSave = {
                        onRename(draft.trim().ifBlank { null })
                        editing = EditTarget.NONE
                    },
                    onCancel = { editing = EditTarget.NONE },
                )

                GivenByYouNote(
                    state = state,
                    editing = editing == EditTarget.NOTE,
                    draft = draft,
                    onDraftChange = { draft = it },
                    onStartEditing = {
                        draft = state.givenByYou.note.orEmpty()
                        editing = EditTarget.NOTE
                    },
                    onSave = {
                        onAddNote(draft.trim().ifBlank { null })
                        editing = EditTarget.NONE
                    },
                    onCancel = { editing = EditTarget.NONE },
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
}

/** The "Heard" and "Voice" facts (callsign, lexicon, cluster size, `Split`, nearest other) — split
 * out of [StationIdentityScreen] to keep that composable under detekt's `LongMethod` limit.
 * R-214: every row here carries a leading state marker (`Station-Identity.dc.html`'s own shape —
 * filled for a fact heard directly, a hollow ring for one the voice pipeline infers rather than
 * hears, a small muted dot for one not yet computed), and the section itself is labelled `Heard`
 * — [KeyValueRow] has no leading-marker slot, so this uses [MarkedKeyValueRow] instead.
 */
@Composable
private fun HeardAndVoiceFacts(state: StationIdentityViewState, onSplit: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        SectionHeader(label = "Heard")
        MarkedKeyValueRow(
            attribution = Attribution.confirmed(state.stationId, 1.0),
            key = "Callsign",
            value = state.callsign,
            // R-572 (register, polish): the board's own full clause — the audio-level-resolution
            // qualifier ("parsed from audio every time") this package's earlier build dropped, not
            // just the bare over count. R-212: the shared plural helper, never a literal "(s)".
            subLine = "heard clearly in ${pluralize(state.heardOverCount, "over")} · parsed from audio every time",
        )
        val lexiconKnown = state.lexiconLabel != null
        MarkedKeyValueRow(
            attribution = if (lexiconKnown) Attribution.confirmed(state.stationId, 1.0) else Attribution.unknown(),
            key = "Lexicon",
            value = state.lexiconLabel ?: "not known",
        )

        SectionHeader(label = "Voice", modifier = Modifier.padding(top = OrtSpacing.md))
        // R-572 (register, polish): the board's own trailing "· stable since <date>" clause —
        // present only when this package can honestly state one (see [StationPolling.stationIdentity]'s
        // own doc comment on [StationVoiceViewState.stableSinceLabel] for why it can be absent).
        val voiceSubLine = "${state.voice.confirmedCount} with the callsign heard · " +
            "${state.voice.inferredCount} inferred from it" +
            (state.voice.stableSinceLabel?.let { " · stable since $it" } ?: "")
        // R-570 (register, design): the board attaches `Split` to the Nearest-other row, not
        // Voiceprint — it is a *comparison* between the two clusters that decides whether a split
        // makes sense, so the action belongs beside the thing being compared against.
        MarkedKeyValueRow(
            attribution = Attribution.inferred(state.stationId, 1.0),
            key = "Voiceprint",
            value = "One cluster, ${pluralize(state.voice.clusterOverCount, "over")}",
            subLine = voiceSubLine,
            modifier = Modifier.testTag("station-identity-voiceprint-row"),
        )
        val nearestId = state.voice.nearestOtherStationId
        val nearestDistance = state.voice.nearestOtherDistance
        val nearestValue = if (nearestId != null && nearestDistance != null) {
            "$nearestId at %.2f".format(Locale.ROOT, nearestDistance)
        } else {
            "not computed yet"
        }
        MarkedKeyValueRow(
            attribution = Attribution.unknown(),
            key = "Nearest other",
            value = nearestValue,
            trailingMarker = {
                TextAction(text = "Split", onClick = onSplit, modifier = Modifier.testTag("station-identity-split"))
            },
            modifier = Modifier.testTag("station-identity-nearest-other-row"),
        )
    }
}

/** [KeyValueRow]'s own layout plus a leading [AttributionMarker] (shape only, `showConfidence =
 * false` — this is not a real transmission attribution, just its shape vocabulary borrowed for
 * "how sure is this fact", the same convention [WhatThisSaysLine] already uses on `Station-Pattern`). */
@Composable
private fun MarkedKeyValueRow(
    attribution: Attribution,
    key: String,
    value: String,
    modifier: Modifier = Modifier,
    subLine: String? = null,
    trailingMarker: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = OrtSpacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Box(modifier = Modifier.padding(top = 6.dp)) {
            AttributionMarker(attribution = attribution, showConfidence = false)
        }
        Spacer(modifier = Modifier.width(OrtSpacing.sm))
        KeyValueRow(key = key, value = value, subLine = subLine, trailingMarker = trailingMarker)
    }
}

/** The "Given by you" name row — a plain [KeyValueRow] with a `Rename`/`Add` action, or an
 * [InlineEditRow] while [editing]. Split out of [StationIdentityScreen] for the same reason as
 * [HeardAndVoiceFacts]. */
@Composable
private fun GivenByYouName(
    state: StationIdentityViewState,
    editing: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onStartEditing: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (editing) {
        InlineEditRow(
            testTag = "station-identity-name-field",
            label = "Name",
            value = draft,
            onValueChange = onDraftChange,
            onSave = onSave,
            onCancel = onCancel,
            modifier = modifier,
        )
    } else {
        // R-571 (register, design): every other row on this screen carries a leading state
        // marker; this one is user-supplied fact rather than a heard/inferred one, so it borrows
        // the same CONFIRMED-shaped (filled, green) marker `HeardAndVoiceFacts`'s own Callsign row
        // uses for "heard directly" once set, and UNKNOWN's small grey dot for the honest "None".
        MarkedKeyValueRow(
            attribution = if (state.givenByYou.name != null) {
                Attribution.confirmed(state.stationId, 1.0)
            } else {
                Attribution.unknown()
            },
            key = "Name",
            value = state.givenByYou.name ?: "None",
            modifier = modifier,
            trailingMarker = {
                TextAction(
                    text = if (state.givenByYou.name != null) "Rename" else "Add",
                    onClick = onStartEditing,
                    modifier = Modifier.testTag("station-identity-rename"),
                )
            },
        )
    }
}

/** The "Given by you" note row — [GivenByYouName]'s own twin. */
@Composable
private fun GivenByYouNote(
    state: StationIdentityViewState,
    editing: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onStartEditing: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (editing) {
        InlineEditRow(
            testTag = "station-identity-note-field",
            label = "Note",
            value = draft,
            onValueChange = onDraftChange,
            onSave = onSave,
            onCancel = onCancel,
            modifier = modifier,
        )
    } else {
        // R-571 — see [GivenByYouName]'s own doc comment for the same reasoning.
        MarkedKeyValueRow(
            attribution = if (state.givenByYou.note != null) {
                Attribution.confirmed(state.stationId, 1.0)
            } else {
                Attribution.unknown()
            },
            key = "Note",
            value = state.givenByYou.note ?: "None",
            modifier = modifier,
            trailingMarker = {
                TextAction(
                    text = if (state.givenByYou.note != null) "Edit" else "Add",
                    onClick = onStartEditing,
                    modifier = Modifier.testTag("station-identity-add-note"),
                )
            },
        )
    }
}

@Composable
private fun InlineEditRow(
    testTag: String,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = OrtSpacing.xs)) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth().testTag(testTag),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
            modifier = Modifier.padding(top = OrtSpacing.xs),
        ) {
            TextAction(text = "Cancel", onClick = onCancel)
            TextAction(text = "Save", onClick = onSave, modifier = Modifier.testTag("$testTag-save"))
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

// -------------------------------------------------------------------------------------------
// Split (R-073) — Fail-Cluster.dc.html, reached from Station-Identity's "Split" action.
// -------------------------------------------------------------------------------------------

/**
 * `Fail-Cluster.dc.html`'s real empty state (R-272, register, halt) — reached when
 * [StationPolling.voiceSplitCandidates] has genuinely finished and found nothing (no voiceprint
 * cluster bound to this station, or its cluster has no member overs), never confused with "still
 * fetching" (a bare "Loading…" that never resolved was exactly V5 pass 2's halt). A real header
 * (with a real way back) and a named message — never a dead end.
 */
@Composable
internal fun SplitEmptyState(callsign: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        DrillInHeader(parentLabel = callsign, onBack = onBack)
        EmptyState(
            message = "One cluster, nothing to split",
            subMessage = "$callsign has no voiceprint cluster with more than one over yet.",
            modifier = Modifier.padding(OrtSpacing.lg),
        )
    }
}

/**
 * `Fail-Cluster.dc.html` (R-073): the real overs in [state]'s cluster, a checkbox per over
 * (disabled — [VoiceprintSplitOverViewState.isAnchor] — for an over where the callsign was heard
 * directly, per the artboard's own copy "those are the anchor"), and `Cancel`/`Split off N overs`.
 * No suggestion is pre-ticked (see [StationVoiceSplitViewState]'s own doc comment for why).
 */
@Composable
public fun StationSplitScreen(
    state: StationVoiceSplitViewState,
    onCancel: () -> Unit,
    onSplit: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember(state.fromVoiceprintId) { mutableStateOf(emptySet<String>()) }

    Column(modifier = modifier.fillMaxSize()) {
        DrillInHeader(parentLabel = state.callsign, onBack = onCancel)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)) {
            Text(
                text = "Split this voice",
                style = OrtType.screenTitle,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "One cluster, ${state.overs.size} overs. If some of these are not " +
                    "${state.callsign}, tick them — they become a new unidentified voice.",
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs),
            )
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(state.overs, key = { it.transmissionId }) { over ->
                SplitOverRow(
                    over = over,
                    checked = over.transmissionId in selected,
                    onToggle = {
                        selected = if (over.transmissionId in selected) {
                            selected - over.transmissionId
                        } else {
                            selected + over.transmissionId
                        }
                    },
                )
            }
        }
        Text(
            text = "Splitting marks every moved over corrected, keeps its old attribution as " +
                "superseded, and re-derives both clusters. Overs where the callsign was heard " +
                "cannot be moved — those are the anchor.",
            style = OrtType.cardBody,
            color = OrtColors.textFaint,
            modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm),
        )
        ActionBar(
            secondaryLabel = "Cancel",
            onSecondary = onCancel,
            primaryLabel = "Split off ${selected.size} overs",
            onPrimary = { onSplit(selected.toList()) },
        )
    }
}

@Composable
private fun SplitOverRow(
    over: VoiceprintSplitOverViewState,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stateWord = when {
        over.isAnchor -> "heard, cannot be moved"
        checked -> "selected to move"
        else -> "not selected"
    }
    val description = "${over.timeLabel}, ${over.transcriptText}, $stateWord" +
        if (over.corrected) ", corrected" else ""
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("split-over-${over.transmissionId}")
            .then(
                if (over.isAnchor) {
                    Modifier
                } else {
                    Modifier.clickable(role = Role.Checkbox, onClickLabel = stateWord, onClick = onToggle)
                },
            )
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        SplitCheckboxGlyph(checked = checked, enabled = !over.isAnchor)
        Spacer(modifier = Modifier.width(OrtSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AttributionMarker(attribution = over.attribution, showConfidence = false)
                Text(
                    text = over.timeLabel,
                    style = OrtType.timeFreq,
                    color = OrtColors.textTime,
                    modifier = Modifier.padding(start = OrtSpacing.sm),
                )
                Text(
                    text = if (over.isAnchor) "heard" else "inferred",
                    style = OrtType.subLine,
                    color = OrtColors.textDim,
                    modifier = Modifier.padding(start = OrtSpacing.xs),
                )
                if (over.corrected) {
                    Spacer(modifier = Modifier.width(OrtSpacing.xs))
                    Badge(text = "corrected", kind = BadgeKind.CORRECTED)
                }
            }
            Text(
                text = over.transcriptText,
                style = OrtType.transcript,
                color = OrtColors.textSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * guide §6.11's 16dp checkbox shape, drawn directly rather than through [org.ort.app.ui.components.CheckboxRow] —
 * that component has no way to render the disabled, non-interactive state an anchor over needs
 * (its checkbox is never a real choice), and this row's layout (marker + two text lines) does not
 * match `CheckboxRow`'s single-label shape either.
 */
@Composable
private fun SplitCheckboxGlyph(checked: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    val box = Modifier
        .padding(top = 2.dp)
        .size(16.dp)
    Box(
        modifier = modifier.then(
            when {
                checked && enabled -> box.background(OrtColors.accentGreen, RoundedCornerShape(4.dp))
                else -> box.border(1.5.dp, OrtColors.lineControl, RoundedCornerShape(4.dp))
            },
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked && enabled) {
            Icon(
                imageVector = OrtIcons.check,
                contentDescription = null,
                tint = OrtColors.accentOnGreen,
                modifier = Modifier.size(11.dp),
            )
        }
    }
}
