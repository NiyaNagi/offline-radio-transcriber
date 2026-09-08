package org.ort.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ort.app.ui.data.DebugLexiconImportOverride
import org.ort.app.ui.data.LexiconAssetActions
import org.ort.app.ui.data.LexiconAssetRowViewState
import org.ort.app.ui.data.LexiconImportViewState
import org.ort.app.ui.data.ModelActionResult
import org.ort.app.ui.data.ModelDownloadFailureViewState
import org.ort.app.ui.data.ModelId
import org.ort.app.ui.data.ModelsController
import org.ort.app.ui.data.ModelsScreenStatus
import org.ort.app.ui.screens.ModelsScreen
import java.io.IOException

/**
 * ui-conformance-plan WP3 · move only: lifted out of `ui/navigation/OrtNavHost.kt` unchanged (F-008's
 * "Models" destination — the drawer's "Settings" row) so `OrtNavHost` dispatches `SETTINGS` to this
 * entry point rather than owning it inline. Everything under `ui/settings/` is WP10's from here on;
 * this file is WP3's only carve-out into that package, and it moves this one entry point in, nothing
 * else.
 *
 * Owns its own busy/last-message state — [ModelsController] itself is stateless — and drives
 * [ModelsController.download]/[ModelsController.sideload] from a tap, off the main dispatcher (both
 * already hop to [kotlinx.coroutines.Dispatchers.IO] internally), refreshing
 * [ModelsController.currentState] after either finishes so the row's installed/not-installed fact
 * always reflects what `ModelAcquisition` itself verified, not an optimistic guess.
 */
@Composable
public fun ModelsContent(context: android.content.Context, modifier: Modifier, onBack: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(ModelsController.currentState(context)) }
    var busy by remember { mutableStateOf(emptySet<ModelId>()) }
    var lastMessage by remember { mutableStateOf<String?>(null) }
    // R-140 (round 4, System validator): a failed download used to fall into the same plain-text
    // `lastMessage` a success did, showing `:net`'s raw exception text ("Unable to resolve
    // host…") with no retry — split out so `ModelsScreen` can render it as the amber `FailedState`
    // guide §9 gives every other operator-facing failure, with a real `Retry` that re-runs the
    // exact same download.
    var lastDownloadFailure by remember { mutableStateOf<ModelDownloadFailureViewState?>(null) }
    var pendingSideloadId by remember { mutableStateOf<ModelId?>(null) }

    // R-154 (round 5): the lexicon row's own state — real (`ModelsController.lexiconRow`) and,
    // once "Install a lexicon from a file" runs, the real [LexiconImportViewState] this screen
    // renders as `Fail-Lexicon.dc.html` on a [LexiconImportViewState.Rejected]. See
    // `DebugLexiconImportOverride`'s own doc comment for why the poll below also reads it.
    var lexiconRow by remember { mutableStateOf<LexiconAssetRowViewState?>(null) }
    var lexiconImportResult by remember { mutableStateOf<LexiconImportViewState?>(null) }
    var lexiconRefreshToken by remember { mutableStateOf(0) }

    LaunchedEffect(lexiconRefreshToken) { lexiconRow = ModelsController.lexiconRow(context) }
    LaunchedEffect(Unit) {
        while (true) {
            if (lexiconImportResult == null) {
                DebugLexiconImportOverride.activeOverride?.let {
                    lexiconImportResult = it
                    lexiconRefreshToken++
                }
            }
            delay(LEXICON_OVERRIDE_POLL_INTERVAL_MILLIS)
        }
    }

    val lexiconFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val source = copyPickedFileToCache(context, uri, LEXICON_CACHE_FILE_NAME)
            lexiconImportResult = if (source != null) {
                ModelsController.installLexicon(context, source)
            } else {
                LexiconImportViewState.Rejected(
                    fileName = "picked file",
                    checks = emptyList(),
                    reason = "could not read the picked file",
                    stillActiveLabel = null,
                )
            }
            lexiconRefreshToken++
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = pendingSideloadId
        pendingSideloadId = null
        if (uri == null || id == null) return@rememberLauncherForActivityResult
        busy = busy + id
        scope.launch {
            val source = copyPickedFileToCache(context, uri, id.name)
            val result = if (source != null) {
                ModelsController.sideload(context, id, source)
            } else {
                ModelActionResult.Failure("could not read the picked file")
            }
            busy = busy - id
            lastMessage = messageFor(id, result)
            state = ModelsController.currentState(context)
        }
    }

    fun runDownload(id: ModelId) {
        busy = busy + id
        scope.launch {
            val result = ModelsController.download(context, id)
            busy = busy - id
            when (result) {
                is ModelActionResult.Success -> {
                    lastDownloadFailure = null
                    lastMessage = messageFor(id, result)
                }
                is ModelActionResult.Failure -> {
                    lastMessage = null
                    lastDownloadFailure = ModelDownloadFailureViewState(id, result.reason)
                }
            }
            state = ModelsController.currentState(context)
        }
    }

    ModelsScreen(
        state = state,
        busy = busy,
        status = ModelsScreenStatus(lastMessage = lastMessage, downloadFailure = lastDownloadFailure),
        onDownload = ::runDownload,
        onSideload = { id ->
            pendingSideloadId = id
            filePicker.launch(arrayOf("*/*"))
        },
        modifier = modifier,
        onBack = onBack,
        lexicon = LexiconAssetActions(
            row = lexiconRow,
            importResult = lexiconImportResult,
            onInstall = { lexiconFilePicker.launch(arrayOf("*/*")) },
            onDismissResult = { lexiconImportResult = null },
        ),
    )
}

private const val LEXICON_CACHE_FILE_NAME = "lexicon-sideload"
private const val LEXICON_OVERRIDE_POLL_INTERVAL_MILLIS = 1_000L

private fun messageFor(id: ModelId, result: ModelActionResult): String = when (result) {
    is ModelActionResult.Success ->
        "${id.label}: installed, checksum verified. Requeued ${result.requeuedCount} previously failed transmission(s)."
    is ModelActionResult.Failure -> "${id.label}: ${result.reason}"
}

/**
 * [ModelAcquisition][org.ort.net.ModelAcquisition].sideload and [ModelsController.installLexicon]
 * both take a [java.io.File], not a content [android.net.Uri] — the system picker only ever hands
 * back the latter, so this copies the picked document into app-private cache storage first. No
 * network call either way (constitution V). [name] is a cache-file-naming key only (a [ModelId]'s
 * own name for a model sideload, [LEXICON_CACHE_FILE_NAME] for a lexicon import — the lexicon has
 * no [ModelId] of its own).
 */
private fun copyPickedFileToCache(context: android.content.Context, uri: android.net.Uri, name: String): java.io.File? =
    try {
        val dest = java.io.File(context.cacheDir, "sideload-$name.tmp")
        val opened = context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
            true
        }
        if (opened == true) dest else null
    } catch (e: IOException) {
        null
    }
