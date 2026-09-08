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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
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

/** R-133 (register, WP10 small round): [SessionsPage] is a `private sealed interface` — not
 * itself Bundle-safe (`DigestItem` carries a whole [DigestItemViewState]) — so [rememberSaveable]
 * needs an explicit [Saver] rather than the automatic one, to survive a configuration change the
 * same way this package's every other saveable state already does. Encodes to one delimiter-joined
 * `String` (always Bundle-safe via the default `autoSaver()` path, unlike a raw `List<String?>`,
 * which is not guaranteed to be) — [PAGE_FIELD_SEPARATOR] is a control character no real headline/
 * sub-line/reason prose in this app ever produces. */
private const val PAGE_FIELD_SEPARATOR = ""

private val SessionsPageSaver: Saver<SessionsPage, String> = Saver(
    save = { page ->
        when (page) {
            SessionsPage.List -> "list"
            is SessionsPage.Detail -> listOf("detail", page.sessionId).joinToString(PAGE_FIELD_SEPARATOR)
            is SessionsPage.Digest -> listOf("digest", page.sessionId).joinToString(PAGE_FIELD_SEPARATOR)
            is SessionsPage.DigestItem -> listOf(
                "digest_item",
                page.sessionId,
                page.item.id,
                page.item.headline,
                page.item.subLine,
                page.item.reason,
                page.item.ambiguousTone.toString(),
                page.item.transmissionIds.joinToString(","),
            ).joinToString(PAGE_FIELD_SEPARATOR)
            is SessionsPage.Log -> listOf("log", page.sessionId, page.label).joinToString(PAGE_FIELD_SEPARATOR)
        }
    },
    restore = { saved ->
        val parts = saved.split(PAGE_FIELD_SEPARATOR)
        when (parts[0]) {
            "detail" -> SessionsPage.Detail(parts[1])
            "digest" -> SessionsPage.Digest(parts[1])
            "digest_item" -> SessionsPage.DigestItem(
                sessionId = parts[1],
                item = DigestItemViewState(
                    id = parts[2],
                    headline = parts[3],
                    subLine = parts[4],
                    reason = parts[5],
                    ambiguousTone = parts[6].toBoolean(),
                    transmissionIds = if (parts[7].isEmpty()) emptyList() else parts[7].split(","),
                ),
            )
            "log" -> SessionsPage.Log(parts[1], parts[2])
            else -> SessionsPage.List
        }
    },
)

/**
 * R-092/R-107 (register): the stateful entry point `OrtNavHost` dispatches `EARLIER_NIGHTS` to.
 * `Sessions` → `Session` → (`Digest` → `Digest-Item`) → `Log` are all held as this package's own
 * local navigation state, the same pattern [org.ort.app.ui.settings.SettingsContent] and
 * [org.ort.app.ui.improve.ImproveContent] use for their own sub-screens — no change to
 * `OrtNavHost`'s drill-in plumbing was needed for any of it. `Log` embeds WP5's own [LogContent]
 * directly, filtered to the exact past session tapped (`LogContent`'s `sessionId` genuinely
 * filters, confirmed by reading `LogPolling.screenState` before relying on it) — composing another
 * package's public screen, not editing it.
 *
 * [onOpenTransmission] (round 3, WP3's host find): the embedded `Log`'s row taps and
 * `Digest-Item`'s "The N over(s)" action both hand a real transmission id up through this callback
 * so a host mounting this composable (`OrtNavHost`) can drill into `TransmissionDetailContent` —
 * this package has no drill-in surface of its own to render that detail on. Defaults to a no-op so
 * every existing caller (`OrtNavHost.kt`, not edited by this change) keeps compiling unchanged;
 * `Digest-Item` hands up the first id in [DigestItemViewState.transmissionIds] (there is no
 * filtered-to-a-set Log view to open for the general case of more than one).
 *
 * [initialSessionId] (round, register R-133): `Settings-Storage`'s "Next deletion" row `Review`
 * link needs to land directly on that session's own `Session` (DG04) detail — this composable had
 * no external seed for its internal [page] state at all before this. A fresh `rememberSaveable`
 * seeded once (matching [org.ort.app.ui.settings.SettingsContent.initialScreen]'s own "opens there
 * on launch" contract — a later change to this parameter after first composition has no effect);
 * `null` (the default, so every existing caller keeps compiling unchanged) opens the list, exactly
 * as before this parameter existed. `Back` from the seeded detail returns to the `Sessions` list,
 * the same as reaching that detail any other way — a host that needs "back to `Settings-Storage`"
 * instead wraps this composable with its own header/back at the call site, not something this
 * package's own internal navigation state can express.
 */
@Composable
public fun SessionsContent(
    context: Context,
    onDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenTransmission: (String) -> Unit = {},
    initialSessionId: String? = null,
) {
    var page by rememberSaveable(stateSaver = SessionsPageSaver) {
        mutableStateOf(initialSessionId?.let { SessionsPage.Detail(it) } ?: SessionsPage.List)
    }
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
            onOpenTheOvers = { current.item.transmissionIds.firstOrNull()?.let(onOpenTransmission) },
            modifier = modifier,
        )

        is SessionsPage.Log -> Column(modifier = modifier.fillMaxSize()) {
            DrillInHeader(parentLabel = current.label, onBack = { page = SessionsPage.Detail(current.sessionId) })
            LogContent(
                context = context,
                sessionId = current.sessionId,
                onOpen = onOpenTransmission,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun Loading(modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(OrtSpacing.lg)) {
        Text(text = "Loading…", modifier = Modifier.semantics { contentDescription = "Loading sessions" })
    }
}
