package org.ort.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.components.TextField
import org.ort.app.ui.data.LogFrequencyEditor
import org.ort.app.ui.data.LogFrequencyHeaderAction
import org.ort.app.ui.data.LogFrequencyHeaderViewState
import org.ort.app.ui.data.megahertzFieldText
import org.ort.app.ui.data.parseMegahertzToHz
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/** The header's own stable test tags (constitution II, R-1160/R-1070: assert a tag, never prose,
 * and never a string that the navigation drawer also happens to render). */
public const val LOG_FREQUENCY_HEADER_TEST_TAG: String = "log-frequency-header"
public const val LOG_FREQUENCY_HEADER_VALUE_TEST_TAG: String = "log-frequency-header-value"
public const val LOG_FREQUENCY_HEADER_ACTION_TEST_TAG: String = "log-frequency-header-action"
public const val LOG_FREQUENCY_HEADER_NOTE_TEST_TAG: String = "log-frequency-header-note"
public const val LOG_FREQUENCY_EDITOR_TEST_TAG: String = "log-frequency-editor"
public const val LOG_FREQUENCY_EDITOR_FIELD_TEST_TAG: String = "log-frequency-editor-field"
public const val LOG_FREQUENCY_EDITOR_SAVE_TEST_TAG: String = "log-frequency-editor-save"
public const val LOG_FREQUENCY_EDITOR_CANCEL_TEST_TAG: String = "log-frequency-editor-cancel"
public const val LOG_FREQUENCY_EDITOR_CLEAR_TEST_TAG: String = "log-frequency-editor-clear"
public const val LOG_FREQUENCY_EDITOR_NOTE_TEST_TAG: String = "log-frequency-editor-note"
public const val LOG_FREQUENCY_EDITOR_VALIDATION_TEST_TAG: String = "log-frequency-editor-validation"

/** What the tap says when it refuses, naming the unit and an example — carried over verbatim in
 * substance from `RadioUsbScreen`'s own R-1170 refusal, because "enter a frequency" alone does not
 * say whether `145230000`, `145.230` or `145,230` is wanted and only one of those parses
 * ([org.ort.app.ui.data.parseMegahertzToHz]). R-1170's rule applies here too: the primary is never
 * disabled for validation state — it stays lit and the tap explains. */
public const val LOG_FREQUENCY_NOT_PARSEABLE: String =
    "Enter the frequency in megahertz — for example 145.230."

/**
 * R-1167, D58, AC-202: everything the Log's frequency header needs from its host that is not part
 * of [LogFrequencyHeaderViewState] itself — the in-place editor's own transient state and its
 * callbacks. Bundled into one value, the same shape `FailureHostActions` already establishes, so
 * [LogScreen] does not grow eight parameters (detekt's `LongParameterList`).
 *
 * Every field is defaulted, so a caller that does not carry the header at all — every existing
 * [LogScreen] call site and test — compiles unchanged.
 */
public data class LogFrequencyHeaderHost(
    public val editing: Boolean = false,
    public val draft: String = "",
    public val validationMessage: String? = null,
    /** The header row's own single action — [LogFrequencyHeaderAction.EDIT] opens the editor,
     * [LogFrequencyHeaderAction.CLEAR_OVERRIDE] stops a hand-entered value overriding a reporting
     * radio. The host decides which, from the same view state it was given. */
    public val onAction: () -> Unit = {},
    public val onDraftChange: (String) -> Unit = {},
    public val onSave: () -> Unit = {},
    public val onClear: () -> Unit = {},
    public val onCancel: () -> Unit = {},
)

/**
 * R-1167/D58/AC-202: the editor's own transient state and every callback that writes, assembled in
 * one place.
 *
 * Extracted from [LogContent] rather than inlined there: the editor is a small state machine
 * (open/closed, a half-typed draft, a refusal earned by a tap) and folding it into that composable's
 * body made one function responsible both for polling the Log and for editing a configuration value
 * — which is exactly the second responsibility detekt's `LongMethod` was pointing at.
 *
 * [currentAction] is read *at tap time*, not captured: the two actions are genuinely different and
 * must never collapse into one. A reporting rig gets its override cleared and nothing else — it is
 * never offered an edit, because `RigSupervisor.frequencyForTransmission` gives the manual value
 * precedence over the radio (FR-RIG-8), so a typed value beside a live rig would silently relabel
 * every over. [onWritten] is called after each real write so the caller can re-read the store
 * without waiting out its poll interval.
 */
@Composable
internal fun rememberLogFrequencyHeaderHost(
    editor: LogFrequencyEditor,
    currentAction: () -> LogFrequencyHeaderAction?,
    onWritten: () -> Unit,
): LogFrequencyHeaderHost {
    // `rememberSaveable` for the two a rotation must not silently discard — a half-typed frequency,
    // and the fact the editor was open. A plain `remember` for the refusal message, which is the
    // answer to a tap and is correctly re-earned rather than restored.
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var validation by remember { mutableStateOf<String?>(null) }

    return LogFrequencyHeaderHost(
        editing = editing,
        draft = draft,
        validationMessage = validation,
        onAction = {
            if (currentAction() == LogFrequencyHeaderAction.CLEAR_OVERRIDE) {
                editor.save(null)
                onWritten()
            } else {
                draft = editor.facts().activeManualHz?.let(::megahertzFieldText) ?: ""
                validation = null
                editing = true
            }
        },
        onDraftChange = {
            draft = it
            validation = null
        },
        onSave = {
            val hz = parseMegahertzToHz(draft)
            if (hz == null) {
                // R-1170: the control stayed lit and the tap explains. Nothing is written until the
                // text actually parses, so the store never sees a fabricated or half-typed value.
                validation = LOG_FREQUENCY_NOT_PARSEABLE
            } else {
                editor.save(hz)
                editing = false
                validation = null
                onWritten()
            }
        },
        onClear = {
            editor.save(null)
            draft = ""
            editing = false
            validation = null
            onWritten()
        },
        onCancel = {
            editing = false
            validation = null
        },
    )
}

/**
 * The Log header's frequency row, and its in-place editor.
 *
 * Placed in the header — between the screen title and the quick-filter chip row — deliberately:
 * it labels every row in the list beneath it, and the header is the one part of this screen the
 * `LazyColumn` cannot scroll away. It sits *above* the chips because it is a fact about the data;
 * the applied-filter statement sits *below* them because it is a fact about the filter.
 */
@Composable
internal fun LogFrequencyHeaderRow(state: LogFrequencyHeaderViewState, host: LogFrequencyHeaderHost) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.xs)
            .testTag(LOG_FREQUENCY_HEADER_TEST_TAG),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // `weight(1f)`, filling — the action belongs at the row's own right edge, exactly like
            // the `Log` / `Filter` row directly above it, which is also what `Log.dc.html` draws.
            // R-863/R-874's rule still holds and is why this is safe: the *unweighted* `TextAction`
            // is measured first by Compose's `Row` policy, so it gets first claim on the row's real
            // width and this column only ever receives the remainder — `fill` changes where the
            // column's own box ends, never how much width either child is given.
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Frequency", style = OrtType.sectionLabel, color = OrtColors.textFaint)
                Text(
                    text = listOfNotNull(state.valueLabel, state.sourceLabel).joinToString(" · "),
                    style = OrtType.control.copy(fontFamily = FontFamily.Monospace),
                    color = OrtColors.textHigh,
                    modifier = Modifier.testTag(LOG_FREQUENCY_HEADER_VALUE_TEST_TAG),
                )
            }
            TextAction(
                text = state.actionLabel,
                onClick = host.onAction,
                modifier = Modifier.testTag(LOG_FREQUENCY_HEADER_ACTION_TEST_TAG),
            )
        }
        state.note?.let { note ->
            Text(
                text = note,
                style = OrtType.subLine,
                // Amber only for a real, recoverable problem (the guide's own rule): an override
                // that is relabelling every over, or an edit that has not taken effect. "No radio is
                // reporting one" is a fact, not a fault — capture runs fine without a frequency,
                // which is exactly what R-1167 established — so it reads as a plain sub-line.
                color = if (state.noteIsWarning) OrtColors.accentAmberText else OrtColors.textDim,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = OrtSpacing.xs)
                    .testTag(LOG_FREQUENCY_HEADER_NOTE_TEST_TAG),
            )
        }
        if (host.editing) FrequencyEditor(state, host)
    }
}

/** Split out of [LogFrequencyHeaderRow] so both stay under detekt's `LongMethod`, and because the
 * editor is a genuinely separate responsibility from the row that summons it. */
@Composable
private fun FrequencyEditor(state: LogFrequencyHeaderViewState, host: LogFrequencyHeaderHost) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = OrtSpacing.sm)
            .testTag(LOG_FREQUENCY_EDITOR_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(OrtSpacing.xs),
    ) {
        TextField(
            value = host.draft,
            onValueChange = host.onDraftChange,
            label = "Frequency (MHz)",
            placeholder = "145.230",
            mono = true,
            contentDescriptionText = "Frequency in megahertz",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.testTag(LOG_FREQUENCY_EDITOR_FIELD_TEST_TAG),
        )
        // R-1170's rule, applied here too: `Save` is never disabled for validation state — it stays
        // lit and focusable, and the *tap* refuses with a specific message. Its own tagged node
        // (never the shared `TextField`'s untagged `errorText` slot) so a test can assert the refusal
        // happened without asserting its wording (constitution II). `Assertive`, for the reason
        // `SetupValidationNotice` documents: it is the direct answer to a deliberate tap that
        // otherwise appeared to do nothing.
        host.validationMessage?.let { message ->
            Text(
                text = message,
                style = OrtType.subLine,
                color = OrtColors.accentAmberText,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Assertive }
                    .testTag(LOG_FREQUENCY_EDITOR_VALIDATION_TEST_TAG),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(OrtSpacing.md)) {
            TextAction(
                text = "Save",
                onClick = host.onSave,
                modifier = Modifier.testTag(LOG_FREQUENCY_EDITOR_SAVE_TEST_TAG),
            )
            TextAction(
                text = "Cancel",
                onClick = host.onCancel,
                modifier = Modifier.testTag(LOG_FREQUENCY_EDITOR_CANCEL_TEST_TAG),
            )
        }
        // Constitution III ("nothing is deleted quietly"): unsetting the frequency is its own
        // deliberate, labelled action rather than the meaning of an empty field, which would
        // otherwise be indistinguishable from an entry the operator had not finished typing. Offered
        // only when there is actually something to clear.
        if (state.hasValue) {
            TextAction(
                text = "Clear the frequency",
                onClick = host.onClear,
                modifier = Modifier.testTag(LOG_FREQUENCY_EDITOR_CLEAR_TEST_TAG),
            )
        }
        // AC-131, stated at the moment of saving rather than discovered afterwards.
        Text(
            text = state.editorNote,
            style = OrtType.subLine,
            color = OrtColors.textDim,
            modifier = Modifier.fillMaxWidth().testTag(LOG_FREQUENCY_EDITOR_NOTE_TEST_TAG),
        )
    }
}
