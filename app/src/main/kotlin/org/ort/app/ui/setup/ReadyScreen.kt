package org.ort.app.ui.setup

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.data.ModelCatalog
import org.ort.app.ui.data.ModelId
import org.ort.app.ui.data.ModelRowStatus
import org.ort.app.ui.data.ModelRowViewState
import org.ort.app.ui.data.ModelsViewState
import org.ort.app.ui.settings.SettingsPolling
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType
import org.ort.core.capture.CaptureMode
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
 * three separate, non-merged `Text`/marker nodes with no focus action of their own, so TalkBack's
 * linear swipe traversal skipped every one of S12's five facts entirely (there was no accessibility
 * node to land on).
 *
 * R-342 (validator pass 4, halt): the first fix here (wrapping each row's own outer `Row` in
 * `Modifier.focusable()` + a lone `Modifier.semantics { contentDescription = ... }` on the trailing
 * status `Text`, relying on it merging up into [KeyValueRow]'s own native R-265 merge boundary)
 * passed every Robolectric test written for it, then reproduced on a real device anyway
 * (`setup-verified/S12-done-pass4.png`: `content-desc="Input, USB Audio Device"` with `verified` as
 * its *own separate* node, not folded in) — confirmed directly that `ComposeTestRule`'s own semantics
 * queries read Compose's internal `SemanticsNode` tree, a different representation from the real
 * `AccessibilityNodeInfo` tree Android's own accessibility bridge builds, and the two disagree on
 * whether a lone-`contentDescription` leaf with no `mergeDescendants` of its own gets absorbed by an
 * ancestor's merge. [ReadySetupRow] is the real fix, re-dumped and confirmed on-device after
 * changing it: [KeyValueRow]'s own explicit `contentDescription` (built from `key`/`value`/`subLine`
 * only) cannot see into its `trailingMarker` slot at all, on Robolectric or a real device alike, so
 * this screen never relies on that merge seeing [ReadyRow.statusText] — see [ReadySetupRow]'s own
 * doc comment for the three concrete shapes (no action, an action alone, both together) and exactly
 * which parts of the tree each one clears and rebuilds versus leaves to [KeyValueRow] natively.
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
        state.rows.forEach { row -> ReadySetupRow(row) }
        Text(
            text = readyFooterText(state.rows),
            style = OrtType.cardBody,
            color = OrtColors.textDim,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * R-812 (reviewer finding, halt): the footer used to hardcode "The two amber items are worth
 * fixing" regardless of how many rows were actually amber — a fabricated count, wrong the moment
 * anything but exactly two rows needed fixing (constitution I: never assert a number the state
 * does not back). `Setup-Done.dc.html`'s own first sentence ("Every model this app can use shipped
 * with it and was verified against its checksum on first launch — nothing was downloaded.") is
 * unconditional; its second sentence is generated from [rows] — dropped entirely when nothing is
 * amber, singular for exactly one, and named with the real count otherwise.
 */
internal fun readyFooterText(rows: List<ReadyRow>): String {
    val firstSentence = "Every model this app can use shipped with it and was verified against " +
        "its checksum on first launch — nothing was downloaded."
    val amberCount = rows.count { !it.ok }
    val secondSentence = when (amberCount) {
        0 -> null
        1 -> "The amber item is worth fixing before an overnight run."
        else -> "The $amberCount amber items are worth fixing before an overnight run."
    }
    return listOfNotNull(firstSentence, secondSentence).joinToString(" ")
}

/**
 * R-342 (validator pass 4, halt): one real device dump (`setup-verified/S12-done-pass4.png`)
 * confirmed a lone `Modifier.semantics { contentDescription = ... }` on a plain descendant `Text`
 * does **not** fold into an ancestor's own merged `AccessibilityNodeInfo` — only
 * `Modifier.clearAndSetSemantics` on the row's own outer node, replacing its whole subtree's
 * accessibility surface outright, actually produced one clean merged node there.
 *
 * R-361 (validator pass 5, halt): the first fix left [ReadyRow.actionLabel]-only rows
 * (Overnight/Radio/Model) on [KeyValueRow]'s own native R-265 merge, reasoning that it already
 * produced one correct node — a follow-up real device dump proved that wrong: the *focusable* outer
 * node carried an **empty** description while a separate, non-focusable child at identical bounds
 * carried the real text, so TalkBack landing on the only reachable stop announced nothing.
 * [KeyValueRow]'s own merge (`Rows.kt`) is therefore never relied on here at all any more, action or
 * not — [ReadySetupRow] now gives every row the identical, uniform shape: the marker + [KeyValueRow]
 * (carrying only [ReadyRow.statusText], never [ReadyRow.actionLabel], in its `trailingMarker`) are
 * wrapped in [Modifier.focusable] + [Modifier.clearAndSetSemantics], announcing
 * [readyRowFactsDescription] as one real, focusable node regardless of whether the row has an
 * action — and [ReadyRow.actionLabel], whenever present, renders as a genuine sibling *outside* that
 * boundary, its own separate real button stop, reached straight after the facts stop. Re-dumped on
 * a real device after this change — see this package's report for the three quoted lines.
 */
@Composable
private fun ReadySetupRow(row: ReadyRow) {
    Row(
        // R-866 (register, validator V9): the Models row's amber `Install` measured 95px/34.3dp
        // tall on a real device -- under the 44dp floor every other action met -- with no
        // reproducible cause found in Robolectric across an isolated render, a real-device-width
        // render and a forced multi-line wrap (this file's own report has the fuller account); a
        // `requiredHeightIn` floor on the row itself, first in the chain (`Controls.kt`'s own
        // R-510-class doc comment on why `requiredHeightIn` before later modifiers is what makes
        // this actually hold), closes the gap defensively at the row level in addition to
        // `TextAction`'s own existing floor, so the row's whole clickable/visual band -- action
        // included -- can never render shorter than 44dp regardless of its sibling content's shape.
        modifier = Modifier
            .fillMaxWidth()
            .requiredHeightIn(min = 44.dp)
            .testTag("setup-ready-row-${row.label.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .focusable()
                .clearAndSetSemantics { contentDescription = readyRowFactsDescription(row) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (row.ok) {
                Box(modifier = Modifier.size(9.dp).background(OrtColors.accentGreen, CircleShape))
            } else {
                AmberHalfMarker()
            }
            ReadyFactColumns(row, modifier = Modifier.weight(1f).padding(start = 12.dp))
        }
        // R-361: TextAction is always a genuine sibling *outside* the clearAndSetSemantics boundary
        // above, never inside KeyValueRow's own trailingMarker -- confirmed on a real device
        // (`setup-verified/S12-done-pass5.png`) that leaving it inside produced a focusable outer
        // node with an EMPTY description while a separate non-focusable child at the same bounds
        // carried the real text; TalkBack landing on the (only reachable) focusable node announced
        // nothing. Rendered here, it stays its own real button stop, reached straight after the
        // facts stop -- the same two-stop shape every row now shares, action or not.
        row.actionLabel?.let { actionLabel ->
            TextAction(text = actionLabel, onClick = { row.onAction?.invoke() })
        }
    }
}

/** The row's own facts alone, e.g. `"Input, USB Audio Device, verified"` or `"Overnight, Battery
 * exemption skipped"` — never [ReadyRow.actionLabel], which is always its own separate real button
 * stop, never part of a row's own announcement ([ReadySetupRow]'s own doc comment has the full
 * account). What [ReadySetupRow] actually announces for every row shape. */
internal fun readyRowFactsDescription(row: ReadyRow): String =
    listOfNotNull(row.label, row.value, row.statusText).joinToString(", ")

/**
 * R-882 (register, validator V11, halt): replaces WP2's shared `KeyValueRow` for this screen's own
 * fact rows — at font scale 2.0, the Radio row (uniquely carrying both a [ReadyRow.statusText] and
 * an [ReadyRow.actionLabel] together, [radioRow]'s own R-285 doc comment) collapsed its label to
 * invisible and its value to one letter per line
 * (`results/ui-audit/validation/V11/rig-bt-connected/S11-radio-verified.png`+`.xml`) — the exact
 * defect class R-863 already found and fixed for `TextAction`: an unweighted sibling with no floor
 * on its own reported width can be squeezed to (or past) zero by however a `Row` divides space
 * among several non-weighted children, starving whichever one this screen actually needs full
 * width for. The **label** gets `wrapContentWidth(unbounded = true)` — the identical technique
 * [org.ort.app.ui.components.TextAction] already uses — so it always measures at its own true,
 * single-line width no matter how little the incoming constraint offers, applied *outside* (not
 * instead of) the `widthIn(min = 96.dp)` floor R-226 already established so every row's value
 * column still starts at the same x position; [ReadyRow.statusText] gets the identical protection.
 * The **value** stays the one genuinely flexible sibling (`weight(1f)`) — once the label/status
 * never over- or under-report their own true width, the value reliably receives whatever real
 * space is left, wrapping at word boundaries the normal way `Text` already does once given a real
 * width to wrap within, rather than collapsing to nothing and falling back to one letter per line.
 */
@Composable
private fun ReadyFactColumns(row: ReadyRow, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = row.label,
            style = OrtType.control,
            color = OrtColors.textDim,
            modifier = Modifier
                .wrapContentWidth(unbounded = true)
                .widthIn(min = 96.dp)
                .testTag("setup-ready-row-${row.label.lowercase()}-label"),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = row.value,
                style = OrtType.control,
                color = OrtColors.textHigh,
                modifier = Modifier.testTag("setup-ready-row-${row.label.lowercase()}-value"),
            )
        }
        row.statusText?.let { statusText ->
            Text(
                text = statusText,
                style = OrtType.signal,
                color = OrtColors.accentGreenDim,
                modifier = Modifier.wrapContentWidth(unbounded = true).padding(start = 8.dp),
            )
        }
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

/** The "Fix"/"Install"/"Change" callbacks S12's rows can carry, bundled so [readyRowsFor] stays
 * under detekt's parameter-count threshold. */
public data class ReadyActions(
    val onFixInput: () -> Unit,
    val onFixLevel: () -> Unit,
    val onFixOvernight: () -> Unit,
    val onFixRadio: () -> Unit,
    /** R-285 (validator pass 3): distinct from [onFixRadio] — [SetupActivity.onChangeRadio]'s own
     * clear-then-navigate, the same callback S11's own "Change radio" ([RadioVerifiedScreen])
     * already wires, not merely the bare step jump [onFixRadio] is for the two already-broken
     * (`Stale`/`Absent`) rig readings. Used on a row that is already `ok` — see [radioRow]'s own
     * doc comment for why this row needed a target at all before this. */
    val onChangeRadio: () -> Unit,
    /** Opens `ReaderActivity` at its `SETTINGS` destination (WP9 round 3) — see
     * [SetupActivity.onInstallModel]'s own doc comment for exactly where it lands and why (Settings'
     * root, not its `Assets` sub-screen directly — no entry point for that exists yet). */
    val onInstallModel: () -> Unit,
    /** D33/E2-E13 — S12's new leading Mode row's `Change` action, routing to S00
     * ([SetupActivity.onChangeMode]). */
    val onChangeMode: () -> Unit,
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
    modelsState: ModelsViewState,
    actions: ReadyActions,
): List<ReadyRow> = listOf(
    modeRow(store, actions.onChangeMode),
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
    radioRow(store, rigStatus, actions.onFixRadio, actions.onChangeRadio),
    modelsRow(modelsState, actions.onInstallModel),
)

/**
 * D33/E2-E13 — S12's new leading row (`Setup-Done.dc.html`, redrawn 2026-09-10): the mode chosen
 * at S00, with `Change` routing back to it. The value line names the mode and, where it says
 * something the mode's own label does not already say, the audio-route fact next to it
 * (`"Bluetooth radio · audio by cable"` — the board's own example) — `null`/overridden cases fall
 * back to the mode's label alone rather than fabricating a segment (constitution I).
 */
private fun modeRow(store: SetupStore, onChangeMode: () -> Unit): ReadyRow {
    val mode = store.captureMode
    return ReadyRow(
        label = "Mode",
        value = mode?.let { modeValueLine(it, store) } ?: "Not set",
        ok = mode != null,
        statusText = null,
        actionLabel = "Change",
        onAction = onChangeMode,
    )
}

private fun modeValueLine(mode: CaptureMode, store: SetupStore): String {
    val audioSegment = when {
        store.modeOverriddenAudio -> "audio route changed"
        mode == CaptureMode.LOCAL_MICROPHONE -> "room audio"
        mode == CaptureMode.BLUETOOTH_RADIO -> "audio by cable"
        else -> null
    }
    return listOfNotNull(mode.operatorLabel, audioSegment).joinToString(" · ")
}

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

/**
 * R-285 (validator pass 3, register): S09..S11 (`Setup-Rig*.dc.html`) had no way back in from S12
 * once a radio was already chosen — an `ok` reading (a deliberate "no radio" choice, or a genuinely
 * `Connected` rig) rendered with no action at all, so the only route was uninstalling/resetting
 * setup entirely. Both `ok` branches below now also carry [ReadyRow.actionLabel] `"Change"`, wired
 * to [ReadyActions.onChangeRadio] — the `Connected` branch is the one case in this whole screen
 * where [ReadyRow.statusText] ("verified") and [ReadyRow.actionLabel] ("Change") are both present
 * together (this row's own [ReadyScreen] `trailingMarker` rendering handles that pairing
 * explicitly; every other row here still only ever sets one or neither, per [readyRowDescription]'s
 * own doc comment).
 */
private fun radioRow(
    store: SetupStore,
    rigStatus: RigStatus.State,
    onFixRadio: () -> Unit,
    onChangeRadio: () -> Unit,
): ReadyRow = when (store.radioChoice) {
    RadioChoice.NONE, null -> ReadyRow(
        label = "Radio",
        value = store.manualFrequencyHz?.let { "No radio · %.3f MHz by hand".format(it / 1_000_000.0) }
            ?: "No radio · frequency by hand",
        ok = true,
        statusText = null,
        actionLabel = "Change",
        onAction = onChangeRadio,
    )
    RadioChoice.TH_D75A, RadioChoice.OTHER_CAT_RIG -> radioRowForCatRig(rigStatus, onFixRadio, onChangeRadio)
}

private fun radioRowForCatRig(
    rigStatus: RigStatus.State,
    onFixRadio: () -> Unit,
    onChangeRadio: () -> Unit,
): ReadyRow = when (rigStatus) {
    is RigStatus.State.Connected -> {
        val bandCount = rigStatus.bands.size
        // R-882: the same manufacturer-prefix strip R-845 (CF06), R-903 (S11) and R-941 (S09b)
        // already reuse rather than duplicate — "Kenwood TH-D75A" -> "TH-D75A", also shortening
        // the value text this row's own new ReadyFactColumns must wrap at font scale 2.0.
        val descriptor = SettingsPolling.stripManufacturerPrefix(rigStatus.descriptor)
        ReadyRow(
            label = "Radio",
            value = "$descriptor · $bandCount band${if (bandCount == 1) "" else "s"}",
            ok = true,
            statusText = "verified",
            actionLabel = "Change",
            onAction = onChangeRadio,
        )
    }
    is RigStatus.State.Stale -> ReadyRow(
        label = "Radio",
        value = "${SettingsPolling.stripManufacturerPrefix(rigStatus.lastKnown.descriptor)} · stale",
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

/**
 * D33/E2-E13, WPG follow-up: "Models" (renamed from "Model") reads WPG's real bundled-asset state
 * (`ModelsController.currentState`, `app/.../ui/data/ModelsViewData.kt`) — [modelsState] is built by
 * [SetupActivity] (the one place allowed to touch `Context`) and handed in here as plain data, per
 * this file's own established pattern for every other row.
 *
 * R-862 (register, halt, AC-138): this row used to be an all-or-nothing gate requiring *every*
 * [ModelId] — [ModelId.LLM_GEMMA3_1B] included — to read [ModelRowStatus.INSTALLED] before it would
 * ever show green, so a no-`HF_TOKEN` build (Gemma genuinely never fetched at build time, per
 * [org.ort.app.ui.data.ModelCatalogEntry.gated]) read "No transcription model yet" forever even with
 * every real transcription asset installed and verified — a real production gap
 * (`results/ui-audit/README.md`'s own R-862 diagnosis has the fuller account), not a scenario
 * artefact. Amber is now reserved for what it should have always meant: an asset [requiredRows]
 * needs for tier ≥1 transcription (every [ModelId] but the gated, tier-3-only prose-digest model)
 * missing or unverified — [ModelsScreen.kt]'s own `ModelId.LLM_GEMMA3_1B` carve-out
 * (`rows.filterNot { it.id == ModelId.LLM_GEMMA3_1B }`) is the established precedent this mirrors.
 * Once that required set is complete, the row is green regardless of Gemma's own state — [rows]
 * missing it altogether (a synthetic single-row test double, never real production output) reads the
 * plain "N bundled" line unchanged; a real 5-row state with Gemma genuinely absent names it ("N of M
 * bundled · ready — Gemma 3 1B not in this build", the board's own worked example, `Setup-Done.dc.html`)
 * rather than silently overstating "N bundled" as if the model even shipped.
 */
private fun modelsRow(modelsState: ModelsViewState, onInstallModel: () -> Unit): ReadyRow {
    val rows = modelsState.rows
    if (rows.isEmpty()) {
        return noModelInstalledRow("No transcription model yet", onInstallModel)
    }
    fun isReady(row: ModelRowViewState) = row.bundled && row.status == ModelRowStatus.INSTALLED
    val requiredRows = rows.filterNot { it.id == ModelId.LLM_GEMMA3_1B }
    val missingRequired = requiredRows.filterNot(::isReady)
    if (missingRequired.isNotEmpty()) {
        val value = if (missingRequired.size == requiredRows.size) {
            "No transcription model yet"
        } else {
            "No transcription model yet — ${missingRequired.first().label} not installed"
        }
        return noModelInstalledRow(value, onInstallModel)
    }
    val gemmaRow = rows.firstOrNull { it.id == ModelId.LLM_GEMMA3_1B }
    if (gemmaRow == null || isReady(gemmaRow)) {
        return ReadyRow(
            label = "Models",
            value = "${rows.size} bundled · checksums verified",
            ok = true,
            statusText = "ready",
            actionLabel = null,
        )
    }
    val gemmaReason = if (ModelCatalog.entry(ModelId.LLM_GEMMA3_1B).gated) "not in this build" else "not installed"
    return ReadyRow(
        label = "Models",
        value = "${requiredRows.size} of ${rows.size} bundled · ready — Gemma 3 1B $gemmaReason",
        ok = true,
        statusText = "ready",
        actionLabel = null,
    )
}

private fun noModelInstalledRow(value: String, onInstallModel: () -> Unit): ReadyRow = ReadyRow(
    label = "Models",
    value = value,
    ok = false,
    statusText = null,
    actionLabel = "Install",
    onAction = onInstallModel,
)
