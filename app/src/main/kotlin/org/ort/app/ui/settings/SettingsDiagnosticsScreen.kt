package org.ort.app.ui.settings

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.ort.app.fieldreport.bundle.FieldReportGatedCategory
import org.ort.app.fieldreport.consent.FieldReportGuard
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.FailedState
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.SecondaryButton
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.Tile
import org.ort.app.ui.components.ToggleRow
import org.ort.app.ui.improve.Plurals
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * `Settings-Diagnostics.dc.html` (FR-OBS-1/3/5): what a bundle contains, real and computed before
 * it exists on disk — WP11e's `DiagnosticsBundleBuilder` (round 9, register R-137) is the real
 * producer behind every file's size and the header total; `Preview`/`Save bundle` are both real
 * actions now, wired by `SettingsContent` (this screen itself takes no `Context`, per this
 * package's own polling-stays-out-of-screens rule).
 *
 * [onPreview] opens [previewOpen] — an in-app, full-screen listing of the exact same real entries
 * (name, clause, size) and total `Save bundle` would write, before committing to it. The board's
 * own prose ("Preview opens every file in a reader before you save") reads as launching a
 * per-file *external* viewer — genuinely a different, larger feature (a `FileProvider`, a manifest
 * change, one `ACTION_VIEW` intent per file) than a single Compose screen can add on its own; this
 * is the honest, real, in-scope substitute reported to the coordinator for a decision on whether
 * the external-reader shape is still wanted as a follow-up.
 */
@Composable
public fun SettingsDiagnosticsScreen(
    state: SettingsDiagnosticsViewState,
    onBack: () -> Unit,
    bundleActions: SettingsDiagnosticsBundleActions,
    modifier: Modifier = Modifier,
    previewOpen: Boolean = false,
    onDismissPreview: () -> Unit = {},
    saveConfirmationLabel: String? = null,
    // WPR2 (FR-OBS-6..12, D37/D38): a no-op default so every existing caller of this function
    // keeps compiling unchanged. `SettingsContent.kt` wires both actions for real.
    fieldReportActions: FieldReportSectionActions = FieldReportSectionActions(),
) {
    if (previewOpen) {
        DiagnosticsPreviewScreen(state = state, onDone = onDismissPreview, modifier = modifier)
        return
    }
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DrillInHeader(parentLabel = "Settings", onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
            Text(text = "Diagnostics", style = OrtType.screenTitle, color = OrtColors.textHigh)
            Text(
                text = "What a bundle contains, shown before it exists",
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
            )

            // R-137 (round 4, System validator): each `Tile` asked for `fillMaxWidth()` instead of
            // `weight(1f)` — inside a `Row`, that makes every tile claim the *whole* row's width
            // (not a fair share of it), so the second and third tiles were laid out entirely off
            // the visible screen to the right rather than side by side. Only the first ("capture
            // state") ever showed.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
            ) {
                Tile(figure = state.aliveLabel, caption = "capture state", modifier = Modifier.weight(1f))
                Tile(figure = state.realTimeFactorLabel, caption = "RTF, small model", modifier = Modifier.weight(1f))
                Tile(
                    figure = state.failedPassCount?.toString() ?: "not tracked",
                    caption = "failed passes",
                    modifier = Modifier.weight(1f),
                )
            }

            // R-137 (round 9): "In the bundle · N files · X.X MB" is now the board's own shape,
            // header total included — real, WP11e's `DiagnosticsBundleBuilder.preview`.
            SectionHeader(
                label = "In the bundle · ${Plurals.count(state.files.size, "file")} · ${state.totalSizeLabel}",
                modifier = Modifier.padding(top = OrtSpacing.lg),
            )
            state.files.forEach { file -> DiagnosticsFileRow(file = file) }

            SectionHeader(label = "Never included", modifier = Modifier.padding(top = OrtSpacing.lg))
            // R-137 (round 7, then round 9): `Settings-Diagnostics.dc.html`'s own scrubbing example
            // — "resolved [callsign] at 0.94" — is real now, not hypothetical: WP11e's
            // `CallsignScrubber` actually runs on every log entry `DiagnosticsBundleBuilder`
            // renders, so this reads "are scrubbed", not "would be".
            Text(
                text = "Audio. Transcripts. Callsigns. Voiceprints. Names. Location. The logs above are " +
                    "scrubbed of callsigns before they are written — a line reads " +
                    "\"resolved [callsign] at 0.94\", never the callsign itself.",
                style = OrtType.cardBody,
                color = OrtColors.textBody,
                modifier = Modifier.padding(top = OrtSpacing.xs),
            )

            saveConfirmationLabel?.let { label ->
                Text(
                    text = label,
                    style = OrtType.cardBody,
                    color = OrtColors.accentGreen,
                    modifier = Modifier.padding(top = OrtSpacing.lg),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.lg),
                horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
            ) {
                SecondaryButton(text = "Preview", onClick = bundleActions.onPreview, modifier = Modifier.weight(1f))
                PrimaryButton(
                    text = "Save bundle",
                    onClick = bundleActions.onSaveBundle,
                    modifier = Modifier.weight(1f),
                )
            }

            // WPW (register R-1009 follow-up): `DebugDumpBuilder`'s own trigger — beside `Save
            // bundle`, through the identical SAF path (`SettingsContent.kt`'s own wiring).
            SecondaryButton(
                text = "Save debug dump",
                onClick = bundleActions.onSaveDebugDump,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = OrtSpacing.sm, bottom = OrtSpacing.lg)
                    .testTag(DEBUG_DUMP_SAVE_TEST_TAG),
            )

            // WPR2 (FR-OBS-6..12, D37/D38): `null` — a release build, where the debug-only
            // recorder never runs at all (FR-OBS-6) — renders nothing further here.
            state.fieldReport?.let { fieldReport ->
                FieldReportSection(
                    state = fieldReport,
                    onOpenFieldReport = fieldReportActions.onOpenFieldReport,
                    onSetPublicDestinationGuardEnabled = fieldReportActions.onSetPublicDestinationGuardEnabled,
                )
            }
        }
    }
}

/**
 * WPR2 (FR-OBS-6..12, D37/D38): the debug-only field report section — a `Send field report…`
 * entry into [FieldReportConsentScreen] (FR-OBS-9), and the FR-OBS-10 Settings switch itself.
 * [state.publicGuardEnabled] `true` is the safe position: the switch reads *checked* when the
 * guard is explicitly turned **off** (`!publicGuardEnabled`), matching this screen's own label
 * ("Allow …") — [onSetPublicDestinationGuardEnabled] receives the resulting guard-enabled value
 * directly, never the raw switch position, so every caller of that callback reasons in the same
 * "guard enabled = safe" terms [org.ort.app.fieldreport.consent.FieldReportGuard] does.
 */
@Composable
private fun FieldReportSection(
    state: FieldReportSectionViewState,
    onOpenFieldReport: () -> Unit,
    onSetPublicDestinationGuardEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(top = OrtSpacing.lg, bottom = OrtSpacing.lg)) {
        SectionHeader(label = "Field report (debug builds only)")
        Text(
            text = "A closed-vocabulary session log plus, on request, retained audio, voiceprint " +
                "embeddings or screen frames — shown in full before every send (FR-OBS-6..12).",
            style = OrtType.cardBody,
            color = OrtColors.textDim,
            modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
        )
        ToggleRow(
            label = "Allow retained audio, voiceprints or frames to a public destination",
            checked = !state.publicGuardEnabled,
            onCheckedChange = { allow -> onSetPublicDestinationGuardEnabled(!allow) },
            subLine = "Off by default — the safe position (FR-OBS-10). A public destination refuses " +
                "those three categories until this is turned on.",
            modifier = Modifier.testTag(FIELD_REPORT_GUARD_TOGGLE_TEST_TAG),
        )
        SecondaryButton(
            text = "Send field report…",
            onClick = onOpenFieldReport,
            modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.sm),
        )
    }
}

/** [FieldReportGatedCategory]'s consent-screen label — plain operator language, never the enum's
 * own `.name`. */
private fun FieldReportGatedCategory.consentLabel(): String = when (this) {
    FieldReportGatedCategory.RETAINED_AUDIO -> "Retained over audio"
    FieldReportGatedCategory.VOICEPRINT_EMBEDDINGS -> "Voiceprint embeddings"
    FieldReportGatedCategory.SCREEN_FRAMES -> "Screen frames"
}

/**
 * FR-OBS-9: shown before **every** field-report upload — never remembered, never inferred from a
 * previous upload (AC-144). [state.toggles] is always [FieldReportToggleState]'s all-`false`
 * default the first time this screen is composed for a given open; `SettingsContent.kt`'s own
 * wiring is what makes a second open start from the same default again rather than carrying
 * forward whatever the operator picked last time — this composable itself holds no memory of its
 * own between opens, by construction (it takes every toggle value as a parameter, never as
 * internal `remember`ed state).
 *
 * FR-OBS-10: when [state.destinationKnown] and [state.destinationPublic] are both true and
 * [state.publicGuardEnabled] is true, [FieldReportGuard.gatedCategoriesAllowed] is `false` — the
 * three toggles below render forced off and inert (`onCheckedChange` does nothing) rather than
 * merely disabled-looking, so a tap cannot silently arm a category the guard is refusing. When the
 * guard has been turned off against a known public destination, a prominent banner names exactly
 * the categories currently toggled on — computed fresh from [state] on every recomposition, so it
 * never has a "seen once" memory either (AC-149).
 */
@Composable
public fun FieldReportConsentScreen(
    state: FieldReportConsentViewState,
    onToggleRetainedAudio: (Boolean) -> Unit,
    onToggleVoiceprintEmbeddings: (Boolean) -> Unit,
    onToggleScreenFrames: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    canSend: Boolean = false,
) {
    val gatedCategoriesAllowed = !state.destinationKnown ||
        FieldReportGuard.gatedCategoriesAllowed(state.destinationPublic, state.publicGuardEnabled)
    val aboutToPublish = state.destinationKnown && state.destinationPublic && !state.publicGuardEnabled
    val publishingCategories = if (aboutToPublish) {
        listOfNotNull(
            FieldReportGatedCategory.RETAINED_AUDIO.takeIf { state.toggles.retainedAudio },
            FieldReportGatedCategory.VOICEPRINT_EMBEDDINGS.takeIf { state.toggles.voiceprintEmbeddings },
            FieldReportGatedCategory.SCREEN_FRAMES.takeIf { state.toggles.screenFrames },
        )
    } else {
        emptyList()
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DrillInHeader(parentLabel = "Diagnostics", onBack = onCancel)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
            Text(text = "Send field report", style = OrtType.screenTitle, color = OrtColors.textHigh)
            Text(
                text = if (state.destinationKnown) {
                    "${state.destinationLabel} · ${if (state.destinationPublic) "public" else "private"}"
                } else {
                    "No upload destination is configured in this build"
                },
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
            )

            if (aboutToPublish && publishingCategories.isNotEmpty()) {
                PublicDestinationWarning(categories = publishingCategories)
            }

            SectionHeader(
                label = "In this upload · ${Plurals.count(state.files.size, "file")} · ${state.totalSizeLabel}",
                modifier = Modifier.padding(top = OrtSpacing.md),
            )
            state.files.forEach { file -> FieldReportFileRow(file = file) }

            FieldReportGatedTogglesSection(
                state = state,
                gatedCategoriesAllowed = gatedCategoriesAllowed,
                onToggleRetainedAudio = onToggleRetainedAudio,
                onToggleVoiceprintEmbeddings = onToggleVoiceprintEmbeddings,
                onToggleScreenFrames = onToggleScreenFrames,
            )

            if (!canSend) {
                FailedState(
                    title = "Send is not available in this build",
                    body = "No field-report upload client exists in :net yet — nothing here can be " +
                        "sent regardless of which categories are on.",
                    modifier = Modifier.padding(top = OrtSpacing.lg),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.lg, bottom = OrtSpacing.lg),
                horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
            ) {
                SecondaryButton(text = "Cancel", onClick = onCancel, modifier = Modifier.weight(1f))
                PrimaryButton(text = "Send", onClick = onSend, modifier = Modifier.weight(1f), enabled = canSend)
            }
        }
    }
}

/** [FieldReportConsentScreen]'s public-destination warning banner (FR-OBS-10, AC-149) — split out
 * purely to keep that composable under detekt's length limit. */
@Composable
private fun PublicDestinationWarning(categories: List<FieldReportGatedCategory>, modifier: Modifier = Modifier) {
    Text(
        text = "This destination is public and the guard is off — " +
            categories.joinToString(", ") { it.consentLabel() } +
            " will be published with this upload.",
        style = OrtType.cardBody,
        color = OrtColors.accentAmberText,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm)
            .semantics { contentDescription = "public destination warning" },
    )
}

/** [FieldReportConsentScreen]'s three FR-OBS-9 gated-category toggles, forced off and inert
 * (never calling back) while [gatedCategoriesAllowed] is `false` (FR-OBS-10, AC-145) — split out
 * purely to keep that composable under detekt's length limit. */
@Composable
private fun FieldReportGatedTogglesSection(
    state: FieldReportConsentViewState,
    gatedCategoriesAllowed: Boolean,
    onToggleRetainedAudio: (Boolean) -> Unit,
    onToggleVoiceprintEmbeddings: (Boolean) -> Unit,
    onToggleScreenFrames: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionHeader(
            label = "Can be included, each with its own switch",
            modifier = Modifier.padding(top = OrtSpacing.md),
        )
        ToggleRow(
            label = FieldReportGatedCategory.RETAINED_AUDIO.consentLabel(),
            checked = gatedCategoriesAllowed && state.toggles.retainedAudio,
            onCheckedChange = { if (gatedCategoriesAllowed) onToggleRetainedAudio(it) },
            modifier = Modifier.testTag(FIELD_REPORT_TOGGLE_RETAINED_AUDIO_TEST_TAG),
        )
        ToggleRow(
            label = FieldReportGatedCategory.VOICEPRINT_EMBEDDINGS.consentLabel(),
            checked = gatedCategoriesAllowed && state.toggles.voiceprintEmbeddings,
            onCheckedChange = { if (gatedCategoriesAllowed) onToggleVoiceprintEmbeddings(it) },
            modifier = Modifier.testTag(FIELD_REPORT_TOGGLE_VOICEPRINTS_TEST_TAG),
        )
        ToggleRow(
            label = FieldReportGatedCategory.SCREEN_FRAMES.consentLabel(),
            checked = gatedCategoriesAllowed && state.toggles.screenFrames,
            onCheckedChange = { if (gatedCategoriesAllowed) onToggleScreenFrames(it) },
            modifier = Modifier.testTag(FIELD_REPORT_TOGGLE_SCREEN_FRAMES_TEST_TAG),
        )
        if (!gatedCategoriesAllowed) {
            Text(
                text = "This destination is public — these three are refused until the Settings " +
                    "switch above is turned on.",
                style = OrtType.subLine,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs),
            )
        }
    }
}

/** One `IN THIS UPLOAD` row — real name and real size, [file.category] named plainly when it is
 * one of the three gated categories rather than part of FR-OBS-8's ungated set. */
@Composable
private fun FieldReportFileRow(file: FieldReportConsentFileViewState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = OrtSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = file.name, style = OrtType.callsignRow, color = OrtColors.textHigh)
            file.category?.let {
                Text(text = it.consentLabel(), style = OrtType.subLine, color = OrtColors.textDim)
            }
        }
        Text(text = file.sizeLabel, style = OrtType.subLine, color = OrtColors.textFaint)
    }
}

/** One `IN THE BUNDLE` row — name, real clause, real trailing size (the board's own `.f`/`.sz`
 * shape). Split out of [SettingsDiagnosticsScreen] purely to keep that function under detekt's
 * length limit. */
@Composable
private fun DiagnosticsFileRow(file: SettingsDiagnosticsFileViewState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = OrtSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = file.name, style = OrtType.callsignRow, color = OrtColors.textHigh)
            Text(text = file.description, style = OrtType.subLine, color = OrtColors.textDim)
        }
        Text(text = file.sizeLabel, style = OrtType.subLine, color = OrtColors.textFaint)
    }
}

/** `Preview` (R-137, round 9): the exact real entries/total `Save bundle` would write, shown
 * before committing to it — see [SettingsDiagnosticsScreen]'s own doc comment for why this is an
 * in-app listing rather than the board's own per-file external-reader wording. */
@Composable
private fun DiagnosticsPreviewScreen(
    state: SettingsDiagnosticsViewState,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DrillInHeader(parentLabel = "Diagnostics", onBack = onDone)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
            Text(text = "Preview", style = OrtType.screenTitle, color = OrtColors.textHigh)
            Text(
                text = "Exactly what Save bundle will write · ${Plurals.count(state.files.size, "file")} · " +
                    state.totalSizeLabel,
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
            )
            state.files.forEach { file -> DiagnosticsFileRow(file = file) }
            PrimaryButton(
                text = "Done",
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.lg, bottom = OrtSpacing.lg),
            )
        }
    }
}

/** Test tags for the field-report section/consent screen's toggles — unambiguous targets for a
 * `performClick()`/`assertIsOn()`/`assertIsOff()` test, the same reason `ControlsTest.kt` already
 * tags a plain `ToggleRow` this way. */
public const val FIELD_REPORT_GUARD_TOGGLE_TEST_TAG: String = "field-report-guard-toggle"
public const val FIELD_REPORT_TOGGLE_RETAINED_AUDIO_TEST_TAG: String = "field-report-toggle-retained-audio"
public const val FIELD_REPORT_TOGGLE_VOICEPRINTS_TEST_TAG: String = "field-report-toggle-voiceprints"
public const val FIELD_REPORT_TOGGLE_SCREEN_FRAMES_TEST_TAG: String = "field-report-toggle-screen-frames"

/** WPW: the debug-dump `Save debug dump` button — a stable, unambiguous target for a
 * `performClick()` test, the same reason every field-report toggle above is tagged. */
public const val DEBUG_DUMP_SAVE_TEST_TAG: String = "diagnostics-save-debug-dump"
