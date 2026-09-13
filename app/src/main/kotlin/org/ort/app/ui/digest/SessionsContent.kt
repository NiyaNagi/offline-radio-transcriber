package org.ort.app.ui.digest

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.LoadingState
import org.ort.app.ui.data.LogFilterSelection
import org.ort.app.ui.screens.LogContent
import org.ort.app.ui.theme.OrtSpacing

private sealed interface SessionsPage {
    data object List : SessionsPage
    data class Detail(val sessionId: String) : SessionsPage
    data class Digest(val sessionId: String) : SessionsPage
    data class DigestItem(val sessionId: String, val item: DigestItemViewState) : SessionsPage

    /**
     * [fromMillis]/[toMillis] (E2-G07, DG05): a prose card's own `Read the overs` seeds the
     * existing time-window filter (R-276's own precedent — `Frequency.dc.html`'s "The N overs"
     * stat does the same) — `null` (every caller before this existed) opens unfiltered, exactly as
     * before.
     *
     * IA-3 (information-architecture review, approved — WPNAV): [transmissionIds] is DG02's own
     * curated set (`Digest-Item.dc.html`'s "The N overs" for N greater than one — see
     * [LogReturn.DigestItem]'s own doc comment for why N of exactly one still drills straight into
     * the transmission instead), and [returnTo] is the one filter model's own "where back goes"
     * half, generalising this package's pre-existing "always back to `Detail`" — which was itself
     * a real instance of the same defect this fixes: `Digest`'s own `Full log`/`Read the overs`
     * back to `Detail` too, silently discarding the `Digest` the operator was actually reading.
     */
    data class Log(
        val sessionId: String,
        val label: String,
        val fromMillis: Long? = null,
        val toMillis: Long? = null,
        val transmissionIds: Set<String>? = null,
        val returnTo: LogReturn = LogReturn.Detail,
    ) : SessionsPage
}

/** IA-3: see [SessionsPage.Log.returnTo]'s own doc comment. */
private sealed interface LogReturn {
    data object Detail : LogReturn
    data object Digest : LogReturn

    /** DG02: a digest item naming more than one over — reopens the exact same item, not its own
     * list, the same as any other drill-in's own back restores its own state intact. */
    data class DigestItem(val item: DigestItemViewState) : LogReturn
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
            is SessionsPage.Log -> listOf(
                "log",
                page.sessionId,
                page.label,
                page.fromMillis?.toString() ?: "",
                page.toMillis?.toString() ?: "",
                // IA-3: [transmissionIds] joined the same way `digest_item`'s own field above
                // already is; [returnTo]'s own discriminator plus, only for `DigestItem`, the
                // identical flat field set `digest_item` above already encodes — never a second,
                // recursively-nested [SessionsPageSaver] call, so [PAGE_FIELD_SEPARATOR]'s single
                // split still finds every field at one flat depth.
                page.transmissionIds?.joinToString(",") ?: "",
                when (page.returnTo) {
                    LogReturn.Detail -> "detail"
                    LogReturn.Digest -> "digest"
                    is LogReturn.DigestItem -> "digest_item"
                },
                (page.returnTo as? LogReturn.DigestItem)?.item?.id ?: "",
                (page.returnTo as? LogReturn.DigestItem)?.item?.headline ?: "",
                (page.returnTo as? LogReturn.DigestItem)?.item?.subLine ?: "",
                (page.returnTo as? LogReturn.DigestItem)?.item?.reason ?: "",
                (page.returnTo as? LogReturn.DigestItem)?.item?.ambiguousTone?.toString() ?: "",
                (page.returnTo as? LogReturn.DigestItem)?.item?.transmissionIds?.joinToString(",") ?: "",
            ).joinToString(PAGE_FIELD_SEPARATOR)
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
            "log" -> SessionsPage.Log(
                sessionId = parts[1],
                label = parts[2],
                fromMillis = parts.getOrNull(3)?.takeIf { it.isNotEmpty() }?.toLongOrNull(),
                toMillis = parts.getOrNull(4)?.takeIf { it.isNotEmpty() }?.toLongOrNull(),
                transmissionIds = parts.getOrNull(5)?.takeIf { it.isNotEmpty() }?.split(",")?.toSet(),
                returnTo = when (parts.getOrNull(6)) {
                    "digest" -> LogReturn.Digest
                    "digest_item" -> LogReturn.DigestItem(
                        DigestItemViewState(
                            id = parts.getOrElse(7) { "" },
                            headline = parts.getOrElse(8) { "" },
                            subLine = parts.getOrElse(9) { "" },
                            reason = parts.getOrElse(10) { "" },
                            ambiguousTone = parts.getOrElse(11) { "false" }.toBoolean(),
                            transmissionIds = parts.getOrNull(12)?.takeIf { it.isNotEmpty() }?.split(",")
                                ?: emptyList(),
                        ),
                    )
                    else -> LogReturn.Detail
                },
            )
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
 *
 * [openDigest] (round, register R-840, WPE's own navigation seam — the tour had no way to reach
 * this package's `Digest` (DG01/DG05) at all, only ever the `Session` (DG04) detail above): `true`
 * seeds [page] on `SessionsPage.Digest(it)` instead of `SessionsPage.Detail(it)` when
 * [initialSessionId] is also set — the exact same destination that detail's own `Digest` button
 * (`onOpenDigest` below) opens by a real tap. `false` (the default, every existing caller) changes
 * nothing. Ignored when [initialSessionId] is `null`, the same "only meaningful alongside its own
 * id" relationship this package's `Log`'s seeded time window already has to its own session id.
 */
@Composable
public fun SessionsContent(
    context: Context,
    onDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenTransmission: (String) -> Unit = {},
    initialSessionId: String? = null,
    openDigest: Boolean = false,
) {
    var page by rememberSaveable(stateSaver = SessionsPageSaver) {
        mutableStateOf(
            initialSessionId?.let { id ->
                if (openDigest) SessionsPage.Digest(id) else SessionsPage.Detail(id)
            } ?: SessionsPage.List,
        )
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
            // IA-3: `returnTo = LogReturn.Digest` — back used to always land on `Detail`, silently
            // discarding the `Digest` the operator actually opened this from.
            onFullLog = { page = SessionsPage.Log(current.sessionId, "Digest", returnTo = LogReturn.Digest) },
            // E2-G07 (DG05): a prose card's own `Read the overs` — the Log filtered to that card's
            // own over time window.
            onReadOvers = { from, to ->
                page = SessionsPage.Log(current.sessionId, "Digest", from, to, returnTo = LogReturn.Digest)
            },
            modifier = modifier,
        )

        is SessionsPage.DigestItem -> DigestItemScreen(
            item = current.item,
            onBack = { page = SessionsPage.Digest(current.sessionId) },
            // IA-3 (DG02, register): a single over still drills straight into its own transmission
            // detail — this composable has no drill-in surface of its own to show that on, so the
            // id is handed up through [onOpenTransmission] exactly as before. More than one used to
            // discard every id but the first, with the other N-1 unreachable from this tap at all —
            // now it opens the Log, filtered to exactly this curated set, `returnTo` this same item
            // so back restores it with its own state intact rather than the plain `Digest` list.
            onOpenTheOvers = {
                val ids = current.item.transmissionIds
                if (ids.size <= 1) {
                    ids.firstOrNull()?.let(onOpenTransmission)
                } else {
                    page = SessionsPage.Log(
                        sessionId = current.sessionId,
                        label = "Digest",
                        transmissionIds = ids.toSet(),
                        returnTo = LogReturn.DigestItem(current.item),
                    )
                }
            },
            modifier = modifier,
        )

        is SessionsPage.Log -> LogPage(
            current = current,
            context = context,
            onOpenTransmission = onOpenTransmission,
            onBack = { page = it },
            modifier = modifier,
        )
    }
}

/** [SessionsContent]'s `is SessionsPage.Log` branch, split out purely to keep that composable
 * under detekt's `LongMethod` limit — IA-3's own generalised `returnTo` is what pushed it over. */
@Composable
private fun LogPage(
    current: SessionsPage.Log,
    context: Context,
    onOpenTransmission: (String) -> Unit,
    onBack: (SessionsPage) -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        DrillInHeader(
            parentLabel = current.label,
            onBack = {
                // IA-3: the one filter model's own "back restores the origin with its own state
                // intact" half — [LogReturn.Detail] is `Detail`'s pre-existing (and only) back
                // target, kept as the default so every caller that never named a `returnTo`
                // behaves exactly as before.
                onBack(
                    when (val returnTo = current.returnTo) {
                        LogReturn.Detail -> SessionsPage.Detail(current.sessionId)
                        LogReturn.Digest -> SessionsPage.Digest(current.sessionId)
                        is LogReturn.DigestItem -> SessionsPage.DigestItem(current.sessionId, returnTo.item)
                    },
                )
            },
        )
        // E2-G07: a prose card's own seeded window (both bounds present) becomes the Log's
        // existing time-window filter — `null` (the plain "Digest"/"Log" navigation, unchanged)
        // opens unfiltered. IA-3: DG02's own curated [SessionsPage.Log.transmissionIds] layers on
        // top the identical way — [LogFilterSelection]'s own fields, never a parallel filter.
        val seededFilter = if (current.fromMillis != null && current.toMillis != null) {
            LogFilterSelection(fromMillis = current.fromMillis, toMillis = current.toMillis)
        } else if (current.transmissionIds != null) {
            LogFilterSelection(transmissionIds = current.transmissionIds)
        } else {
            null
        }
        LogContent(
            context = context,
            sessionId = current.sessionId,
            onOpen = onOpenTransmission,
            initialFilter = seededFilter,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Register R-1022/R-1051 (halt, constitution I/IV): the shared [LoadingState] (`loading-state`
 * test tag) — before this fix this was a local, untagged "Loading…" `Text`, so
 * [org.ort.app.debug.tour.TourAccessibilityScroll.snapshot]'s structural readiness check (which
 * scans for [org.ort.app.ui.components.LOADING_STATE_TEST_TAG]) could not tell this screen's own
 * genuine mid-load frame apart from anything else, and the tour could capture it before the real
 * sessions list/detail landed. `list`/`detail` (both nullable, unchanged) already keep this
 * distinct from a real empty state — this only adds the structural marker so a caller can wait for
 * it.
 */
@Composable
private fun Loading(modifier: Modifier = Modifier) {
    LoadingState(message = "Loading…", modifier = modifier.padding(horizontal = OrtSpacing.lg))
}
