package org.ort.app.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.ort.app.alerts.AlertsAppWiring
import org.ort.app.ui.components.Banner
import org.ort.app.ui.components.BannerTone
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.components.TextField
import org.ort.app.ui.components.ToggleRow
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * Build-plan P31 (FR-ALR-1, FR-ALR-2, FR-ALR-5, FR-ALR-6): every watched callsign, keyword and
 * frequency, one master on/off switch, and — honestly, per functional spec §7.19 — a banner when
 * the platform's own notification permission was denied, rather than a screen that looks fine
 * while nothing can actually fire (constitution I).
 *
 * No artboard existed for this screen before this unit (a genuinely new inventory row, see
 * `design/canvas/Settings-Alerts.dc.html` and `design/design-intent.md`'s own new `CF15` row) —
 * laid out to match its neighbours (`Settings-Analytics.dc.html`'s toggle-list shape,
 * `Settings-Backup.dc.html`'s section-label + inline-action shape) rather than inventing a new
 * pattern.
 */
@Composable
public fun SettingsAlertsScreen(
    state: SettingsAlertsViewState,
    onBack: () -> Unit,
    actions: SettingsAlertsActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        DrillInHeader(parentLabel = "Settings", onBack = onBack)
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = OrtSpacing.lg),
        ) {
            Text(
                text = "Live alerts",
                style = OrtType.screenTitle,
                color = OrtColors.textHigh,
                modifier = Modifier.padding(top = OrtSpacing.xs),
            )
            Text(
                text = "Local notifications only — no watch, match or firing event ever leaves this device.",
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
            )

            ToggleRow(
                label = "Alerts",
                checked = state.alertsEnabled,
                onCheckedChange = actions.onToggleAlertsEnabled,
                subLine = "Turn off to stop every watch below from firing, without deleting any of them.",
                modifier = Modifier.testTag("alerts-master-toggle"),
            )

            if (!state.notificationsPermissionGranted) {
                Banner(
                    title = "Alerts cannot fire",
                    body = "Notifications are turned off for this app in system settings. Turn them on " +
                        "there to receive a live alert.",
                    tone = BannerTone.DEGRADED,
                    modifier = Modifier.padding(top = OrtSpacing.sm),
                )
            }

            AlertWatchSection(
                title = "Callsigns",
                kind = SettingsAlertWatchKind.CALLSIGN,
                state = state,
                actions = actions,
                addLabel = "Add a callsign",
                placeholder = "e.g. K7ABC",
                mono = true,
                onAdd = actions.onAddCallsignWatch,
            )
            AlertWatchSection(
                title = "Keywords",
                kind = SettingsAlertWatchKind.KEYWORD,
                state = state,
                actions = actions,
                addLabel = "Add a keyword",
                placeholder = "e.g. skywarn",
                mono = false,
                onAdd = actions.onAddKeywordWatch,
            )
            AlertWatchSection(
                title = "Frequencies",
                kind = SettingsAlertWatchKind.FREQUENCY,
                state = state,
                actions = actions,
                addLabel = "Add a frequency, MHz",
                placeholder = "e.g. 146.520",
                mono = true,
                onAdd = actions.onAddFrequencyWatch,
            )
        }
    }
}

/** One watch-kind section — split out purely to keep [SettingsAlertsScreen] under detekt's length
 * limit, the same reason every other multi-section Settings screen in this package already does. */
@Composable
private fun AlertWatchSection(
    title: String,
    kind: SettingsAlertWatchKind,
    state: SettingsAlertsViewState,
    actions: SettingsAlertsActions,
    addLabel: String,
    placeholder: String,
    mono: Boolean,
    onAdd: (String) -> Unit,
) {
    SectionHeader(label = title, modifier = Modifier.padding(top = OrtSpacing.md))
    state.watches.filter { it.kind == kind }.forEach { row ->
        AlertWatchRow(row = row, actions = actions, mono = mono)
    }
    AddAlertWatchRow(addLabel = addLabel, placeholder = placeholder, mono = mono, onAdd = onAdd)
}

/** One existing watch: a [ToggleRow] for FR-ALR-6's enable/disable, plus an `Edit`/`Remove` action
 * line — `Edit` swaps the row for an inline [TextField] (the same tap-to-edit shape
 * `SettingsCaptureScreen.kt`'s own `ManualFrequencyRow` already established for the manual
 * frequency field), `Save` commits through [SettingsAlertsActions.onEditWatch] with the *same*
 * [SettingsAlertWatchRowViewState.id] — editing a watch's value never re-creates it as a new one
 * (FR-ALR-1's own "add, edit and delete", not "delete and re-add"). */
@Composable
private fun AlertWatchRow(row: SettingsAlertWatchRowViewState, actions: SettingsAlertsActions, mono: Boolean) {
    var editing by remember(row.id) { mutableStateOf(false) }
    var draft by remember(row.id, editing) { mutableStateOf(row.label) }
    if (editing) {
        TextField(
            value = draft,
            onValueChange = { draft = it },
            mono = mono,
            contentDescriptionText = "Edit watch value",
            modifier = Modifier.fillMaxWidth().padding(vertical = OrtSpacing.xs),
            trailingAction = {
                TextAction(
                    text = "Save",
                    onClick = {
                        if (draft.isNotBlank()) {
                            actions.onEditWatch(row.id, row.kind, draft.trim())
                            editing = false
                        }
                    },
                )
            },
        )
    } else {
        Column {
            ToggleRow(
                label = row.label,
                checked = row.enabled,
                onCheckedChange = { actions.onToggleWatch(row.id, it) },
                modifier = Modifier.testTag("alert-watch-toggle-${row.id}"),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(OrtSpacing.md),
                modifier = Modifier.padding(start = OrtSpacing.xs, bottom = OrtSpacing.xs),
            ) {
                TextAction(text = "Edit", onClick = { editing = true })
                TextAction(text = "Remove", onClick = { actions.onRemoveWatch(row.id) })
            }
        }
    }
}

/** `Add a callsign` / `Add a keyword` / `Add a frequency, MHz` — collapsed to one [TextAction]
 * until tapped, then the same tap-to-edit [TextField] shape [AlertWatchRow] uses for editing. A
 * blank draft is never added (AC-192 does not ask for empty watches); the field clears itself and
 * collapses again after a successful add, ready for the next one. */
@Composable
private fun AddAlertWatchRow(addLabel: String, placeholder: String, mono: Boolean, onAdd: (String) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    if (adding) {
        TextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = placeholder,
            mono = mono,
            contentDescriptionText = "New watch value",
            modifier = Modifier.fillMaxWidth().padding(vertical = OrtSpacing.xs),
            trailingAction = {
                TextAction(
                    text = "Add",
                    onClick = {
                        if (draft.isNotBlank()) {
                            onAdd(draft.trim())
                            draft = ""
                            adding = false
                        }
                    },
                )
            },
        )
    } else {
        TextAction(text = addLabel, onClick = { adding = true }, modifier = Modifier.padding(vertical = OrtSpacing.xs))
    }
}

/** [SettingsAlertsScreen]'s own state-builder — deliberately not added to `SettingsPolling.kt`
 * (outside this unit's narrow, named integration point; see `SettingsRootScreen.kt`'s own doc
 * comment on the identical choice P27/P28/P30 each made for their own new screen). */
public object SettingsAlertsPolling {
    public fun current(context: Context): SettingsAlertsViewState {
        AlertsAppWiring.configureOnce(context)
        val store = AlertsAppWiring.watchStore
        return SettingsAlertsViewState(
            alertsEnabled = store.alertsEnabled,
            notificationsPermissionGranted = AlertsAppWiring.notificationsPermissionGranted(context),
            watches = store.list().map { it.toRowViewState() },
        )
    }
}

private fun org.ort.pipeline.alerts.AlertWatch.toRowViewState(): SettingsAlertWatchRowViewState = when (this) {
    is org.ort.pipeline.alerts.AlertWatch.Callsign ->
        SettingsAlertWatchRowViewState(id, SettingsAlertWatchKind.CALLSIGN, displayValue(), enabled)
    is org.ort.pipeline.alerts.AlertWatch.Keyword ->
        SettingsAlertWatchRowViewState(id, SettingsAlertWatchKind.KEYWORD, displayValue(), enabled)
    is org.ort.pipeline.alerts.AlertWatch.Frequency ->
        SettingsAlertWatchRowViewState(id, SettingsAlertWatchKind.FREQUENCY, displayValue(), enabled)
}
