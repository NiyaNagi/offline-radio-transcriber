package org.ort.app.ui.failures

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.Banner
import org.ort.app.ui.components.BannerTone
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType

/**
 * WP11b (register R-100): F2/F3/F5/F6-warning/F7/F8/F9's banners — the amber `Banner`
 * (guide §6.8/`Feedback.dc.html`, built by WP2) wrapping each F-id's own copy from its board,
 * never an enum name or a `failureReason` string surfaced raw. Only the recovery actions the
 * runtime actually supports today are wired; the rest are documented no-op stubs — see each
 * composable's own kdoc for exactly which and why (this package's report says the same).
 */

// -------------------------------------------------------------------------------------------
// F2 — Fail-Disconnect.
// -------------------------------------------------------------------------------------------

@Composable
public fun FailDisconnectBanner(
    state: DisconnectViewState,
    onRetry: () -> Unit,
    onChooseAnotherInput: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Banner(
        title = "Input disconnected — retrying",
        body = "${state.deviceLabel} went away at ${state.sinceLabel}. The session is still open. " +
            "A gap is being recorded until it is back.",
        tone = BannerTone.DEGRADED,
        primaryActionLabel = "Retry now",
        onPrimaryAction = onRetry,
        secondaryActionLabel = "Choose another input",
        onSecondaryAction = onChooseAnotherInput,
        modifier = modifier.testTag("failure-disconnect-banner"),
    )
}

// -------------------------------------------------------------------------------------------
// F3 — Fail-Level. No action row on the board itself.
// -------------------------------------------------------------------------------------------

@Composable
public fun FailLevelBanner(state: LevelViewState, modifier: Modifier = Modifier) {
    val (title, body) = when (state.problem) {
        LevelProblem.QUIET -> "Too quiet since ${state.sinceLabel} — speech peaks at ${dbfsLabel(state.peakDbfs)}" to
            "The radio's volume dropped, or the squelch is opening on weaker signals. Weak stations will be " +
            "missed and the model will hear less. Turn the radio up until the peaks sit in the green band."
        LevelProblem.CLIPPING -> "Clipping since ${state.sinceLabel} — peaks hit ${dbfsLabel(state.peakDbfs)}" to
            "The input is too hot; loud signals are being cut off before the model ever hears them. Turn the " +
            "radio down until peaks sit under 0 dBFS."
    }
    Banner(title = title, body = body, tone = BannerTone.DEGRADED, modifier = modifier.testTag("failure-level-banner"))
}

private fun dbfsLabel(dbfs: Float): String {
    val rounded = dbfs.toInt()
    return "$rounded dBFS"
}

// -------------------------------------------------------------------------------------------
// F5 — Fail-Killed. onOpenBatterySettings is real (a platform Settings intent, wired by
// ReaderActivity); the banner is dismissable (guide's "dismissable only where the board says so"
// — F5 reports a past, already-resumed event, not an ongoing condition).
// -------------------------------------------------------------------------------------------

@Composable
public fun FailKilledBanner(
    state: KilledViewState,
    onOpenBatterySettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Banner(
        title = "The phone stopped the app at ${state.stoppedAtLabel}",
        body = "The heartbeat stopped at ${state.stoppedAtLabel} and there was no shutdown of ours. Nothing " +
            "for ${state.gapDurationLabel} was captured. The session is reopened, not replaced — tonight's " +
            "log is one log with a gap in it.",
        tone = BannerTone.DEGRADED,
        primaryActionLabel = "Open the setting",
        onPrimaryAction = onOpenBatterySettings,
        secondaryActionLabel = "OK",
        onSecondaryAction = onDismiss,
        modifier = modifier.testTag("failure-killed-banner"),
    )
}

// -------------------------------------------------------------------------------------------
// F6 (warning stage) — Fail-Storage.
// -------------------------------------------------------------------------------------------

@Composable
public fun FailStorageWarningBanner(
    state: StorageWarningViewState,
    onFreeUpSpace: () -> Unit,
    onOpenRetentionSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nightWord = if (state.nightsLeftLabel == "1") "night" else "nights"
    Banner(
        title = "Storage getting low — warned at ${state.nightsLeftLabel} $nightWord left",
        body = "${state.freeLabel} at the current write rate. Audio pauses first, then transcripts, before " +
            "capture ever stops — and capture only stops loudly.",
        tone = BannerTone.DEGRADED,
        primaryActionLabel = "Free up space",
        onPrimaryAction = onFreeUpSpace,
        secondaryActionLabel = "Retention",
        onSecondaryAction = onOpenRetentionSettings,
        modifier = modifier.testTag("failure-storage-warning-banner"),
    )
}

// -------------------------------------------------------------------------------------------
// F6 (audio-paused stage) — Fail-Storage.dc.html's own title narrative. No runtime signal today
// (see FailureViewState.kt's StorageAudioPausedViewState kdoc) — built against the shape the
// signal would need, reachable only through DebugFailureOverride until :pipeline adds it.
// -------------------------------------------------------------------------------------------

@Composable
public fun FailStorageAudioPausedBanner(
    state: StorageAudioPausedViewState,
    onFreeUpSpace: () -> Unit,
    onOpenRetentionSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Banner(
        title = "Storage is at the floor — ${state.freeLabel}",
        body = "Audio is no longer being written. Every over since ${state.pausedAtLabel} " +
            "(${state.oversSinceCount} so far) has a transcript and an attribution but no recording, and is " +
            "marked so. Capture itself has not stopped and will not stop silently.",
        tone = BannerTone.DEGRADED,
        primaryActionLabel = "Free up space",
        onPrimaryAction = onFreeUpSpace,
        secondaryActionLabel = "Retention",
        onSecondaryAction = onOpenRetentionSettings,
        modifier = modifier.testTag("failure-storage-audio-paused-banner"),
    )
}

// -------------------------------------------------------------------------------------------
// F7 — Fail-Thermal. "What tier N does not do" is a local expand/collapse (guide §6.7: a
// tappable control must be a real affordance — there is no owned destination to navigate to, so
// this stays self-contained rather than a fabricated nav edge; see this package's report).
// -------------------------------------------------------------------------------------------

@Composable
public fun FailThermalBanner(state: ThermalViewState, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Banner(
        title = "Running warm — dropped to tier ${state.tier} at ${state.sinceLabel}",
        body = "Thermal status is elevated and processing has slowed" +
            (state.realTimeFactor?.let { " (RTF ${"%.2f".format(it)})" } ?: "") +
            ". Pass C is off until it cools; ambiguous overs stay ambiguous rather than resolve. Everything " +
            "captured is kept and marked improvable.",
        tone = BannerTone.DEGRADED,
        primaryActionLabel = "What tier ${state.tier} does not do",
        onPrimaryAction = { expanded = !expanded },
        modifier = modifier.testTag("failure-thermal-banner"),
    )
    if (expanded) {
        Text(
            text = "Tier ${state.tier} does not run Pass C — ambiguous overs stay ambiguous instead of " +
                "resolving from audio-level evidence. Everything is still captured, transcribed and kept; " +
                "marked overs can be reprocessed once the tier is back.",
            style = OrtType.cardBody,
            color = OrtColors.textMuted,
            modifier = Modifier.fillMaxWidth().testTag("failure-thermal-detail"),
        )
    }
}

// -------------------------------------------------------------------------------------------
// F8 — Fail-Backlog. No action row on the board.
// -------------------------------------------------------------------------------------------

@Composable
public fun FailBacklogBanner(state: BacklogViewState, modifier: Modifier = Modifier) {
    Banner(
        title = "${state.waitingCount} overs deferred — the queue is growing",
        body = "Capture takes priority; Pass B runs on what it can. Deferred overs keep a live partial and " +
            "their audio, and get a final transcript once the band quiets.",
        tone = BannerTone.DEGRADED,
        modifier = modifier.testTag("failure-backlog-banner"),
    )
}

// -------------------------------------------------------------------------------------------
// F9 — Fail-Rig.
// -------------------------------------------------------------------------------------------

@Composable
public fun FailRigBanner(
    state: RigViewState,
    onReconnect: () -> Unit,
    onSetFrequencyByHand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Banner(
        title = "Radio disconnected at ${state.sinceLabel} — frequency is stale",
        body = "Capture continues. Overs since then are logged against the last frequency the rig reported, " +
            "and marked so. If you changed channel, they are wrong until the rig is back.",
        tone = BannerTone.DEGRADED,
        primaryActionLabel = "Reconnect",
        onPrimaryAction = onReconnect,
        secondaryActionLabel = "Set the frequency by hand",
        onSecondaryAction = onSetFrequencyByHand,
        modifier = modifier.testTag("failure-rig-banner"),
    )
}

// -------------------------------------------------------------------------------------------
// F15 — Fail-Call. `Feedback.dc.html`'s own shape is closest to a compact acknowledgement row,
// not the halting/degraded Banner (its background is `bg/selected`, not an amber card) — built
// locally rather than repurposing WP2's Toast, whose action slot is hardcoded "Undo" (guide
// §Feedback: Toast is for a *propagating* action; this is an acknowledgement, not an undo).
// -------------------------------------------------------------------------------------------

@Composable
public fun FailCallBanner(state: CallViewState, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(OrtColors.bgSelected, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp)
            .testTag("failure-call-banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Text(
            text = "Resumed after a ${state.durationLabel} call. The gap is in the log.",
            style = OrtType.subtitle,
            color = OrtColors.textHigh,
            modifier = Modifier.weight(1f),
        )
        TextAction(text = "OK", onClick = onDismiss, modifier = Modifier.testTag("failure-call-dismiss"))
    }
}
