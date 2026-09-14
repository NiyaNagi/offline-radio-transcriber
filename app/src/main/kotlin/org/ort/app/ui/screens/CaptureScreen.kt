package org.ort.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import org.ort.app.ui.components.LoadingState
import org.ort.app.ui.components.ProgressBar
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.data.CaptureArchiveViewState
import org.ort.app.ui.data.CaptureOverAudioViewState
import org.ort.app.ui.data.CaptureStatusViewState
import org.ort.app.ui.data.CaptureStorageViewState
import org.ort.app.ui.data.KeyValueFacts
import org.ort.app.ui.data.LevelViewState
import org.ort.app.ui.data.LiveMonitorOversViewState
import org.ort.app.ui.navigation.toGigabyteLabel
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * `Capture.dc.html` (design-intent N08, R-1023/R-1026/R-1027/R-1030): **Capture as one instrument**
 * — state and elapsed, the level envelope, the Pass A partial, the four status facts (input, radio,
 * queue, storage) and this session's overs in flight, on one scrolling surface. Supersedes
 * [CaptureStatusScreen] (N04), [LevelMeterScreen] (N06) and [LiveMonitorScreen] (N07) as *separate*
 * screens — those boards stay, marked superseded in `design/design-intent.md`, nothing deleted
 * quietly (P9); their own composables and tests are untouched, simply no longer reachable as
 * distinct destinations from [CaptureStatusContent] once this screen lands.
 *
 * Composes existing pieces rather than re-implementing them: [LiveMonitorLevelCard],
 * [LiveMonitorHearingCard] and [LiveMonitorOversSection] (promoted `internal` in
 * `LiveMonitorScreen.kt` for exactly this reuse) render the level envelope, the "Hearing now" card
 * and this session's overs identically to how N07 already did; [KeyValueRowWithDot]/[StateDot]
 * (promoted `internal` in `CaptureStatusScreen.kt`) render Input/Radio/Queue the same way N04
 * already did. The only genuinely new rendering here is [CaptureStorageSection] — D40/FR-STO-3e's
 * persistent over-audio warning (AC-156/157/160) beside D39/FR-STO-3f's continuous-archive
 * default-on state and monthly rate (AC-158/159), both **visible without a tap**, on this one
 * surface (FR-UI-7).
 *
 * **No [org.ort.app.ui.components.LiveBar] of its own** — the artboard's own note: this screen is
 * what the transport bar/live bar expands to (IA-2, C10), so drawing one here would be showing the
 * bar its own destination. Matches [LiveMonitorScreen]'s identical precedent (that screen's own
 * kdoc has the same reasoning).
 *
 * **One documented deviation from the artboard's literal drawing (constitution VIII):** the board
 * draws Storage as a single collapsed `.fact` row with a chevron into settings; AC-159 requires the
 * archive's on/off state and rate to sit **beside the control that changes it**, not behind a
 * second tap — so [CaptureStorageSection] renders as an expanded block (mirroring
 * `RecordingsScreen.kt`'s own `RecordingsBudgetsCard` shape, the sibling surface that already
 * carries this identical pair of facts) rather than the board's single line. The facts themselves
 * — archive usage against its budget, over-audio usage and its warning — are exactly what the board
 * names.
 */
@Composable
@Suppress("LongParameterList") // the merged N08 surface's own inputs — see this file's class kdoc.
public fun CaptureScreen(
    status: CaptureStatusViewState,
    level: LevelViewState,
    hearingText: String?,
    overs: LiveMonitorOversViewState,
    storage: CaptureStorageViewState,
    modifier: Modifier = Modifier,
    onStop: () -> Unit = {},
    onOpenOver: (String) -> Unit = {},
    onTurnOffArchive: () -> Unit = {},
) {
    var confirmingStop by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.lg)
                .testTag(CAPTURE_SCROLL_TEST_TAG),
        ) {
            if (status.loading) {
                LoadingState(message = "Loading capture status…")
            } else {
                CaptureTitleRow(status = status, onStopRequested = { confirmingStop = true })
                LiveMonitorLevelCard(level = level, modifier = Modifier.padding(top = OrtSpacing.lg))
                if (hearingText != null) {
                    LiveMonitorHearingCard(text = hearingText, modifier = Modifier.padding(top = OrtSpacing.lg))
                }

                SectionHeader(label = "Status", modifier = Modifier.padding(top = OrtSpacing.lg))
                KeyValueRowWithDot("Input", status.input, "capture-input")
                KeyValueRowWithDot("Radio", status.radio, "capture-radio")
                KeyValueRowWithDot("Queue", queueFacts(status.backlog, status.tier), "capture-queue")
                CaptureStorageSection(
                    storage = storage,
                    onTurnOffArchive = onTurnOffArchive,
                    modifier = Modifier.padding(top = OrtSpacing.xs),
                )

                LiveMonitorOversSection(
                    overs = overs,
                    onOpenOver = onOpenOver,
                    modifier = Modifier.padding(top = OrtSpacing.lg),
                )
            }
        }
    }

    if (confirmingStop) {
        StopConfirmDialog(
            title = status.haltConfirmTitle,
            body = status.haltConfirmBody,
            onConfirm = {
                confirmingStop = false
                onStop()
            },
            onDismiss = { confirmingStop = false },
        )
    }
}

/** [CaptureScreen]'s own top row — the exact shape [CaptureStatusScreen]'s own
 * `CaptureStatusTitleRow` establishes (state dot, title, elapsed sub-line, trailing `Stop`), drawn
 * locally rather than promoted/imported because that composable is already `private` and small
 * enough that duplicating it costs less than widening that file's own public surface a fourth time
 * — the same call [LiveMonitorScreen]'s own `LiveMonitorStateRow` already made for the identical
 * reason. */
@Composable
private fun CaptureTitleRow(
    status: CaptureStatusViewState,
    onStopRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = OrtSpacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                StateDot(tone = status.stateTone, size = 9.dp)
                Text(
                    text = status.stateLabel,
                    style = OrtType.screenTitle,
                    color = OrtColors.textHigh,
                    modifier = Modifier.testTag("capture-title"),
                )
            }
            Text(
                text = status.sinceElapsedLabel,
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = 3.dp).testTag("capture-since"),
            )
        }
        status.haltActionLabel?.let { label ->
            TextAction(text = label, onClick = onStopRequested, modifier = Modifier.testTag("capture-stop"))
        }
    }
}

/**
 * `Capture.dc.html`'s "Queue" row (`2 waiting · 0 failed` / `Pass B ~6 s behind · tier 3`) — a
 * textual merge of two already-real facts ([CaptureStatusViewState.backlog],
 * [CaptureStatusViewState.tier]), never a fabricated ETA: no honest per-pass timing signal exists
 * anywhere in `:pipeline` (the same absence [LiveMonitorScreen]'s own five accepted deviations
 * already documented for N07's "~40 s behind" ETA — constitution I applied identically here).
 */
internal fun queueFacts(backlog: KeyValueFacts, tier: KeyValueFacts): KeyValueFacts = KeyValueFacts(
    value = backlog.value,
    subLine = listOfNotNull(backlog.subLine, "tier ${tier.value}", tier.subLine).joinToString(" · "),
)

/**
 * D40/FR-STO-3e (AC-156/157/160) beside D39/FR-STO-3f (AC-158/159) — both real, both recomputed
 * fresh from [storage] every poll, rendered in the ordinary scroll flow so both are **visible
 * without a tap**. Copy and layout deliberately match `RecordingsScreen.kt`'s own
 * `RecordingsBudgetsCard` (`OverAudioBudgetRow`/`ArchiveBudgetRow`) verbatim — the sibling surface
 * that already carries this identical pair of facts — so the two screens can never present the same
 * real state in two different words (see this file's own class kdoc for why this diverges from the
 * artboard's single collapsed row).
 */
@Composable
internal fun CaptureStorageSection(
    storage: CaptureStorageViewState,
    onTurnOffArchive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OrtColors.bgCard, RoundedCornerShape(8.dp))
            .padding(horizontal = OrtSpacing.md, vertical = OrtSpacing.sm)
            .testTag(CAPTURE_STORAGE_SECTION_TEST_TAG),
    ) {
        SectionHeader(label = "Storage")
        CaptureOverAudioRow(storage.overAudio, modifier = Modifier.padding(top = OrtSpacing.xs))
        CaptureArchiveRow(storage.archive, onTurnOffArchive, modifier = Modifier.padding(top = OrtSpacing.sm))
    }
}

@Composable
private fun CaptureOverAudioRow(state: CaptureOverAudioViewState, modifier: Modifier = Modifier) {
    val usedLabel = state.usedBytes.toGigabyteLabel()
    val budgetLabel = state.budgetGb?.let { "$it GB" }
    Column(modifier = modifier.testTag(CAPTURE_OVER_AUDIO_ROW_TEST_TAG)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                text = "Over audio",
                style = OrtType.control,
                color = OrtColors.textHigh,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = budgetLabel?.let { "$usedLabel of $it" } ?: "$usedLabel · no budget set",
                style = OrtType.subLine,
                color = OrtColors.textDim,
            )
        }
        if (state.fractionUsed != null) {
            ProgressBar(
                progress = state.fractionUsed,
                modifier = Modifier.padding(top = 5.dp),
                fillColor = if (state.exceeded) OrtColors.accentAmber else OrtColors.accentGreen,
            )
        }
        // AC-160: visible without a tap, the instant the budget is exceeded — this sub-line itself
        // carries it, rendered unconditionally in the ordinary flow, never behind a dialog or a
        // second screen.
        Text(
            text = state.warningLabel,
            style = OrtType.subLine,
            color = if (state.exceeded) OrtColors.accentAmber else OrtColors.textDim,
            modifier = Modifier.padding(top = 3.dp).testTag(CAPTURE_OVER_AUDIO_WARNING_TEST_TAG),
        )
    }
}

@Composable
private fun CaptureArchiveRow(state: CaptureArchiveViewState, onTurnOff: () -> Unit, modifier: Modifier = Modifier) {
    val usedLabel = state.usedBytes.toGigabyteLabel()
    Column(modifier = modifier.testTag(CAPTURE_ARCHIVE_ROW_TEST_TAG)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                text = "Raw archive",
                style = OrtType.control,
                color = OrtColors.textHigh,
                modifier = Modifier.weight(1f),
            )
            Text(text = "$usedLabel of ${state.budgetGb} GB", style = OrtType.subLine, color = OrtColors.textDim)
        }
        ProgressBar(progress = state.fractionUsed, modifier = Modifier.padding(top = 5.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            // AC-158/159: on/off and the rate — measured outranking the estimate the moment one
            // exists (constitution VI) — stated beside the control that changes it, same line.
            Text(
                text = (if (state.enabled) "on" else "off") +
                    " · ${state.monthlyRateLabel} · oldest archive removed first, overs kept",
                style = OrtType.subLine,
                color = OrtColors.textDim,
                modifier = Modifier.weight(1f, fill = false).testTag(CAPTURE_ARCHIVE_RATE_TEST_TAG),
            )
            TextAction(
                text = if (state.enabled) "Turn off" else "Turn on",
                onClick = onTurnOff,
                modifier = Modifier.testTag(CAPTURE_ARCHIVE_TOGGLE_TEST_TAG),
            )
        }
    }
}

public const val CAPTURE_SCROLL_TEST_TAG: String = "capture-scroll"
public const val CAPTURE_STORAGE_SECTION_TEST_TAG: String = "capture-storage-section"
public const val CAPTURE_OVER_AUDIO_ROW_TEST_TAG: String = "capture-over-audio-row"
public const val CAPTURE_OVER_AUDIO_WARNING_TEST_TAG: String = "capture-over-audio-warning"
public const val CAPTURE_ARCHIVE_ROW_TEST_TAG: String = "capture-archive-row"
public const val CAPTURE_ARCHIVE_TOGGLE_TEST_TAG: String = "capture-archive-toggle"
public const val CAPTURE_ARCHIVE_RATE_TEST_TAG: String = "capture-archive-rate"
