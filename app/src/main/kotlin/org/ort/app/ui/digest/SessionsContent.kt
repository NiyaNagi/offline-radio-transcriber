package org.ort.app.ui.digest

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.screens.LogContent
import org.ort.app.ui.theme.OrtSpacing

private sealed interface SessionsPage {
    data object List : SessionsPage
    data class Detail(val sessionId: String) : SessionsPage
    data class Digest(val sessionId: String) : SessionsPage
    data class DigestItem(val sessionId: String, val item: DigestItemViewState) : SessionsPage
    data class Log(val sessionId: String, val label: String) : SessionsPage
}

/**
 * R-092/R-107 (register): the stateful entry point `OrtNavHost` dispatches `EARLIER_NIGHTS` to.
 * `Sessions` → `Session` → (`Digest` → `Digest-Item`) → `Log` are all held as this package's own
 * local navigation state, the same pattern [org.ort.app.ui.settings.SettingsContent] and
 * [org.ort.app.ui.improve.ImproveContent] use for their own sub-screens — no change to
 * `OrtNavHost`'s drill-in plumbing was needed for any of it. `Log` embeds WP5's own [LogContent]
 * directly, filtered to the exact past session tapped (`LogContent`'s `sessionId` genuinely
 * filters, confirmed by reading `LogPolling.screenState` before relying on it) — composing another
 * package's public screen, not editing it.
 */
@Composable
public fun SessionsContent(context: Context, onDrawer: () -> Unit, modifier: Modifier = Modifier) {
    var page by remember { mutableStateOf<SessionsPage>(SessionsPage.List) }
    var list by remember { mutableStateOf<SessionsViewState?>(null) }
    LaunchedEffect(page) { if (page is SessionsPage.List) list = DigestPolling.sessions(context) }

    when (val current = page) {
        SessionsPage.List -> {
            val state = list
            if (state != null) {
                SessionsScreen(
                    state = state,
                    onDrawer = onDrawer,
                    onOpen = { id -> page = SessionsPage.Detail(id) },
                    modifier = modifier,
                )
            } else {
                Loading(modifier)
            }
        }

        is SessionsPage.Detail -> {
            var detail by remember(current.sessionId) { mutableStateOf<SessionDetailViewState?>(null) }
            LaunchedEffect(current.sessionId) { detail = DigestPolling.sessionDetail(context, current.sessionId) }
            val state = detail
            if (state != null) {
                SessionDetailScreen(
                    state = state,
                    onBack = { page = SessionsPage.List },
                    onOpenLog = { page = SessionsPage.Log(current.sessionId, state.label) },
                    onOpenDigest = { page = SessionsPage.Digest(current.sessionId) },
                    modifier = modifier,
                )
            } else {
                Loading(modifier)
            }
        }

        is SessionsPage.Digest -> DigestContent(
            context = context,
            sessionId = current.sessionId,
            onBack = { page = SessionsPage.Detail(current.sessionId) },
            onOpenItem = { item -> page = SessionsPage.DigestItem(current.sessionId, item) },
            onFullLog = { page = SessionsPage.Log(current.sessionId, "Digest") },
            modifier = modifier,
        )

        is SessionsPage.DigestItem -> DigestItemScreen(
            item = current.item,
            onBack = { page = SessionsPage.Digest(current.sessionId) },
            onOpenTheOvers = { page = SessionsPage.Log(current.sessionId, current.item.headline) },
            modifier = modifier,
        )

        is SessionsPage.Log -> Column(modifier = modifier.fillMaxSize()) {
            DrillInHeader(parentLabel = current.label, onBack = { page = SessionsPage.Detail(current.sessionId) })
            LogContent(context = context, sessionId = current.sessionId, onOpen = {}, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun Loading(modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(OrtSpacing.lg)) {
        Text(text = "Loading…", modifier = Modifier.semantics { contentDescription = "Loading sessions" })
    }
}
