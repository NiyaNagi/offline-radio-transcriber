package org.ort.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * P30 (FR-STO-6, FR-STO-9, AC-170). A new, dedicated screen — not a restore action folded into
 * `SettingsStorageScreen.kt` — because (1) that file is not in this unit's file-ownership map at
 * all, only "a restore action inside it" was offered as the alternative, and every edit to it
 * would be an unowned-file edit to an already-hot screen; (2) FR-STO-6/FR-STO-9 are a paired
 * export-and-restore capability with its own disclosure (FR-SPK-20's "device-to-device transfer,
 * and SHALL say so"), its own conflict-review surface (FR-STO-9), and its own "what this does not
 * yet carry" honesty list — more than a single row can carry without crowding Storage's own
 * retention controls; (3) the plan's own one-new-`SettingsScreenId`-case budget exists precisely
 * for a unit like this one.
 *
 * **Backup** always shows [SettingsBackupViewState.preview]'s real counts before `Save backup` is
 * ever tapped (constitution I — the same "preview before commit" idiom
 * [org.ort.app.ui.settings.SettingsExportScreen] and [org.ort.app.ui.settings
 * .SettingsDiagnosticsScreen] already use for their own bundles).
 *
 * **Restore** is two steps, never one tap-to-overwrite: `Choose a backup file` only ever produces
 * a [SettingsBackupViewState.restorePlan] (nothing is written yet); if the plan carries any
 * conflict, that count is shown plainly — session/transmission/correction/audio, each named —
 * before `Restore` can be tapped, and `Restore` never overwrites or merges a conflicting record
 * (constitution III, FR-STO-9; see [org.ort.app.backup.BackupRestorePlan]'s own kdoc for the exact
 * conflict rule this screen states in words). [SettingsBackupViewState.restoreSummary] renders
 * once a restore has actually been applied, replacing the plan with what genuinely happened.
 */
@Composable
public fun SettingsBackupScreen(
    state: SettingsBackupViewState,
    onBack: () -> Unit,
    actions: SettingsBackupActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag("backup-screen-scroll"),
    ) {
        DrillInHeader(parentLabel = "Settings", onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
            Text(text = "Backup and restore", style = OrtType.screenTitle, color = OrtColors.textHigh)
            Text(
                text = "A device-to-device transfer — a file you save yourself and restore onto " +
                    "another phone, or this one after a reinstall.",
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
            )

            BackupWhatsInSection()
            BackupSection(state.preview, onSaveBackup = actions.onSaveBackup)
            RestoreSection(
                plan = state.restorePlan,
                summary = state.restoreSummary,
                onPickRestoreFile = actions.onPickRestoreFile,
                onConfirmRestore = actions.onConfirmRestore,
                onCancelRestore = actions.onCancelRestore,
            )
        }
    }
}

/** Constitution I's honesty about scope, the same "Not in the bundle" idiom
 * `Settings-Diagnostics.dc.html`/[org.ort.app.diagnostics.DiagnosticsBundleBuilder] already use —
 * this bundle's own real, stated limitation (`BackupRecordCodecs.kt`'s own top-of-file kdoc): four
 * tables today, not the whole schema. */
@Composable
private fun BackupWhatsInSection() {
    SectionHeader(label = "In the backup", modifier = Modifier.padding(top = OrtSpacing.md))
    Text(
        text = "Sessions, overs, their current transcripts, and every correction you've made, " +
            "plus the retained audio for each over.",
        style = OrtType.cardBody,
        color = OrtColors.textBody,
        modifier = Modifier.padding(top = OrtSpacing.xs),
    )
    Text(
        text = "Not yet in the backup: the station catalog, voiceprints, threads, digests and " +
            "superseded transcript history — real, separate future work.",
        style = OrtType.subLine,
        color = OrtColors.textDim,
        modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
    )
}

@Composable
private fun BackupSection(preview: SettingsBackupPreviewViewState?, onSaveBackup: () -> Unit) {
    SectionHeader(label = "Save a backup", modifier = Modifier.padding(top = OrtSpacing.md))
    if (preview != null) {
        Text(
            text = "${preview.sessionCount} sessions · ${preview.transmissionCount} overs · " +
                "${preview.correctionCount} corrections · ${preview.audioFileCount} audio files · " +
                preview.sizeLabel,
            style = OrtType.subLine,
            color = OrtColors.textDim,
            modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm).testTag("backup-preview"),
        )
    }
    PrimaryActionButton(
        label = "Save backup",
        enabled = true,
        onClick = onSaveBackup,
        modifier = Modifier.fillMaxWidth().testTag("backup-save-button"),
    )
}

@Composable
private fun RestoreSection(
    plan: SettingsRestorePlanViewState?,
    summary: String?,
    onPickRestoreFile: () -> Unit,
    onConfirmRestore: () -> Unit,
    onCancelRestore: () -> Unit,
) {
    SectionHeader(label = "Restore from a backup", modifier = Modifier.padding(top = OrtSpacing.lg))
    when {
        summary != null -> {
            Text(
                text = summary,
                style = OrtType.cardBody,
                color = OrtColors.textBody,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.md).testTag("restore-summary"),
            )
        }
        plan != null -> {
            RestorePlanSummary(plan)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.md),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(OrtSpacing.sm),
            ) {
                SecondaryActionButton(
                    label = "Cancel",
                    onClick = onCancelRestore,
                    modifier = Modifier.testTag("restore-cancel-button"),
                )
                PrimaryActionButton(
                    label = "Restore",
                    enabled = true,
                    onClick = onConfirmRestore,
                    modifier = Modifier.testTag("restore-confirm-button"),
                )
            }
        }
        else -> {
            Text(
                text = "Nothing already on this device is ever deleted or overwritten by a " +
                    "restore — a record that already exists here is shown to you, not merged.",
                style = OrtType.subLine,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
            )
            SecondaryActionButton(
                label = "Choose a backup file",
                onClick = onPickRestoreFile,
                modifier = Modifier.fillMaxWidth().testTag("restore-pick-button"),
            )
        }
    }
}

@Composable
private fun RestorePlanSummary(plan: SettingsRestorePlanViewState) {
    Column(modifier = Modifier.testTag("restore-plan")) {
        Text(
            text = "${plan.sessionsToAddCount} sessions, ${plan.transmissionsToAddCount} overs, " +
                "${plan.correctionsToAddCount} corrections and ${plan.audioToAddCount} audio files " +
                "will be added.",
            style = OrtType.cardBody,
            color = OrtColors.textBody,
            modifier = Modifier.padding(top = OrtSpacing.xs),
        )
        if (plan.hasConflicts) {
            Text(
                text = "${plan.sessionConflictCount} sessions, ${plan.transmissionConflictCount} overs, " +
                    "${plan.correctionConflictCount} corrections and ${plan.audioConflictCount} audio " +
                    "files already exist on this device and will be left exactly as they are.",
                style = OrtType.subLine,
                color = OrtColors.accentAmberDim,
                modifier = Modifier.padding(top = OrtSpacing.xs).testTag("restore-conflict-summary"),
            )
        }
    }
}

/** The one, shared filled-button look every sibling `Save`/`Export` action in this package
 * already draws — kept local rather than reused from [org.ort.app.export.ExportCoordinator]'s own
 * screen since `SettingsExportScreen.kt`'s own button carries extra font-scale-specific layout
 * (`ExportSaveFileButton`) this screen's simpler, single-line label does not need. */
@Composable
private fun PrimaryActionButton(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg = if (enabled) OrtColors.accentGreen else OrtColors.bgChip
    val fg = if (enabled) OrtColors.accentOnGreen else OrtColors.textDisabled
    Box(
        modifier = modifier
            .padding(vertical = OrtSpacing.xs)
            .background(bg, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = OrtSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = OrtType.control, color = fg)
    }
}

@Composable
private fun SecondaryActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(vertical = OrtSpacing.xs)
            .background(OrtColors.bgCard, RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = OrtSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = OrtType.control, color = OrtColors.textBody)
    }
}
