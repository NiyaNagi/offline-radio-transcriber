package org.ort.app.ui.digest

import android.content.Context
import androidx.compose.foundation.layout.Column
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
import org.ort.app.ui.theme.OrtSpacing

/**
 * R-092 (register, FR-DIG-1..6): the poll-and-render wrapper for [DigestScreen] — one session's
 * digest. [org.ort.app.ui.digest.SessionsContent] (the `EARLIER_NIGHTS` destination) is this
 * package's own real caller today, reached from a session's `Digest` action; this file exists as
 * its own named entry point per this package's row so any future caller (a `Now`-embedded "tonight
 * so far" digest, say) can reach the same real computation without duplicating
 * [DigestPolling.digest]'s query. Item drill-in ([DigestItemScreen]) is owned by the caller, the
 * same way [org.ort.app.ui.settings.SettingsContent] owns its own sub-screen navigation.
 */
@Composable
public fun DigestContent(
    context: Context,
    sessionId: String,
    onBack: () -> Unit,
    onOpenItem: (DigestItemViewState) -> Unit,
    onFullLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var state by remember(sessionId) { mutableStateOf<DigestViewState?>(null) }
    LaunchedEffect(sessionId) { state = DigestPolling.digest(context, sessionId) }

    val current = state
    if (current != null) {
        DigestScreen(
            state = current,
            onBack = onBack,
            onOpenItem = onOpenItem,
            onFullLog = onFullLog,
            modifier = modifier,
        )
    } else {
        Column(modifier = modifier.padding(OrtSpacing.lg)) {
            Text(text = "Loading…", modifier = Modifier.semantics { contentDescription = "Loading digest" })
        }
    }
}
