package org.ort.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import org.ort.app.ui.data.ModelActionResult
import org.ort.app.ui.data.ModelId
import org.ort.app.ui.data.ModelsController
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
    var pendingSideloadId by remember { mutableStateOf<ModelId?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = pendingSideloadId
        pendingSideloadId = null
        if (uri == null || id == null) return@rememberLauncherForActivityResult
        busy = busy + id
        scope.launch {
            val source = copyPickedFileToCache(context, uri, id)
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

    ModelsScreen(
        state = state,
        busy = busy,
        lastMessage = lastMessage,
        onDownload = { id ->
            busy = busy + id
            scope.launch {
                val result = ModelsController.download(context, id)
                busy = busy - id
                lastMessage = messageFor(id, result)
                state = ModelsController.currentState(context)
            }
        },
        onSideload = { id ->
            pendingSideloadId = id
            filePicker.launch(arrayOf("*/*"))
        },
        modifier = modifier,
        onBack = onBack,
    )
}

private fun messageFor(id: ModelId, result: ModelActionResult): String = when (result) {
    is ModelActionResult.Success ->
        "${id.label}: installed, checksum verified. Requeued ${result.requeuedCount} previously failed transmission(s)."
    is ModelActionResult.Failure -> "${id.label}: ${result.reason}"
}

/**
 * [ModelAcquisition][org.ort.net.ModelAcquisition].sideload takes a [java.io.File], not a content
 * [android.net.Uri] — the system picker only ever hands back the latter, so this copies the picked
 * document into app-private cache storage first. No network call either way (constitution V).
 */
private fun copyPickedFileToCache(context: android.content.Context, uri: android.net.Uri, id: ModelId): java.io.File? =
    try {
        val dest = java.io.File(context.cacheDir, "sideload-${id.name}.tmp")
        val opened = context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
            true
        }
        if (opened == true) dest else null
    } catch (e: IOException) {
        null
    }
