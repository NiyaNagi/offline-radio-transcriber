package org.ort.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
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
import org.ort.app.ui.data.ModelId
import org.ort.app.ui.data.ModelRowStatus
import org.ort.app.ui.data.ModelRowViewState
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
            // R-140 (round 4, System validator): grouped by real model family — was one flat row
            // per [ModelId], so the Whisper encoder/decoder/tokens files (three real, independently
            // downloadable/sideloadable parts of *one* model — `ModelsController`'s own unit of
            // action) read as three unrelated assets. Grouping states the split honestly (a family
            // caption, each part still its own row with its own real actions) rather than either
            // hiding the split or leaving it unexplained.
            groupAssetRows(state.rows).forEach { group ->
                Text(
                    text = group.familyLabel,
                    style = OrtType.subLine,
                    color = OrtColors.textFaint,
                    modifier = Modifier.padding(top = OrtSpacing.sm),
                )
                group.parts.forEach { row ->
                    AssetRow(row = row, isBusy = row.id in busy, onDownload = onDownload, onSideload = onSideload)
                }
            }
            // R-154 (round 5): the callsign lexicon is a real asset ([org.ort.data.entity.LexiconVersionEntity],
            // via [org.ort.app.ui.data.ModelsController.lexiconRow]) with no [ModelId] of its own —
            // it gets its own family caption and row rather than joining `groupAssetRows` above,
            // since its install path (`installLexicon`, real validation + activation) is genuinely
            // different from a plain checksum-and-copy model download.
            lexicon?.row?.let { row -> LexiconAssetRow(row = row, onInstall = lexicon.onInstall) }

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
        // R-140: a failed download used to leave `:net`'s raw exception text sitting in the same
        // plain-body slot a success message uses — this is the amber `FailedState` every other
        // operator-facing failure in this build gets, with a real `Retry`.
        status.downloadFailure?.let { failure ->
            FailedState(
                title = "${failure.id.label} could not be downloaded",
                body = "The download did not complete: ${failure.reason}. The version already " +
                    "installed, if any, is unchanged.",
                actionLabel = "Retry",
                onAction = { onRetryDownload(failure.id) },
                modifier = Modifier.padding(top = OrtSpacing.sm),
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
    val description = "${row.label} $statusWord. $subLine"

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

/** R-154 (round 5): the Assets screen's own lexicon row — a real [LexiconAssetRowViewState],
 * never a [ModelId]-shaped one (there is no lexicon `ModelId`). "Install a lexicon from a file" is
 * this row's one action regardless of [LexiconAssetRowViewState.installed] — there is no download
 * path for the lexicon (FR-LEX-30/constitution V's own "sideload only" for this asset). */
@Composable
private fun LexiconAssetRow(row: LexiconAssetRowViewState, onInstall: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = "Callsign lexicon",
        style = OrtType.subLine,
        color = OrtColors.textFaint,
        modifier = Modifier.padding(top = OrtSpacing.sm),
    )
    val description = "Callsign lexicon ${if (row.installed) "installed" else "not installed"}. ${row.label}"
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
        DrillInHeader(parentLabel = "Settings", onBack = onDone)
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
                body = result.reason,
                modifier = Modifier.padding(top = OrtSpacing.md),
            )

            SectionHeader(label = "What was checked", modifier = Modifier.padding(top = OrtSpacing.lg))
            result.checks.forEach { check -> LexiconCheckRow(check = check) }

            SectionHeader(label = "Still active", modifier = Modifier.padding(top = OrtSpacing.lg))
            Text(
                text = result.stillActiveLabel ?: "nothing — no lexicon was active before this import attempt",
                style = OrtType.control,
                color = OrtColors.textBody,
                modifier = Modifier.padding(top = OrtSpacing.xs),
            )

            Text(
                text = "Re-download the file and try again. An import only ever replaces the current " +
                    "lexicon after every check passes.",
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

@Composable
private fun LexiconCheckMarker(status: CheckStatus, modifier: Modifier = Modifier) {
    when (status) {
        CheckStatus.PASSED -> Icon(
            imageVector = OrtIcons.check,
            contentDescription = null,
            tint = OrtColors.accentGreen,
            modifier = modifier.padding(top = 2.dp).size(18.dp),
        )
        CheckStatus.FAILED -> Icon(
            imageVector = OrtIcons.dismiss,
            contentDescription = null,
            tint = OrtColors.haltFill,
            modifier = modifier.padding(top = 2.dp).size(18.dp),
        )
        CheckStatus.NOT_REACHED -> Canvas(modifier = modifier.padding(top = 6.dp).size(9.dp)) {
            drawCircle(
                color = OrtColors.lineControl,
                radius = size.minDimension / 2 - 0.75.dp.toPx(),
                style = Stroke(1.5.dp.toPx()),
            )
        }
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
