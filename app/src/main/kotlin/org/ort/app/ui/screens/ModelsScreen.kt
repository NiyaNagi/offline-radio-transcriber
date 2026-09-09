package org.ort.app.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.Badge
import org.ort.app.ui.components.BadgeKind
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.FailedState
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.SecondaryButton
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.data.LexiconAssetActions
import org.ort.app.ui.data.LexiconAssetRowViewState
import org.ort.app.ui.data.LexiconCheckViewRow
import org.ort.app.ui.data.LexiconImportViewState
import org.ort.app.ui.data.ModelDownloadFailureViewState
import org.ort.app.ui.data.ModelId
import org.ort.app.ui.data.ModelRowStatus
import org.ort.app.ui.data.ModelRowViewState
import org.ort.app.ui.data.ModelsController
import org.ort.app.ui.data.ModelsScreenStatus
import org.ort.app.ui.data.ModelsViewState
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType
import org.ort.lexicon.import.CheckStatus

/**
 * R-093 (`Settings-Assets.dc.html`, guide §6.7): one row per asset — a verified/not-installed
 * marker, the name with an `active` tag once installed, a size · checksum-prefix sub-line — plus
 * the checksum/record-count promise underneath. Replaces the pre-R-093 presentation (M3 `Button`
 * pairs, a free-text `lastMessage` paragraph, the unknown-checksum reason printed as a full
 * sentence in the row label) audit F-008 shipped first and register row R-093 named as `design`.
 *
 * [ModelsController]'s logic is unchanged by this file (R-093's brief: "keep it, it is correct") —
 * this only re-skins what [ModelsViewState] already carries, plus the two fields
 * [ModelRowViewState.sizeBytes]/[ModelRowViewState.checksumPrefix] this package's `ModelsViewData.kt`
 * change added so the sub-line has real numbers to show rather than inventing them here.
 *
 * F13 (`Fail-Model.dc.html`, this screen's honest reading of it): when neither of the two files
 * Pass B's encoder needs is installed, a [FailedState] states the fallback tier plainly (capture
 * still runs; Pass B/C stay off) with an `Install` action — never a bare missing row with no
 * explanation, and never a silent full-capability claim while the model that provides it is
 * absent.
 */
@Composable
public fun ModelsScreen(
    state: ModelsViewState,
    busy: Set<ModelId> = emptySet(),
    onDownload: (ModelId) -> Unit,
    onSideload: (ModelId) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    // R-154 (round 5): bundled with `downloadFailure` (mutually exclusive in practice —
    // `ModelsContent` clears one when it sets the other) into [ModelsScreenStatus] to keep this
    // function's own parameter list under detekt's threshold once the lexicon row added a ninth.
    // `Retry` reuses [onDownload] itself (with the failed asset's own id) rather than a separate
    // callback for what is, structurally, the same action.
    status: ModelsScreenStatus = ModelsScreenStatus(),
    // R-154 (round 5): `null` (the default) keeps every existing caller compiling unchanged and
    // draws no lexicon row at all — see [LexiconAssetActions]'s own doc comment.
    lexicon: LexiconAssetActions? = null,
) {
    // FR-AST-4 (WP11b's F21 audit finding): folded into status (ModelsScreenStatus's own
    // stagedActivation field), the same detekt-threshold reason status itself already carries
    // lastMessage/downloadFailure instead of two more bare parameters — see StagedActivation's own
    // doc comment for what this real, trimmed fact carries and where it comes from.
    val stagedActivation = status.stagedActivation
    val rejectedImport = lexicon?.importResult as? LexiconImportViewState.Rejected
    if (rejectedImport != null) {
        FailLexiconScreen(
            result = rejectedImport,
            onChooseAnotherFile = lexicon.onInstall,
            onDone = lexicon.onDismissResult,
            modifier = modifier,
        )
        return
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        onBack?.let { back -> DrillInHeader(parentLabel = "Settings", onBack = back) }
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)) {
            Text(text = "Models and lexicon", style = OrtType.screenTitle, color = OrtColors.textHigh)
            Text(
                text = "Installed by you, from a file. This app never downloads anything without a tap.",
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs),
            )

            val encoderOrDecoderMissing = state.rows
                .filter { it.id == ModelId.ASR_ENCODER || it.id == ModelId.ASR_DECODER }
                .any { it.status == ModelRowStatus.NOT_INSTALLED }
            if (encoderOrDecoderMissing) {
                FailedState(
                    title = "No transcription model installed",
                    body = "Pass B and Pass C stay off until the encoder and decoder are both installed. " +
                        "Capture still runs — this session falls back to tier 1: live partials only, " +
                        "plainly spelled callsigns, no voice match.",
                    actionLabel = "Install",
                    onAction = { onSideload(ModelId.ASR_ENCODER) },
                    modifier = Modifier.padding(top = OrtSpacing.md),
                )
            }

            ModelsNotices(requeuedMessage = state.requeuedMessage, status = status, onRetryDownload = onDownload)

            SectionHeader(label = "Assets", modifier = Modifier.padding(top = OrtSpacing.lg))
            AssetGroupsList(
                rows = state.rows,
                busy = busy,
                onDownload = onDownload,
                onSideload = onSideload,
                stagedAssetId = stagedActivation?.assetId,
            )
            // R-154 (round 5): the callsign lexicon is a real asset ([org.ort.data.entity.LexiconVersionEntity],
            // via [org.ort.app.ui.data.ModelsController.lexiconRow]) with no [ModelId] of its own —
            // it gets its own family caption and row rather than joining `groupAssetRows` above,
            // since its install path (`installLexicon`, real validation + activation) is genuinely
            // different from a plain checksum-and-copy model download.
            lexicon?.row?.let { row ->
                LexiconAssetRow(
                    row = row,
                    onInstall = lexicon.onInstall,
                    staged = stagedActivation?.assetId == ModelsController.CALLSIGN_LEXICON_ASSET_ID,
                )
            }

            Text(
                text = "Checksum and record count are verified before anything replaces the current " +
                    "version. A corrupt file is refused and the old one stays.",
                style = OrtType.cardBody,
                color = OrtColors.textFaint,
                modifier = Modifier.padding(top = OrtSpacing.lg, bottom = OrtSpacing.lg),
            )
        }
    }
}

/** R-140 (round 4, System validator): grouped by real model family — was one flat row per
 * [ModelId], so the Whisper encoder/decoder/tokens files (three real, independently downloadable/
 * sideloadable parts of *one* model — `ModelsController`'s own unit of action) read as three
 * unrelated assets. Grouping states the split honestly (a family caption, each part still its own
 * row with its own real actions) rather than either hiding the split or leaving it unexplained.
 * Split out of [ModelsScreen] itself purely to keep that function under detekt's length limit. */
@Composable
private fun AssetGroupsList(
    rows: List<ModelRowViewState>,
    busy: Set<ModelId>,
    onDownload: (ModelId) -> Unit,
    onSideload: (ModelId) -> Unit,
    stagedAssetId: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        groupAssetRows(rows).forEach { group ->
            // R-140 (register, round 7 System validator pass 3): a multi-file family (today, only
            // the Whisper tiny.en encoder/decoder/tokens split) is one row — the board's own
            // `whisper-small-int8`/`whisper-tiny-en-int8` shape — not one full `AssetRow` per file.
            // A single-file family (VAD) keeps the pre-existing caption + row.
            if (group.parts.size > 1) {
                GroupedAssetRow(
                    familyLabel = group.familyLabel,
                    parts = group.parts,
                    busy = busy,
                    onDownload = onDownload,
                    onSideload = onSideload,
                    stagedAssetId = stagedAssetId,
                )
            } else {
                Text(
                    text = group.familyLabel,
                    style = OrtType.subLine,
                    color = OrtColors.textFaint,
                    modifier = Modifier.padding(top = OrtSpacing.sm),
                )
                group.parts.forEach { row ->
                    AssetRow(
                        row = row,
                        isBusy = row.id in busy,
                        onDownload = onDownload,
                        onSideload = onSideload,
                        staged = stagedAssetId == row.id.name,
                    )
                }
            }
        }
    }
}

/** The requeued/last-action/download-failure notices, split out of [ModelsScreen] purely to keep
 * that function under detekt's length limit. */
@Composable
private fun ModelsNotices(
    requeuedMessage: String?,
    status: ModelsScreenStatus,
    onRetryDownload: (ModelId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        requeuedMessage?.let { message ->
            Text(
                text = message,
                style = OrtType.cardBody,
                color = OrtColors.textMuted,
                modifier = Modifier.padding(top = OrtSpacing.sm).semantics { contentDescription = message },
            )
        }
        status.lastMessage?.let { message ->
            Text(
                text = message,
                style = OrtType.cardBody,
                color = OrtColors.textMuted,
                modifier = Modifier
                    .padding(top = OrtSpacing.xs)
                    .semantics { contentDescription = "Last action: $message" },
            )
        }
        // R-140 (round 4, then round 7 System validator pass 3): a failed download used to leave
        // `:net`'s raw exception text sitting in plain body copy — round 4 stopped it being the
        // *only* thing shown but still quoted it inline; this is the amber `FailedState` every
        // other operator-facing failure in this build gets, real `Retry`, operator-language body
        // (guide §9) with the unedited raw cause now behind its own expandable "Details" instead.
        status.downloadFailure?.let { failure ->
            DownloadFailureNotice(
                failure = failure,
                onRetryDownload = onRetryDownload,
                modifier = Modifier.padding(top = OrtSpacing.sm),
            )
        }
    }
}

/** R-140 (round 7): split out of [ModelsNotices] so a failed download's raw cause
 * ([ModelDownloadFailureViewState.reason] — real, `:net`'s own, never edited) can carry its own
 * local `expanded` state without `ModelsNotices` itself needing one. The visible body never
 * guesses *why* the download failed (a checksum mismatch reads nothing like a host that could not
 * be reached) — "did not complete" is true of every real cause this state can carry; the specific
 * one is one tap away, not hidden, not deleted. */
@Composable
private fun DownloadFailureNotice(
    failure: ModelDownloadFailureViewState,
    onRetryDownload: (ModelId) -> Unit,
    modifier: Modifier = Modifier,
) {
    var detailsExpanded by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        FailedState(
            title = "${failure.id.label} could not be downloaded",
            body = "The download did not complete. Check the connection and retry. The version " +
                "already installed, if any, is unchanged.",
            actionLabel = "Retry",
            onAction = { onRetryDownload(failure.id) },
        )
        TextAction(
            text = if (detailsExpanded) "Hide details" else "Details",
            onClick = { detailsExpanded = !detailsExpanded },
            modifier = Modifier.padding(start = OrtSpacing.lg),
        )
        if (detailsExpanded) {
            Text(
                text = failure.reason,
                style = OrtType.subLine,
                color = OrtColors.textFaint,
                modifier = Modifier
                    .padding(start = OrtSpacing.lg, top = OrtSpacing.xs)
                    .semantics { contentDescription = "Details: ${failure.reason}" },
            )
        }
    }
}

@Composable
private fun AssetRow(
    row: ModelRowViewState,
    isBusy: Boolean,
    onDownload: (ModelId) -> Unit,
    onSideload: (ModelId) -> Unit,
    modifier: Modifier = Modifier,
    // FR-AST-4: real only when `stagedActivation.assetId == row.id.name` (this screen's own call
    // site) — never a guess, and never true while [row]'s own real `status` hasn't changed at all.
    staged: Boolean = false,
) {
    val statusWord = when {
        isBusy -> "downloading"
        row.status == ModelRowStatus.INSTALLED -> "verified"
        row.status == ModelRowStatus.INSTALLED_UNVERIFIED -> "installed, unverified"
        else -> "not installed"
    }
    val subLine = when {
        isBusy -> "downloading…"
        row.status == ModelRowStatus.NOT_INSTALLED && row.checksumKnown -> "not installed"
        row.status == ModelRowStatus.NOT_INSTALLED && !row.checksumKnown ->
            "not installed · sideload only, " + row.detail.orEmpty()
        else -> {
            val size = row.sizeBytes?.let { formatAssetSize(it) } ?: "size unknown"
            val checksum = row.checksumPrefix?.let { "sha256 $it…" } ?: "checksum unknown"
            if (row.status == ModelRowStatus.INSTALLED_UNVERIFIED) {
                "$size · $checksum · not verified against a published value"
            } else {
                "$size · $checksum"
            }
        }
    }
    val description = describeStaged("${row.label} $statusWord. $subLine", staged)

    Column(modifier = modifier.fillMaxWidth().padding(vertical = OrtSpacing.sm)) {
        // Marker + name + sub-line are one merged, non-interactive semantics node (the row's
        // facts, read as one); the two actions below stay separate, independently-clickable
        // nodes — merging them into this node would swallow their own click semantics (the bug
        // R-024 already found once for a text-styled control with no real target of its own).
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
            horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
        ) {
            AssetMarker(status = row.status, isBusy = isBusy)
            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(OrtSpacing.xs)) {
                    Text(text = row.label, style = OrtType.rowTitle, color = OrtColors.textHigh)
                    if (row.status != ModelRowStatus.NOT_INSTALLED) {
                        Badge(text = "active", kind = BadgeKind.TIER)
                    }
                }
                Text(
                    text = subLine,
                    style = OrtType.subLine,
                    color = OrtColors.textDim,
                    modifier = Modifier.padding(top = 2.dp),
                )
                StagedBadgeText(staged)
            }
        }
        Row(
            modifier = Modifier.padding(top = OrtSpacing.xs, start = MARKER_COLUMN_WIDTH),
            horizontalArrangement = Arrangement.spacedBy(OrtSpacing.md),
        ) {
            if (row.checksumKnown && row.status == ModelRowStatus.NOT_INSTALLED) {
                TextAction(
                    text = "Download",
                    enabled = !isBusy,
                    onClick = { onDownload(row.id) },
                    modifier = Modifier.semantics { contentDescription = "Download ${row.label}" },
                )
            }
            TextAction(
                text = "Install from a file",
                enabled = !isBusy,
                onClick = { onSideload(row.id) },
                modifier = Modifier.semantics { contentDescription = "Install ${row.label} from a file" },
            )
        }
    }
}

/** R-140 (register, round 7 System validator pass 3): a multi-file model family (today, only the
 * Whisper tiny.en encoder/decoder/tokens split) as one row — the board's own
 * `whisper-small-int8`/`whisper-tiny-en-int8` pattern — instead of one full [AssetRow] per file.
 * The family's marker/`active` tag reflect every part together, never claiming installed while a
 * part is still missing (constitution I); each still-missing part keeps its own real action
 * (`Download`/`Install from a file`) nested beneath, so nothing reachable before is now hidden. */
@Composable
private fun GroupedAssetRow(
    familyLabel: String,
    parts: List<ModelRowViewState>,
    busy: Set<ModelId>,
    onDownload: (ModelId) -> Unit,
    onSideload: (ModelId) -> Unit,
    modifier: Modifier = Modifier,
    // FR-AST-4: real only when the staged asset id names one of [parts] — see [AssetRow]'s own
    // matching parameter.
    stagedAssetId: String? = null,
) {
    val missing = parts.filter { it.status == ModelRowStatus.NOT_INSTALLED }
    val fullyInstalled = missing.isEmpty()
    val fullyVerified = fullyInstalled && parts.all { it.status == ModelRowStatus.INSTALLED }
    val anyBusy = parts.any { it.id in busy }
    val staged = stagedAssetId != null && parts.any { it.id.name == stagedAssetId }
    val aggregateStatus = when {
        !fullyInstalled -> ModelRowStatus.NOT_INSTALLED
        fullyVerified -> ModelRowStatus.INSTALLED
        else -> ModelRowStatus.INSTALLED_UNVERIFIED
    }
    val subLine = when {
        anyBusy -> "downloading…"
        fullyInstalled -> {
            val size = formatAssetSize(parts.sumOf { it.sizeBytes ?: 0L })
            if (fullyVerified) {
                "$size · every part verified"
            } else {
                "$size · not every part verified against a published checksum"
            }
        }
        else -> "not installed · ${missing.size} of ${parts.size} parts missing"
    }
    val description =
        describeStaged("$familyLabel ${if (fullyInstalled) "installed" else "not installed"}. $subLine", staged)

    Column(modifier = modifier.fillMaxWidth().padding(vertical = OrtSpacing.sm)) {
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
            horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
        ) {
            AssetMarker(status = aggregateStatus, isBusy = anyBusy)
            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(OrtSpacing.xs)) {
                    Text(text = familyLabel, style = OrtType.rowTitle, color = OrtColors.textHigh)
                    if (fullyInstalled) Badge(text = "active", kind = BadgeKind.TIER)
                }
                Text(
                    text = subLine,
                    style = OrtType.subLine,
                    color = OrtColors.textDim,
                    modifier = Modifier.padding(top = 2.dp),
                )
                StagedBadgeText(staged)
            }
        }
        missing.forEach { part ->
            Row(
                modifier = Modifier.padding(top = OrtSpacing.xs, start = MARKER_COLUMN_WIDTH),
                horizontalArrangement = Arrangement.spacedBy(OrtSpacing.md),
            ) {
                Text(text = part.label, style = OrtType.subLine, color = OrtColors.textFaint)
                if (part.checksumKnown) {
                    TextAction(
                        text = "Download",
                        enabled = part.id !in busy,
                        onClick = { onDownload(part.id) },
                        modifier = Modifier.semantics { contentDescription = "Download ${part.label}" },
                    )
                }
                TextAction(
                    text = "Install from a file",
                    enabled = part.id !in busy,
                    onClick = { onSideload(part.id) },
                    modifier = Modifier.semantics { contentDescription = "Install ${part.label} from a file" },
                )
            }
        }
    }
}

/** R-154 (round 5): the Assets screen's own lexicon row — a real [LexiconAssetRowViewState],
 * never a [ModelId]-shaped one (there is no lexicon `ModelId`). "Install a lexicon from a file" is
 * this row's one action regardless of [LexiconAssetRowViewState.installed] — there is no download
 * path for the lexicon (FR-LEX-30/constitution V's own "sideload only" for this asset). */
@Composable
private fun LexiconAssetRow(
    row: LexiconAssetRowViewState,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
    // FR-AST-4: real only when the staged asset id is
    // [org.ort.app.ui.data.ModelsController.CALLSIGN_LEXICON_ASSET_ID] — see [AssetRow]'s own
    // matching parameter.
    staged: Boolean = false,
) {
    Text(
        text = "Callsign lexicon",
        style = OrtType.subLine,
        color = OrtColors.textFaint,
        modifier = Modifier.padding(top = OrtSpacing.sm),
    )
    val description = describeStaged(
        "Callsign lexicon ${if (row.installed) "installed" else "not installed"}. ${row.label}",
        staged,
    )
    Column(modifier = modifier.fillMaxWidth().padding(vertical = OrtSpacing.sm)) {
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
            horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
        ) {
            AssetMarker(
                status = if (row.installed) ModelRowStatus.INSTALLED else ModelRowStatus.NOT_INSTALLED,
                isBusy = false,
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(OrtSpacing.xs)) {
                    Text(text = "Callsign lexicon", style = OrtType.rowTitle, color = OrtColors.textHigh)
                    if (row.installed) Badge(text = "active", kind = BadgeKind.TIER)
                }
                Text(
                    text = row.label,
                    style = OrtType.subLine,
                    color = OrtColors.textDim,
                    modifier = Modifier.padding(top = 2.dp),
                )
                StagedBadgeText(staged)
            }
        }
        Row(modifier = Modifier.padding(top = OrtSpacing.xs, start = MARKER_COLUMN_WIDTH)) {
            TextAction(
                text = "Install a lexicon from a file",
                onClick = onInstall,
                modifier = Modifier.semantics { contentDescription = "Install a lexicon from a file" },
            )
        }
    }
}

/** `Fail-Lexicon.dc.html` (R-154, FR-LEX-12): a refused import — every check in [result.checks]
 * with its real outcome, the real refusal [reason][LexiconImportViewState.Rejected.reason], and
 * what remains active ([LexiconImportViewState.Rejected.stillActiveLabel]). Replaces the whole
 * Assets screen (not an inline card) while a rejection is showing, matching the board's own
 * full-screen presentation. */
@Composable
private fun FailLexiconScreen(
    result: LexiconImportViewState.Rejected,
    onChooseAnotherFile: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // R-493 (register, Reviewer D): the parent this drill-in is actually beneath is
        // "Models and lexicon" (Settings-Assets), not "Settings" itself — `onDone` already returns
        // there (`ModelsContent.onDismissResult` clears the import result in place, no navigation
        // change needed), this was only ever the header's own label being wrong.
        DrillInHeader(parentLabel = "Models and lexicon", onBack = onDone)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)) {
            Text(text = "Import refused", style = OrtType.screenTitle, color = OrtColors.textHigh)
            Text(
                text = "${result.fileName} · the current lexicon is untouched",
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs),
            )

            FailedState(
                title = "The file is not what its manifest says it is",
                body = failLexiconBannerBody(result),
                modifier = Modifier.padding(top = OrtSpacing.md),
            )

            SectionHeader(label = "What was checked", modifier = Modifier.padding(top = OrtSpacing.lg))
            result.checks.forEach { check -> LexiconCheckRow(check = check) }

            SectionHeader(label = "Still active", modifier = Modifier.padding(top = OrtSpacing.lg))
            StillActiveLexiconRow(result = result)

            Text(
                text = "Re-download the file and try again. An import only ever replaces the current " +
                    "lexicon after every check passes, and even then only at the next session.",
                style = OrtType.cardBody,
                color = OrtColors.textFaint,
                modifier = Modifier.padding(top = OrtSpacing.lg, bottom = OrtSpacing.lg),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = OrtSpacing.lg),
                horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
            ) {
                SecondaryButton(
                    text = "Choose another file",
                    onClick = onChooseAnotherFile,
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(text = "Done", onClick = onDone, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** R-491 (register, Reviewer D): the board's banner names the real consequence of a rejection that
 * the validator's own [LexiconImportViewState.Rejected.reason] alone does not — which lexicon stayed
 * active, and that capture kept running on it the whole time, unaware. [result.stillActiveLabel] is
 * the exact same real fact [StillActiveLexiconRow] below draws — never a second, invented claim
 * about it. */
private fun failLexiconBannerBody(result: LexiconImportViewState.Rejected): String {
    val stillActive = result.stillActiveLabel
    return if (stillActive != null) {
        "${result.reason} $stillActive is still active and capture never noticed."
    } else {
        result.reason
    }
}

/** R-492 (register, Reviewer D): the board's own STILL ACTIVE row — a green state dot (this
 * lexicon is genuinely in force, not merely on record) and two real lines, the name then
 * "N records · verified · in use by the running session". "Verified" and "in use by the running
 * session" are never separately-sourced fields: see [LexiconImportViewState.Rejected]'s own doc
 * comment for why both are structurally true of *any* [stillActiveLabel] this screen ever draws. */
@Composable
private fun StillActiveLexiconRow(result: LexiconImportViewState.Rejected, modifier: Modifier = Modifier) {
    val name = result.stillActiveLabel
    val recordCount = result.stillActiveRecordCount
    if (name == null || recordCount == null) {
        Text(
            text = "nothing — no lexicon was active before this import attempt",
            style = OrtType.control,
            color = OrtColors.textBody,
            modifier = modifier.padding(top = OrtSpacing.xs),
        )
        return
    }
    Row(
        modifier = modifier.padding(top = OrtSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
    ) {
        Canvas(modifier = Modifier.padding(top = 5.dp).size(9.dp)) { drawCircle(color = OrtColors.accentGreen) }
        Column {
            Text(text = name, style = OrtType.control, color = OrtColors.textBody)
            Text(
                text = "${"%,d".format(java.util.Locale.ROOT, recordCount)} records · verified · " +
                    "in use by the running session",
                style = OrtType.subLine,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** `Fail-Lexicon.dc.html`'s "What was checked" row: a filled green check, a filled red cross, or a
 * hollow ring for [CheckStatus.NOT_REACHED] — the guide's own text-not-colour-alone discipline
 * (the status word is always in [org.ort.lexicon.import.LexiconCheck.detail] too, never colour-only). */
@Composable
private fun LexiconCheckRow(check: LexiconCheckViewRow, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = OrtSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
    ) {
        LexiconCheckMarker(status = check.status)
        Column {
            Text(
                text = check.name,
                style = OrtType.rowTitle,
                color = if (check.status == CheckStatus.NOT_REACHED) OrtColors.textFaint else OrtColors.textHigh,
            )
            Text(
                text = check.detail,
                style = OrtType.subLine,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** R-490 (register, Reviewer D): the board draws each check's own outcome as a filled, 20dp
 * circular badge (green + a check mark, or halt-red + a cross) — not a bare tinted glyph on
 * transparent background. [CheckStatus.NOT_REACHED] keeps the existing hollow ring: the board's
 * own row for it (`lexicon-corrupt/F12-lexicon-assets.png`) draws no fill at all there either. */
@Composable
private fun LexiconCheckMarker(status: CheckStatus, modifier: Modifier = Modifier) {
    when (status) {
        CheckStatus.PASSED -> FilledCheckBadge(
            background = OrtColors.accentGreen,
            icon = OrtIcons.check,
            iconTint = OrtColors.accentOnGreen,
            modifier = modifier,
        )
        CheckStatus.FAILED -> FilledCheckBadge(
            background = OrtColors.haltFill,
            icon = OrtIcons.dismiss,
            iconTint = OrtColors.haltOnFill,
            modifier = modifier,
        )
        CheckStatus.NOT_REACHED -> Canvas(modifier = modifier.size(20.dp)) {
            drawCircle(
                color = OrtColors.lineControl,
                radius = size.minDimension / 2 - 0.75.dp.toPx(),
                style = Stroke(1.5.dp.toPx()),
            )
        }
    }
}

@Composable
private fun FilledCheckBadge(background: Color, icon: ImageVector, iconTint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(20.dp).background(background, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(12.dp))
    }
}

/** R-140: [ModelId]'s real family grouping — the ASR encoder/decoder/tokens are three files of one
 * Whisper model; VAD stands alone. A `when` over every [ModelId] (no `else`) so a future asset
 * added to that enum fails to compile here rather than silently landing in the wrong (or no)
 * group. */
private fun familyOf(id: ModelId): String = when (id) {
    ModelId.ASR_ENCODER, ModelId.ASR_DECODER, ModelId.ASR_TOKENS -> "Whisper tiny.en (speech to text)"
    ModelId.VAD -> "Silero VAD (voice activity)"
}

private data class AssetGroup(val familyLabel: String, val parts: List<ModelRowViewState>)

/** Groups [rows] by [familyOf], preserving each family's first-seen order (never re-sorted —
 * [ModelsViewState.rows]' own order is [ModelCatalog][org.ort.data.ModelCatalog]'s, not this
 * screen's to reinterpret). */
private fun groupAssetRows(rows: List<ModelRowViewState>): List<AssetGroup> {
    val order = LinkedHashMap<String, MutableList<ModelRowViewState>>()
    rows.forEach { row -> order.getOrPut(familyOf(row.id)) { mutableListOf() }.add(row) }
    return order.map { (family, parts) -> AssetGroup(family, parts) }
}

private val MARKER_COLUMN_WIDTH = 17.dp

/** FR-AST-4 (register, WP11b's F21 audit finding): the coordinator's own brief, verbatim — the one
 * wording every staged row uses, real and honest (a session really is live, and this really is
 * exactly when it will really activate — never "soon" or "later"). */
private const val STAGED_BADGE_TEXT = "staged · activates when this session ends"

/** Shared by [AssetRow]/[GroupedAssetRow]/[LexiconAssetRow] — appends [STAGED_BADGE_TEXT]'s own
 * fact to an already-built merged-semantics description, exactly once, in exactly one place. */
private fun describeStaged(description: String, staged: Boolean): String =
    if (staged) "$description. staged, activates when this session ends" else description

/** The visible staged badge every asset row draws underneath its own sub-line, or nothing at all
 * when [staged] is false — split out purely so three call sites don't each repeat the same branch
 * and the same [Text] block (each call site's own cyclomatic complexity stays lower this way,
 * under detekt's threshold). */
@Composable
private fun StagedBadgeText(staged: Boolean, modifier: Modifier = Modifier) {
    if (!staged) return
    Text(
        text = STAGED_BADGE_TEXT,
        style = OrtType.subLine,
        color = OrtColors.accentAmber,
        modifier = modifier.padding(top = 2.dp),
    )
}

/** guide's marker vocabulary applied to an asset rather than an attribution — verified (solid
 * green), installed-unverified (half-filled amber, echoing AMBIGUOUS's "not fully trusted" shape
 * without claiming it *is* an attribution), not installed (hollow ring). */
@Composable
private fun AssetMarker(status: ModelRowStatus, isBusy: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.padding(top = 5.dp).size(9.dp)) {
        val d = size.minDimension
        val ringStroke = Stroke(1.5.dp.toPx())
        when {
            isBusy -> drawCircle(color = OrtColors.textSignal, radius = d / 2 - 0.75.dp.toPx(), style = ringStroke)
            status == ModelRowStatus.INSTALLED -> drawCircle(color = OrtColors.accentGreen, radius = d / 2)
            status == ModelRowStatus.INSTALLED_UNVERIFIED ->
                drawCircle(color = OrtColors.accentAmber, radius = d / 2 - 0.75.dp.toPx(), style = ringStroke)
            else -> drawCircle(color = OrtColors.lineControl, radius = d / 2 - 0.75.dp.toPx(), style = ringStroke)
        }
    }
}

private fun formatAssetSize(bytes: Long): String {
    val mb = bytes / 1_000_000.0
    val kb = bytes / 1_000.0
    return when {
        mb >= 1000.0 -> "%.1f GB".format(mb / 1000.0)
        mb >= 1.0 -> "%.0f MB".format(mb)
        else -> "%.0f KB".format(kb)
    }
}
