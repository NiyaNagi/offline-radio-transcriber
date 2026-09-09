package org.ort.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.Badge
import org.ort.app.ui.components.BadgeKind
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.components.ProgressBar
import org.ort.app.ui.data.DrawerCountsViewState
import org.ort.app.ui.data.pluralize
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * The drawer (`Menu.dc.html`, build-plan P13; layout brought to conformance — R-010..R-014 —
 * ui-conformance-plan WP3). Lists every *built or reachable* destination the canvas specifies, in
 * its order, replacing a tab bar — "Log, Threads, Stations, Frequencies and Earlier nights do not
 * fit in tabs, and Capture, Improve records and Settings belong in the same place"
 * (`canvas.json`'s `integrated` annotation). [ReaderDestination.SEARCH] is deliberately excluded
 * from the rendered rows — `Menu.dc.html` never lists it, and it is reached from every header's
 * magnifier instead ([ReaderDestination.SEARCH]'s own doc comment already named this as the
 * intended end state). The storage footer (D26) is the same `integrated` annotation's other
 * requirement: "a thing you actually watch on this product".
 */
@Composable
public fun ReaderDrawerContent(
    current: ReaderDestination,
    sessionHeader: DrawerSessionHeaderViewState,
    storage: StorageFooterViewState,
    badges: DrawerBadgeViewState,
    counts: DrawerCountsViewState,
    onSelect: (ReaderDestination) -> Unit,
    /** The Improve records count-pill (guide §6.4) — `null` until WP10 supplies a real count. */
    improveRecordsCount: Int? = null,
) {
    ModalDrawerSheet {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            DrawerSessionHeader(sessionHeader)
            Divider(horizontalInset = OrtSpacing.lg)

            // Scrollable: build-plan P15 grew the drawer past nine real rows, and a fixed-height
            // Column silently clips whatever falls below the visible drawer height rather than
            // failing loudly — confirmed by this project's own ReaderAccessibilityTest, which
            // asserts every rendered row is actually reachable. `weight(1f)` keeps the storage
            // footer pinned below the scroll, matching `Menu.dc.html`'s own fixed footer.
            val firstTrailing = ReaderDestination.entries.firstOrNull { it in ReaderDestination.trailingGroup }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .testTag("drawer-rows")
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = OrtSpacing.md, vertical = OrtSpacing.md),
            ) {
                ReaderDestination.entries
                    .filter { it != ReaderDestination.SEARCH }
                    .forEach { destination ->
                        if (destination == firstTrailing) {
                            Divider(horizontalInset = 0.dp, verticalInset = OrtSpacing.md)
                        }
                        DrawerRow(
                            destination = destination,
                            selected = destination == current,
                            trailing = trailingFor(destination, badges, counts, improveRecordsCount),
                            onSelect = onSelect,
                        )
                    }
            }
            StorageFooter(storage)
        }
    }
}

/** guide §3 `line/default`, one row divider drawn either the drawer's own way or the trailing group's. */
@Composable
private fun Divider(horizontalInset: Dp, verticalInset: Dp = 0.dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalInset, vertical = verticalInset)
            .height(1.dp)
            .background(OrtColors.lineDefault),
    )
}

@Composable
private fun DrawerSessionHeader(state: DrawerSessionHeaderViewState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = OrtSpacing.lg, end = OrtSpacing.lg, top = 18.dp, bottom = 16.dp)
            .semantics(mergeDescendants = true) { contentDescription = "${state.title}, ${state.rigLabel}" },
    ) {
        Text(text = state.title, style = OrtType.drawerTitle, color = OrtColors.textHigh)
        Text(
            text = state.rigLabel,
            style = OrtType.cardBody,
            color = OrtColors.textTime,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** R-010/R-022 (FR-UI-7): which destinations carry a trailing figure, and what kind. */
private sealed interface DrawerRowTrailing {
    data class Count(val text: String) : DrawerRowTrailing
    data class Live(val elapsedLabel: String) : DrawerRowTrailing
    data class CountPill(val text: String) : DrawerRowTrailing
}

/** R-163: `Threads`' own honest "nothing to report" marker — never a fabricated `0` while
 * `threadId` is always null. [drawerRowDescription] below skips it rather than reading it aloud as
 * literal punctuation. */
private const val NO_COUNT_PLACEHOLDER = "—"

private fun trailingFor(
    destination: ReaderDestination,
    badges: DrawerBadgeViewState,
    counts: DrawerCountsViewState,
    improveRecordsCount: Int?,
): DrawerRowTrailing? = when (destination) {
    ReaderDestination.LOG -> badges.logCount?.let { DrawerRowTrailing.Count(it.toString()) }
    ReaderDestination.THREADS -> DrawerRowTrailing.Count(NO_COUNT_PLACEHOLDER)
    ReaderDestination.STATIONS -> DrawerRowTrailing.Count(counts.stationCount.toString())
    ReaderDestination.FREQUENCIES -> DrawerRowTrailing.Count(counts.frequencyCount.toString())
    ReaderDestination.CAPTURE -> badges.captureElapsedLabel?.let { DrawerRowTrailing.Live(it) }
    ReaderDestination.IMPROVE_RECORDS -> improveRecordsCount?.let { DrawerRowTrailing.CountPill(it.toString()) }
    else -> null
}

/**
 * R-546 (register, cf. R-380): the row's own spoken description — [destination]'s label plus
 * whatever trailing figure or badge is visibly shown next to it (FR-UI-7's own count is exactly
 * the kind of fact a sighted operator reads and a screen-reader user must not silently lose).
 * [NO_COUNT_PLACEHOLDER] carries nothing to announce, so it is skipped rather than read as literal
 * punctuation ("Threads, dash"). [ReaderDestination.IMPROVE_RECORDS] reads "Improve, N records"
 * rather than "Improve records, N records" once a real count exists — the visible row still shows
 * the destination's full [ReaderDestination.label] ("Improve records"); this is the one place the
 * spoken and visible text deliberately differ, so "records" is never said twice.
 */
private fun drawerRowDescription(destination: ReaderDestination, trailing: DrawerRowTrailing?): String {
    val trailingPhrase = when (trailing) {
        is DrawerRowTrailing.Count -> trailing.text.takeIf { it != NO_COUNT_PLACEHOLDER }
        is DrawerRowTrailing.Live -> "${trailing.elapsedLabel} elapsed"
        is DrawerRowTrailing.CountPill -> pluralize(trailing.text.toInt(), "record")
        null -> null
    }
    val spokenLabel = if (destination == ReaderDestination.IMPROVE_RECORDS && trailingPhrase != null) {
        "Improve"
    } else {
        destination.label
    }
    val notBuiltPhrase = "not built".takeUnless { destination.hasScreen }
    return listOfNotNull(spokenLabel, trailingPhrase, notBuiltPhrase).joinToString(", ")
}

private fun iconFor(destination: ReaderDestination): ImageVector = when (destination) {
    ReaderDestination.NOW -> OrtIcons.now
    ReaderDestination.LOG -> OrtIcons.log
    ReaderDestination.SEARCH -> OrtIcons.search
    ReaderDestination.THREADS -> OrtIcons.threads
    ReaderDestination.STATIONS -> OrtIcons.stations
    ReaderDestination.FREQUENCIES -> OrtIcons.frequencies
    ReaderDestination.EARLIER_NIGHTS -> OrtIcons.earlierNights
    ReaderDestination.CAPTURE -> OrtIcons.capture
    ReaderDestination.IMPROVE_RECORDS -> OrtIcons.improve
    ReaderDestination.SETTINGS -> OrtIcons.settings
}

/**
 * R-010/R-011/R-014: 18dp [OrtIcons], `accent/green` selected / `text/icon-dim` otherwise; the
 * selected row on `bg/selected` with 8dp radius and `text/bright`; a 44dp floor; `Role.Tab`
 * selection semantics that actually announce "selected" (the pre-R-014 row used `selectable` with
 * no `Role`, and a separate, non-merging `.semantics{}` block that hid the selectable node's own
 * `selected` state from TalkBack — merging the whole row into one accessibility node here is the
 * fix, not just adding a role); [ReaderDestination.hasScreen] `false` rows render `text/disabled`
 * with a trailing "not built" `subLine` (R-013), never silently identical to a built row.
 *
 * R-546 (register, cf. R-380): reproduced the R-380 defect verbatim — a plain, trailing
 * `semantics(mergeDescendants = true) { contentDescription = ... }` does not reliably keep the
 * description on the *clickable* node itself once real child content (this row's own `Text`s) sits
 * beneath it on a real device, and the description it built ("Open <label>") never carried the
 * trailing count/badge a sighted operator reads next to it either. `clearAndSetSemantics` is this
 * package's own confirmed fix, the identical shape `FilterChip`'s selectable `Role.Checkbox` case
 * already established (`Controls.kt`, read before writing this): `selectable()` above still
 * supplies the click action and `Role.Tab` for touch/visuals, but `clearAndSetSemantics` erases its
 * own semantics config the same way it erases every descendant's, so `selected`/`role`/`onClick`
 * are re-declared explicitly in the block below rather than left to `selectable()` alone.
 */
@Composable
private fun DrawerRow(
    destination: ReaderDestination,
    selected: Boolean,
    trailing: DrawerRowTrailing?,
    onSelect: (ReaderDestination) -> Unit,
) {
    val iconTint = if (selected) OrtColors.accentGreen else OrtColors.textIconDim
    val labelColor = when {
        !destination.hasScreen -> OrtColors.textDisabled
        selected -> OrtColors.textBright
        else -> OrtColors.textBody
    }
    val description = drawerRowDescription(destination, trailing)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .then(
                if (selected) {
                    Modifier.background(OrtColors.bgSelected, RoundedCornerShape(8.dp))
                } else {
                    Modifier
                },
            )
            .selectable(selected = selected, onClick = { onSelect(destination) }, role = Role.Tab)
            .testTag("drawer-row-${destination.name}")
            .clearAndSetSemantics {
                contentDescription = description
                // R-380 correction (WP2, gate-blocking) — see `FilterChip`'s own doc comment.
                text = AnnotatedString(description)
                this.selected = selected
                role = Role.Tab
                onClick(label = null) {
                    onSelect(destination)
                    true
                }
            }
            .padding(horizontal = OrtSpacing.md, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Icon(
            imageVector = iconFor(destination),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = destination.label,
                style = OrtType.rowTitle.copy(fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal),
                color = labelColor,
            )
            if (!destination.hasScreen) {
                Text(text = "not built", style = OrtType.subLine, color = OrtColors.textDisabled)
            }
        }
        DrawerRowTrailingContent(trailing)
    }
}

@Composable
private fun DrawerRowTrailingContent(trailing: DrawerRowTrailing?) {
    when (trailing) {
        is DrawerRowTrailing.Count ->
            Text(text = trailing.text, style = OrtType.signal, color = OrtColors.textFigure)

        is DrawerRowTrailing.Live -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(modifier = Modifier.size(6.dp).background(OrtColors.accentGreen, CircleShape))
            Text(text = trailing.elapsedLabel, style = OrtType.signal, color = OrtColors.accentGreenDim)
        }

        is DrawerRowTrailing.CountPill -> Badge(text = trailing.text, kind = BadgeKind.COUNT)
        null -> Unit
    }
}

/**
 * R-012: one baseline row ("Audio 38.2 GB" `text/dim` left, "of N" mono `text/figure` right) over
 * a [ProgressBar] — only when [StorageFooterViewState.budgetBytes] is real (WP10). Until then, a
 * single honest line: "Audio 0.8 GB · no budget set", no bar drawn against a device total that was
 * never the budget (audit F-020's own finding — this is that fix's layout, not a new claim).
 */
@Composable
private fun StorageFooter(storage: StorageFooterViewState, modifier: Modifier = Modifier) {
    val budgetBytes = storage.budgetBytes
    val usedLabel = storage.audioUsedBytes.toGigabyteLabel()
    val description = if (budgetBytes != null) {
        "Audio $usedLabel of ${budgetBytes.toGigabyteLabel()}"
    } else {
        "Audio $usedLabel, no budget set"
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(OrtColors.lineDefault))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = OrtSpacing.lg, end = OrtSpacing.lg, top = 14.dp, bottom = 20.dp)
                .semantics(mergeDescendants = true) { contentDescription = description },
        ) {
            if (budgetBytes != null) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "Audio $usedLabel",
                        style = OrtType.chip,
                        color = OrtColors.textDim,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "of ${budgetBytes.toGigabyteLabel()}",
                        style = OrtType.signal,
                        color = OrtColors.textFigure,
                    )
                }
                Spacer(modifier = Modifier.height(7.dp))
                ProgressBar(progress = storage.audioUsedBytes.toFloat() / budgetBytes.toFloat())
            } else {
                Text(text = "Audio $usedLabel · no budget set", style = OrtType.chip, color = OrtColors.textDim)
            }
        }
    }
}
