package org.ort.app.ui.screens

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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import org.ort.app.ui.components.Badge
import org.ort.app.ui.components.BadgeKind
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.data.TranscriptVersionViewState
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * R-055, `Detail-Revisions.dc.html`: version cards (current / superseded; pass, model, time, who),
 * a word-level diff against the current version, and `Restore` (a **new** current transcript row —
 * nothing deleted, constitution III). [org.ort.app.ui.data.CorrectionPolling.revisions] supplies
 * every real version; nothing here is inlined only when the list is short — the exhaustive list is
 * always shown.
 */
@Composable
public fun DetailRevisionsScreen(
    parentLabel: String,
    versions: List<TranscriptVersionViewState>,
    onRestore: (versionId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = versions.firstOrNull { it.isCurrent }
    Column(modifier = modifier.fillMaxSize()) {
        DrillInHeader(parentLabel = parentLabel, onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)) {
            Text(text = "Earlier versions", style = OrtType.screenTitle, color = OrtColors.textHigh)
            Text(
                text = "${versions.size} versions · none deleted · the current one is what the log shows",
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs),
            )
        }
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            versions.forEach { version -> VersionCard(version, current, onRestore) }
        }
    }
}

@Composable
private fun VersionCard(
    version: TranscriptVersionViewState,
    current: TranscriptVersionViewState?,
    onRestore: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.md)
            .semantics(mergeDescendants = true) {
                contentDescription = "${if (version.isCurrent) "current" else "superseded"}, ${version.who}, " +
                    version.timeLabel
            },
    ) {
        Row {
            Badge(
                text = if (version.isCurrent) "current" else "superseded",
                kind = if (version.isCurrent) BadgeKind.NEW else BadgeKind.TIER,
            )
        }
        Text(
            text = "${version.who} · ${version.timeLabel}",
            style = OrtType.cardBody,
            color = OrtColors.textFaint,
            modifier = Modifier.padding(top = OrtSpacing.xs),
        )
        if (version.isCurrent || current == null || current.text == version.text) {
            Text(
                text = version.text,
                style = OrtType.control,
                color = OrtColors.textBody,
                modifier = Modifier.padding(top = OrtSpacing.sm),
            )
        } else {
            Text(
                text = wordDiff(old = version.text, new = current.text),
                style = OrtType.control,
                color = OrtColors.textBody,
                modifier = Modifier.padding(top = OrtSpacing.sm),
            )
        }
        if (!version.isCurrent) {
            TextAction(
                text = "Restore",
                onClick = { onRestore(version.id) },
                modifier = Modifier.padding(top = OrtSpacing.xs),
            )
        }
    }
}

/**
 * A word-level LCS diff (`Detail-Revisions.dc.html`: "kilo juliet ... november [del]pop a[/del]
 * [ins]papa[/ins] charlie"). Words only in [old] are struck through in amber; words only in [new]
 * are highlighted green immediately after — matching the artboard's inline convention exactly.
 */
private fun wordDiff(old: String, new: String) = buildAnnotatedString {
    val oldWords = old.split(Regex("\\s+")).filter { it.isNotEmpty() }
    val newWords = new.split(Regex("\\s+")).filter { it.isNotEmpty() }
    val ops = WordDiff.diff(oldWords, newWords)
    ops.forEachIndexed { index, op ->
        if (index > 0) append(" ")
        when (op) {
            is WordDiff.Op.Equal -> append(op.word)
            is WordDiff.Op.Deleted ->
                withStyle(SpanStyle(color = OrtColors.accentAmberText, textDecoration = TextDecoration.LineThrough)) {
                    append(op.word)
                }

            is WordDiff.Op.Inserted ->
                withStyle(SpanStyle(background = OrtColors.highlightGreen)) { append(op.word) }
        }
    }
}

/** A minimal LCS-based word diff — pure, testable independent of Compose. */
internal object WordDiff {
    internal sealed interface Op {
        data class Equal(val word: String) : Op
        data class Deleted(val word: String) : Op
        data class Inserted(val word: String) : Op
    }

    internal fun diff(old: List<String>, new: List<String>): List<Op> {
        val m = old.size
        val n = new.size
        val table = Array(m + 1) { IntArray(n + 1) }
        for (i in m - 1 downTo 0) {
            for (j in n - 1 downTo 0) {
                table[i][j] = if (old[i] == new[j]) {
                    table[i + 1][j + 1] + 1
                } else {
                    maxOf(table[i + 1][j], table[i][j + 1])
                }
            }
        }
        val ops = mutableListOf<Op>()
        var i = 0
        var j = 0
        while (i < m && j < n) {
            when {
                old[i] == new[j] -> {
                    ops += Op.Equal(old[i])
                    i++
                    j++
                }

                table[i + 1][j] >= table[i][j + 1] -> {
                    ops += Op.Deleted(old[i])
                    i++
                }

                else -> {
                    ops += Op.Inserted(new[j])
                    j++
                }
            }
        }
        while (i < m) {
            ops += Op.Deleted(old[i])
            i++
        }
        while (j < n) {
            ops += Op.Inserted(new[j])
            j++
        }
        return ops
    }
}
