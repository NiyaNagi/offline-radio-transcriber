package org.ort.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import org.ort.app.ui.screens.ModelsExtrasViewState
import org.ort.app.ui.screens.ModelsScreen
import org.ort.app.ui.screens.ProseDigestSectionViewState
import org.ort.data.OrtDatabase
import org.ort.pipeline.capture.measureStorageAccounting
import org.ort.pipeline.digest.ProseDigestRunner
import org.ort.pipeline.digest.SharedPreferencesProseDigestSettingsStore
import java.io.File
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
 *
 * The real work each tap/pick starts is in [onLexiconFilePicked]/[onModelFilePicked]/[runDownload]
 * (private, top-level, below) — split out purely to keep this function itself under detekt's length
 * limit; each takes this composable's own mutable state back only as narrow setter lambdas.
 */
@Composable
public fun ModelsContent(context: android.content.Context, modifier: Modifier, onBack: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(ModelsController.currentState(context)) }
    var busy by remember { mutableStateOf(emptySet<ModelId>()) }
    var lastMessage by remember { mutableStateOf<String?>(null) }
    var lastDownloadFailure by remember { mutableStateOf<ModelDownloadFailureViewState?>(null) }
    var pendingSideloadId by remember { mutableStateOf<ModelId?>(null) }
    var lexiconRow by remember { mutableStateOf<LexiconAssetRowViewState?>(null) }
    var lexiconImportResult by remember { mutableStateOf<LexiconImportViewState?>(null) }
    var lexiconRefreshToken by remember { mutableStateOf(0) }

    LaunchedEffect(lexiconRefreshToken) { lexiconRow = ModelsController.lexiconRow(context) }
    // R-154 (round 5): see `DebugLexiconImportOverride`'s own doc comment for why this also polls it.
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
    // FR-AST-4: opening this screen is the concrete, in-ownership trigger this round implements
    // for "activate a staged swap once its session has ended" — see [activateStagedOnOpen]'s own
    // doc comment.
    LaunchedEffect(Unit) {
        activateStagedOnOpen(context) {
            state = ModelsController.currentState(context)
            lexiconRefreshToken++
        }
    }
    val stagedActivation by ModelsController.stagedActivation.collectAsState()

    val extras = rememberModelsExtras(context)

    val lexiconFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        scope.launch {
            onLexiconFilePicked(context, uri) { result ->
                lexiconImportResult = result
                lexiconRefreshToken++
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = pendingSideloadId
        pendingSideloadId = null
        if (id == null) return@rememberLauncherForActivityResult
        scope.launch {
            onModelFilePicked(context, id, uri, onBusy = { b -> busy = if (b) busy + id else busy - id }) { result ->
                lastMessage = messageFor(id, result)
                state = ModelsController.currentState(context)
            }
        }
    }

    fun startDownload(id: ModelId) {
        scope.launch {
            runDownload(
                context,
                id,
                onBusy = { b -> busy = if (b) busy + id else busy - id },
                onSuccess = { message ->
                    lastDownloadFailure = null
                    lastMessage = message
                },
                onFailure = { failure ->
                    lastMessage = null
                    lastDownloadFailure = failure
                },
                onStateRefresh = { state = ModelsController.currentState(context) },
            )
        }
    }

    ModelsScreen(
        state = state,
        status = ModelsScreenStatus(
            lastMessage = lastMessage,
            downloadFailure = lastDownloadFailure,
            stagedActivation = stagedActivation,
        ),
        onDownload = ::startDownload,
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
        extras = extras.copy(busy = busy),
    )
}

/**
 * CF04's Prose-digest toggle and Space row, split out of [ModelsContent] purely to keep that
 * function under detekt's length limit (the same reason [onLexiconFilePicked]/[onModelFilePicked]/
 * [runDownload] already are). See [ModelsContent]'s own doc comment for why the toggle writes
 * straight to [SharedPreferencesProseDigestSettingsStore] rather than through
 * [org.ort.pipeline.digest.ProseDigestSettings.setEnabled].
 */
@Composable
private fun rememberModelsExtras(context: android.content.Context): ModelsExtrasViewState {
    val proseDigestStore = remember(context) { SharedPreferencesProseDigestSettingsStore(context) }
    var proseDigestEnabled by remember { mutableStateOf(proseDigestStore.isEnabled()) }
    var bundledBytesLabel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val dbFile = context.getDatabasePath(OrtDatabase.DATABASE_NAME)
        val databaseFiles = listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm"))
        val accounting = measureStorageAccounting(context.filesDir, databaseFiles)
        bundledBytesLabel = formatBundledBytes(accounting.bundledBytes)
    }
    return ModelsExtrasViewState(
        proseDigest = ProseDigestSectionViewState(
            enabled = proseDigestEnabled,
            onToggle = { enabled ->
                proseDigestStore.setEnabled(enabled)
                proseDigestEnabled = enabled
                if (enabled) ProseDigestRunner.schedule(context) else ProseDigestRunner.cancel(context)
            },
        ),
        spaceUsedBytesLabel = bundledBytesLabel,
    )
}

/** CF04's own Space-row precision (`Settings-Assets.dc.html`'s own "635 MB" example) — a plain
 * `toGigabyteLabel()` would round today's real bundled figure (tens to hundreds of MB) to `0.0 GB`. */
private fun formatBundledBytes(bytes: Long): String {
    val mb = bytes / 1_000_000.0
    return if (mb >= 1000.0) "%.2f GB".format(mb / 1000.0) else "%.0f MB".format(mb)
}

private const val LEXICON_CACHE_FILE_NAME = "lexicon-sideload"
private const val LEXICON_OVERRIDE_POLL_INTERVAL_MILLIS = 1_000L

/**
 * FR-AST-4 (register, WP11b's F21 audit finding): [ModelsController.activateStaged] is itself a
 * safe no-op whenever a session is still live, so calling it every time this screen opens is never
 * wrong — this is the concrete, in-ownership trigger this round implements for "activate a staged
 * swap once the session that staged it has ended". It does not replace a real capture-lifecycle
 * hook (an `Activity` launch, `ReaderActivity`'s own start path) — those live outside this
 * package's own ownership; see this round's own report for that gap. [onActivated] refreshes this
 * screen's own state only when something really did activate, never unconditionally.
 */
private suspend fun activateStagedOnOpen(context: android.content.Context, onActivated: () -> Unit) {
    if (ModelsController.activateStaged(context) != null) onActivated()
}

/** [ModelsContent]'s "Install a lexicon from a file" picker callback, split out purely to keep
 * that composable itself under detekt's length limit — R-154's own real validate-then-activate
 * call site ([ModelsController.installLexicon]) is unchanged, only moved. */
private suspend fun onLexiconFilePicked(
    context: android.content.Context,
    uri: android.net.Uri?,
    onResult: (LexiconImportViewState) -> Unit,
) {
    if (uri == null) return
    val source = copyPickedFileToCache(context, uri, LEXICON_CACHE_FILE_NAME)
    val result = if (source != null) {
        ModelsController.installLexicon(context, source)
    } else {
        LexiconImportViewState.Rejected(
            fileName = "picked file",
            checks = emptyList(),
            reason = "could not read the picked file",
            stillActiveLabel = null,
        )
    }
    onResult(result)
}

/** [ModelsContent]'s "Install from a file" (sideload) picker callback, split out purely to keep
 * that composable itself under detekt's length limit — [ModelsController.sideload] is unchanged,
 * only moved. */
private suspend fun onModelFilePicked(
    context: android.content.Context,
    id: ModelId,
    uri: android.net.Uri?,
    onBusy: (Boolean) -> Unit,
    onResult: (ModelActionResult) -> Unit,
) {
    if (uri == null) return
    onBusy(true)
    val source = copyPickedFileToCache(context, uri, id.name)
    val result = if (source != null) {
        ModelsController.sideload(context, id, source)
    } else {
        ModelActionResult.Failure("could not read the picked file")
    }
    onBusy(false)
    onResult(result)
}

/** [ModelsContent]'s "Download" action, split out purely to keep that composable itself under
 * detekt's length limit — [ModelsController.download] is unchanged, only moved. */
private suspend fun runDownload(
    context: android.content.Context,
    id: ModelId,
    onBusy: (Boolean) -> Unit,
    onSuccess: (String) -> Unit,
    onFailure: (ModelDownloadFailureViewState) -> Unit,
    onStateRefresh: () -> Unit,
) {
    onBusy(true)
    val result = ModelsController.download(context, id)
    onBusy(false)
    when (result) {
        is ModelActionResult.Success -> onSuccess(messageFor(id, result))
        is ModelActionResult.Failure -> onFailure(ModelDownloadFailureViewState(id, result.reason))
    }
    onStateRefresh()
}

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
