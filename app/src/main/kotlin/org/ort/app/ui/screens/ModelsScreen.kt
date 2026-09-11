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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
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
import org.ort.app.ui.components.ToggleRow
import org.ort.app.ui.data.LexiconAssetActions
import org.ort.app.ui.data.LexiconAssetRowViewState
import org.ort.app.ui.data.LexiconCheckViewRow
import org.ort.app.ui.data.LexiconImportViewState
import org.ort.app.ui.data.ModelCatalog
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
    // WPE (`Settings-Assets.dc.html`, redrawn 2026-09-10, D35/D36, FR-DIG-3b, FR-AST-3a, AC-139):
    // the Prose-digest and Space sections, plus [busy] (folded in here rather than left as its own
    // bare parameter) — the same detekt-threshold reason [ModelsScreenStatus]/[LexiconAssetActions]
    // are bundles rather than one bare parameter each. `null` (the default) keeps every existing
    // caller compiling unchanged, [busy] reading as its own real default (empty), and draws
    // neither new section — matching [lexicon]'s own established convention.
    extras: ModelsExtrasViewState? = null,
) {
    val busy = extras?.busy ?: emptySet()
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
            // R-841 (round 1, tour finding): the pre-D35 subtitle ("Installed by you, from a
            // file…") described a download-and-sideload screen that no longer exists — every
            // asset ships inside the artifact now (D35). `Settings-Assets.dc.html`'s own redrawn
            // subtitle, verbatim.
            Text(
                text = "Bundled with the app and verified on first launch. Nothing is downloaded.",
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

            // `Settings-Assets.dc.html` (redrawn 2026-09-10): "Transcription" — every non-LLM
            // model family. `ModelId.LLM_GEMMA3_1B` gets its own "Prose digest" section below,
            // never joining this grouped list — a solid-green "active" marker there would wrongly
            // imply the model is loaded, when D36/AC-138 mean it almost never is.
            SectionHeader(label = "Transcription", modifier = Modifier.padding(top = OrtSpacing.lg))
            AssetGroupsList(
                rows = state.rows.filterNot { it.id == ModelId.LLM_GEMMA3_1B },
                busy = busy,
                onDownload = onDownload,
                onSideload = onSideload,
                stagedAssetId = stagedActivation?.assetId,
            )
            // R-841 (round 1, tour finding): `Settings-Assets.dc.html`'s own section order is
            // Transcription, Prose digest, Lexicon, Space, Replacing an asset — Prose digest sits
            // here, before Lexicon, never bundled with Space after it (order is binding,
            // constitution VIII).
            ProseDigestSection(rows = state.rows, proseDigest = extras?.proseDigest)

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

            SpaceSection(spaceUsedBytesLabel = extras?.spaceUsedBytesLabel)

            ReplacingAnAssetSection()

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

/** CF04's Prose-digest toggle row (`Settings-Assets.dc.html`, FR-DIG-3b) — bundled with [enabled]/
 * [onToggle] so [ModelsScreen]'s own parameter list stays under detekt's threshold, the same reason
 * [ModelsScreenStatus]/[LexiconAssetActions] are bundles rather than bare parameters. */
public data class ProseDigestSectionViewState(val enabled: Boolean, val onToggle: (Boolean) -> Unit)

/** [ModelsScreen]'s own bundle of everything WPE's CF04 redraw added — see that parameter's own
 * doc comment for why. [spaceUsedBytesLabel] is `null` while
 * [org.ort.pipeline.capture.StorageAccounting.bundledBytes] has not measured yet; the row simply
 * does not draw until it has (this screen never blocks on it). [busy] is the pre-existing
 * `ModelsScreen(busy = ...)` parameter, moved here purely to keep the function's own parameter
 * list under detekt's threshold once this bundle itself became the ninth. */
public data class ModelsExtrasViewState(
    val proseDigest: ProseDigestSectionViewState? = null,
    val spaceUsedBytesLabel: String? = null,
    val busy: Set<ModelId> = emptySet(),
)

/** E2-F04/F05 (`spec/e2e-capture-modes-plan.md`): the stable handle a test or the tour uses to find
 * CF04's own "Write prose summaries" toggle. */
public const val PROSE_DIGEST_TOGGLE_TEST_TAG: String = "settings-assets-prose-digest-toggle"

/** The Prose-digest section, split out of [ModelsScreen] purely to keep that function under
 * detekt's length limit — the same reason [AssetGroupsList]/[ModelsNotices] already are. */
@Composable
private fun ProseDigestSection(
    rows: List<ModelRowViewState>,
    proseDigest: ProseDigestSectionViewState?,
    modifier: Modifier = Modifier,
) {
    rows.firstOrNull { it.id == ModelId.LLM_GEMMA3_1B }?.let { gemmaRow ->
        Column(modifier = modifier) {
            SectionHeader(label = "Prose digest", modifier = Modifier.padding(top = OrtSpacing.lg))
            ProseDigestModelRow(row = gemmaRow)
            proseDigest?.let {
                ToggleRow(
                    label = "Write prose summaries",
                    checked = it.enabled,
                    onCheckedChange = it.onToggle,
                    subLine = "an addition to the digest, never a replacement · marked as generated · " +
                        "frees its memory when off",
                    modifier = Modifier.testTag(PROSE_DIGEST_TOGGLE_TEST_TAG),
                )
            }
        }
    }
}

/** The Space row, split out of [ModelsScreen] for the same reason [ProseDigestSection] is —
 * `Settings-Assets.dc.html`'s own section, positioned after Lexicon, never bundled with
 * Prose-digest (R-841: order is binding). */
@Composable
private fun SpaceSection(spaceUsedBytesLabel: String?, modifier: Modifier = Modifier) {
    spaceUsedBytesLabel?.let { sizeLabel ->
        Column(modifier = modifier) {
            SectionHeader(label = "Space", modifier = Modifier.padding(top = OrtSpacing.lg))
            Text(
                text = "Bundled assets use $sizeLabel",
                style = OrtType.rowTitle,
                color = OrtColors.textHigh,
                modifier = Modifier.padding(top = OrtSpacing.sm),
            )
            Text(
                text = "not counted against your recording budget · a model this phone cannot run " +
                    "is kept, never loaded",
                style = OrtType.subLine,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * R-841 (round 1, tour finding — a whole section omitted from the redraw): `Settings-Assets.dc.html`'s
 * own "Replacing an asset" row — a standing policy statement, always shown (never conditioned on
 * anything actually being staged right now; [StagedBadgeText] on the row itself is the *live*
 * version of this same fact). The half-filled amber marker is the board's own "pending/cost"
 * bullet, per register R-801's own note that this exact half-circle is reused across boards
 * (`Setup-Rig-Usb`, `Fail-Rig`) as a pending/cost marker outside attribution.
 */
@Composable
private fun ReplacingAnAssetSection(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        SectionHeader(label = "Replacing an asset", modifier = Modifier.padding(top = OrtSpacing.lg))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = OrtSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
        ) {
            Canvas(modifier = Modifier.padding(top = 5.dp).size(9.dp)) {
                drawCircle(
                    brush = Brush.horizontalGradient(0.5f to OrtColors.accentAmber, 0.5f to Color.Transparent),
                )
                drawCircle(color = OrtColors.accentAmber, style = Stroke(1.5.dp.toPx()))
            }
            Column {
                Text(text = "Takes effect at the next session", style = OrtType.rowTitle, color = OrtColors.textHigh)
                Text(
                    text = "a model or lexicon is never swapped under a running capture · the bundled copy " +
                        "stays as the fallback",
                    style = OrtType.subLine,
                    color = OrtColors.textDim,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
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
        row.status == ModelRowStatus.NOT_INSTALLED -> notInstalledLabel(row)
        else -> assetFactsSubLine(row, passLabelFor(row.id))
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
            if (offersDownload(row)) {
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

/**
 * R-140 (register, round 7 System validator pass 3): a multi-file model family (today, only the
 * Whisper tiny.en encoder/decoder/tokens split) as one row — the board's own
 * `whisper-small-int8`/`whisper-tiny-en-int8` pattern — instead of one full [AssetRow] per file.
 * The family's marker/`active` tag reflect every part together, never claiming installed while a
 * part is still missing (constitution I).
 *
 * R-761 (register, design, confirmation sweep): round 7's own fix collapsed the family into one
 * *header* row but then rendered one full sub-row **per still-missing part** beneath it — three
 * rows, two actions each, six buttons total on a clean install (`model-missing/CF04-settings-
 * assets.png`) — instead of `Settings-Assets.dc.html`'s own single-row-per-asset shape (one
 * marker, its trailing action(s), nothing nested beneath it). The real constraint the board's own
 * mockup never has to show: encoder/decoder/tokens genuinely are three separate files, so no
 * single tap can install all three at once the way a true single-file row's own actions can — but
 * an operator sideloading three files does that three separate times regardless of how many
 * buttons are on screen at once. [missing]`.firstOrNull()` is what changed: this row now shows
 * exactly *one* still-missing part's own actions at a time (the same `Download`/`Install from a
 * file` pair [AssetRow] already offers a single file, just addressed at whichever part is next),
 * not one nested row per part — [subLine] still honestly names how many parts remain, and the row
 * itself re-renders with the *next* missing part's actions the moment [parts] reports one fewer.
 */
/** [GroupedAssetRow]'s own `subLine` formula, split out purely to keep that composable under
 * detekt's `LongMethod` limit (R-865's own placeholder guard is what pushed it over). */
private fun groupedAssetSubLine(
    parts: List<ModelRowViewState>,
    missing: List<ModelRowViewState>,
    anyBusy: Boolean,
    fullyInstalled: Boolean,
    fullyVerified: Boolean,
    passLabel: String,
): String = when {
    anyBusy -> "downloading…"
    fullyInstalled -> {
        // R-865: the declared size (catalogue, build-time) is this row's own primary figure —
        // real always, unlike a sum of real on-disk lengths, which a test/dev fixture's
        // placeholder shortcut can silently zero out (see `declaredSizeLabel`'s own doc
        // comment). The real on-disk sum is still shown, secondarily, only when every part's
        // own real length is itself real (nonzero) — a family with even one placeholder part
        // is honestly flagged instead.
        val size = formatAssetSize(parts.sumOf { ModelCatalog.entry(it.id).sizeBytes })
        val bundledNote = if (parts.all { it.bundled }) "bundled" else "not every part bundled"
        if (parts.any { isZeroBytePlaceholder(it) }) {
            "$passLabel · $size · $bundledNote · placeholder — no bytes"
        } else {
            val onDisk = onDiskClause(parts.sumOf { it.sizeBytes ?: 0L })?.let { " · $it" }.orEmpty()
            val tiersLabel = tiersLabelFor(parts.first())
            if (fullyVerified) {
                "$passLabel · $size$onDisk · $bundledNote · every part verified · $tiersLabel"
            } else {
                "$passLabel · $size$onDisk · $bundledNote · not every part verified against a published checksum"
            }
        }
    }
    else -> {
        // R-841 (round 1, tour finding): "not installed" implies an operator owes this an
        // action, which D35 makes untrue for a bundled family — [notInstalledLabel]'s own
        // per-part reasoning, folded to the worst (least honest-sounding-as-fine) case among
        // the missing parts, same as the marker's own aggregate above.
        val lead = missing.map { notInstalledLeadFor(it) }.distinct().singleOrNull() ?: "not installed"
        "$lead · ${missing.size} of ${parts.size} parts missing"
    }
}

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
    val passLabel = passLabelFor(parts.first().id)
    val subLine = groupedAssetSubLine(parts, missing, anyBusy, fullyInstalled, fullyVerified, passLabel)
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
        // R-761: exactly one nested row, for the next still-missing part only — see this
        // composable's own doc comment for why three (one per part) is not shown at once.
        missing.firstOrNull()?.let { part ->
            Row(
                modifier = Modifier.padding(top = OrtSpacing.xs, start = MARKER_COLUMN_WIDTH),
                horizontalArrangement = Arrangement.spacedBy(OrtSpacing.md),
            ) {
                Text(text = part.label, style = OrtType.subLine, color = OrtColors.textFaint)
                if (offersDownload(part)) {
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
    // R-841 (round 1, tour finding): `Settings-Assets.dc.html`'s own "LEXICON" caption is a real
    // `.lbl` section header — the same treatment "Transcription"/"Prose digest"/"Space" already
    // get — not a family-caption-style `Text` (this row's own asset name, "Callsign lexicon",
    // already appears on the row itself, just below).
    SectionHeader(label = "Lexicon", modifier = Modifier.padding(top = OrtSpacing.lg))
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
    // WPG (D36): minimal, mechanical addition forced by this when's own documented design (no
    // `else`, so a new ModelId fails to compile here) — this file is otherwise WPE's row per
    // spec/e2e-capture-modes-plan.md; the crossing is reported in WPG's own build report rather
    // than left as a silent compile fix.
    ModelId.LLM_GEMMA3_1B -> "Gemma 3 1B int4 (prose digest)"
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

/**
 * Register R-865 (Validator V9, device): the row's own real on-disk length ([ModelRowViewState
 * .sizeBytes]) is a real `File.length()` — honest for a genuine install, but a test/dev fixture
 * that shortcuts installation with an empty placeholder file makes it `0L`, and rendering that as
 * this row's *primary* size fabricated "0 KB" for an asset that is really tens of megabytes. The
 * catalogue's own declared size ([org.ort.app.ui.data.ModelCatalogEntry.sizeBytes], build-time,
 * from the manifest — never dependent on what happens to be on disk this launch) is the stable,
 * always-real figure every row's own primary size now reads; the real measured length is still
 * shown, as a clearly-labelled secondary "on disk: N MB" fact, but only when it is itself real
 * (nonzero) — see [onDiskClause]/[isZeroBytePlaceholder].
 */
private fun declaredSizeLabel(id: ModelId): String = formatAssetSize(ModelCatalog.entry(id).sizeBytes)

/** R-865: the honest secondary "on disk" fact — `null` (nothing appended) unless [sizeBytes] is a
 * real, nonzero measured length. A verified `0L` is never treated as "real bytes on disk" (see
 * [isZeroBytePlaceholder]) — this function and that one share the exact same `> 0L` test on
 * purpose, so a row can never disagree with itself about whether its own on-disk file is real. */
private fun onDiskClause(sizeBytes: Long?): String? =
    sizeBytes?.takeIf { it > 0L }?.let { "on disk: ${formatAssetSize(it)}" }

/** R-865: `true` exactly when a row claims to be installed/verified (never [ModelRowStatus
 * .NOT_INSTALLED], which has no size to claim at all) yet its own real, measured length is `0L` —
 * a fact no genuinely verified file can ever have (a real checksum is never computed over zero
 * bytes) and therefore the unambiguous signature of a test/dev fixture's placeholder shortcut, not
 * a legitimate installed asset. Rendered as an honest "placeholder — no bytes" rather than a
 * fabricated "0 KB · verified <checksum>" (constitution I). */
private fun isZeroBytePlaceholder(row: ModelRowViewState): Boolean =
    row.status != ModelRowStatus.NOT_INSTALLED && row.sizeBytes == 0L

private fun formatAssetSize(bytes: Long): String {
    val mb = bytes / 1_000_000.0
    val kb = bytes / 1_000.0
    return when {
        mb >= 1000.0 -> "%.1f GB".format(mb / 1000.0)
        mb >= 1.0 -> "%.0f MB".format(mb)
        else -> "%.0f KB".format(kb)
    }
}

/**
 * R-841 (round 1, tour finding — audited against `Settings-Assets.dc.html` verbatim, not a
 * hypothesised universal formula): the board's own Silero VAD row reads "segmentation · 2 MB ·
 * bundled · verified 9e2449e1" — no "sha256" word, no trailing tiers clause (unlike the Whisper
 * family row, which is [GroupedAssetRow]'s own, separate formula and does carry both). [AssetRow]'s
 * only real caller today is VAD (the one single-file family — Whisper is always three files,
 * always grouped), so this matches that row's own board text exactly rather than forcing a
 * uniform shape the board itself does not use.
 */
private fun assetFactsSubLine(row: ModelRowViewState, passLabel: String): String {
    val size = declaredSizeLabel(row.id)
    val bundledNote = if (row.bundled) "bundled" else "not bundled"
    // R-865: a verified-but-zero-byte row is a placeholder fixture, never a real 0 KB asset —
    // say so plainly instead of a fabricated "verified <checksum>" over bytes that do not exist.
    if (isZeroBytePlaceholder(row)) {
        return "$passLabel · $size · $bundledNote · placeholder — no bytes"
    }
    val onDisk = onDiskClause(row.sizeBytes)?.let { " · $it" }.orEmpty()
    val checksum = row.checksumPrefix?.let { "verified $it" } ?: "checksum unknown"
    val verifiedNote = if (row.status == ModelRowStatus.INSTALLED_UNVERIFIED) {
        "$checksum, not against a published value"
    } else {
        checksum
    }
    return "$passLabel · $size$onDisk · $bundledNote · $verifiedNote"
}

/** `Settings-Assets.dc.html`'s own per-asset "Pass" label — the same [Pass][org.ort.core.Tier]
 * vocabulary the rest of the app uses for what a model is *for*, not just its family name
 * ([familyOf], which groups files, not passes). */
private fun passLabelFor(id: ModelId): String = when (id) {
    ModelId.ASR_ENCODER, ModelId.ASR_DECODER, ModelId.ASR_TOKENS -> "Pass B"
    ModelId.VAD -> "segmentation"
    ModelId.LLM_GEMMA3_1B -> "prose digest"
}

/** `Settings-Assets.dc.html`'s own tiers clause — "every tier" for the closed
 * [org.ort.app.ui.data.ModelCatalogEntry.tiers] set every asset but Gemma carries today; the
 * tier-restricted case (`!row.tierEligible`) is Gemma's own dedicated wording in
 * [ProseDigestModelRow], never reused here since no other asset is tier-gated yet. */
private fun tiersLabelFor(row: ModelRowViewState): String = if (row.tierEligible) "every tier" else "this tier only"

/**
 * R-841 (round 1, tour finding): "not installed · N of 3 parts missing" told the operator they
 * owed this an install action, which D35 makes untrue for a bundled asset — every real `ModelId`
 * ships inside the artifact. `NOT_INSTALLED` for a bundled, non-gated row means
 * [org.ort.app.assets.BundledAssetInstaller] has not (yet, or successfully) copied and verified it
 * this launch, never that the operator must do something; "not in this build" is reserved for the
 * one state [ModelRowStatus] cannot express directly — [org.ort.app.ui.data.ModelCatalogEntry.gated]
 * true and still missing (the dev escape hatch, confirmed by reading `ModelsController.currentState`/
 * `rowFor` before writing this: an absent `.sha256` marker alone cannot distinguish the two cases;
 * `gated` is the one real field that can). A genuinely non-bundled row (hypothetical today —
 * [org.ort.app.ui.data.ModelCatalogEntry.bundled] `false`, the split-delivery TODO that field's own
 * doc comment names) keeps the plain "not installed" an operator really would need to act on.
 */
private fun notInstalledLeadFor(row: ModelRowViewState): String = when {
    ModelCatalog.entry(row.id).gated -> "not in this build — needs the gated download at build time"
    row.bundled -> "not yet verified on this launch"
    else -> "not installed"
}

/** [AssetRow]'s/[ProseDigestModelRow]'s own full `NOT_INSTALLED` sub-line — [notInstalledLeadFor]'s
 * lead phrase, plus the real sideload-only reason when [ModelRowViewState.checksumKnown] is false
 * (unrelated to whether the row is bundled — a checksum-unknown asset needs sideload regardless). */
private fun notInstalledLabel(row: ModelRowViewState): String = if (!row.checksumKnown) {
    "not installed · sideload only, " + row.detail.orEmpty()
} else {
    notInstalledLeadFor(row)
}

/**
 * `Settings-Assets.dc.html` (D35, FR-AST-1): a bundled asset already ships inside the installed
 * artifact — there is nothing to download, and offering the action anyway would silently lie about
 * what a tap does. `row.checksumKnown` still gates it too (a row with no published digest can never
 * be safely verified after a fetch — [org.ort.app.ui.data.ModelsController.download]'s own refusal,
 * mirrored here so the button is never shown for an action that would only fail).
 */
internal fun offersDownload(row: ModelRowViewState): Boolean =
    row.checksumKnown && !row.bundled && row.status == ModelRowStatus.NOT_INSTALLED

/**
 * CF04's Gemma row (`Settings-Assets.dc.html`, D36, FR-AST-3a, FR-DIG-3b) — its own composable
 * (not [AssetRow]) because its installed marker reads differently from an ordinary model: never an
 * "active" tag (AC-138 — stored is not loaded, and a solid marker would imply otherwise). Its own
 * `NOT_INSTALLED` case shares [notInstalledLabel] with every other row (the gated/bundled/plain
 * three-way split lives there, once, not duplicated here).
 */
@Composable
private fun ProseDigestModelRow(row: ModelRowViewState, modifier: Modifier = Modifier) {
    val subLine = when {
        row.status == ModelRowStatus.NOT_INSTALLED -> notInstalledLabel(row)
        else -> {
            // R-841/R-842: the board's own Gemma line reads "verified e3d981c0" — no "sha256"
            // word (`Settings-Assets.dc.html` verbatim) — and states the tier-3-only load rule
            // regardless of *this device's* current tier (`row.tierEligible` is never consulted
            // here): AC-138 means "stored, loaded only at tier 3" is permanently true of this
            // asset, not a fact that changes with the phone it happens to run on.
            val size = declaredSizeLabel(row.id)
            if (isZeroBytePlaceholder(row)) {
                "$size · bundled · placeholder — no bytes"
            } else {
                val onDisk = onDiskClause(row.sizeBytes)?.let { " · $it" }.orEmpty()
                val checksum = row.checksumPrefix?.let { "verified $it" } ?: "checksum unknown"
                "$size$onDisk · bundled · $checksum · stored, loaded only at tier 3 while idle and charging"
            }
        }
    }
    val description = "${row.label}. $subLine"
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = OrtSpacing.sm)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
    ) {
        // A hollow ring always — never the solid "active"/installed marker: AC-138 means this
        // model is on disk far more often than it is ever actually loaded, and a solid green dot
        // would read exactly like every other row's "resident and running" fact, which this one
        // almost never is.
        Canvas(modifier = Modifier.padding(top = 5.dp).size(9.dp)) {
            drawCircle(
                color = OrtColors.lineControl,
                radius = size.minDimension / 2 - 0.75.dp.toPx(),
                style = Stroke(1.5.dp.toPx()),
            )
        }
        Column {
            Text(text = row.label, style = OrtType.rowTitle, color = OrtColors.textHigh)
            Text(
                text = subLine,
                style = OrtType.subLine,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
