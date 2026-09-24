package org.ort.app.ui.setup

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.InProgressRing
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType
import java.util.Locale

/**
 * The route-check section of [ListenScreen] (`Setup-Listen.dc.html`) — the four checks progressing in
 * board order, driven by [RouteCheck], with the input waveform card beneath them.
 *
 * **P39 (D58): this was `VerifyScreen`, a step of its own.** It now expands in place under the route
 * list, which is what D58's *"the verify checklist expanding in place"* names. Nothing about the
 * *gate* moved with it: a [RouteCheckState.Mismatch] still hands off to [RouteMismatchScreen] — the
 * one deliberate hard halt in the product (constitution IV, AC-201's own closing sentence) — and
 * [ListenScreen]'s `Continue` is still only ever enabled to advance on [RouteCheckState.Passed].
 *
 * Validator finding (register R-120..R-125, halt): [RouteCheckState.TimedOut] used to fall through to
 * no branch at all — every check icon reverted to unfilled, the screen offered no honest explanation,
 * and `Continue` stayed disabled forever. Now: the two stages that genuinely completed before the 30 s
 * listening window ran out stay ticked, the signal check shows an honest failed mark and "No signal
 * heard in 30 s on `<device>`", and [ListenScreen] offers `Try again` / `Choose another input`.
 */
@Composable
internal fun VerifySection(inputLabel: String, check: RouteCheckState?) {
    val timedOut = check is RouteCheckState.TimedOut
    val passed = when (check) {
        is RouteCheckState.InProgress -> check.passed
        is RouteCheckState.Passed -> RouteCheckStage.entries.toSet()
        RouteCheckState.TimedOut -> setOf(RouteCheckStage.NATIVE_RATE, RouteCheckStage.ROUTE_MATCH)
        else -> emptySet()
    }
    val facts = routeCheckFactsFrom(check)

    CheckRow(
        title = "Opened at native rate",
        // R-1169: the audio source the open actually obtained, named beside the rate it opened at —
        // before this there was no way to tell from a capture whether the OEM had applied its own gain
        // and noise suppression. Omitted entirely, never guessed, when the [AudioIo] reported none.
        detail = facts.nativeRateHz?.let { rate ->
            listOfNotNull("${formatHzGrouped(rate)} Hz · mono · 16-bit", facts.audioSourceLabel)
                .joinToString(" · ")
        },
        done = RouteCheckStage.NATIVE_RATE in passed,
        testTag = "setup-verify-check-native-rate",
    )
    CheckRow(
        title = "Routed device matches the one you chose",
        detail = facts.routedDeviceLabel?.let { "getRoutedDevice() → $it" },
        done = RouteCheckStage.ROUTE_MATCH in passed,
        testTag = "setup-verify-check-route-match",
    )
    CheckRow(
        title = "Listening for signal",
        detail = if (timedOut) {
            "No signal heard in 30 s on $inputLabel"
        } else {
            "Key the radio, or wait for traffic — up to 30 s"
        },
        done = RouteCheckStage.SIGNAL in passed,
        failed = timedOut,
        inProgress = check is RouteCheckState.InProgress && RouteCheckStage.SIGNAL !in passed,
        // R-943: the board's own live "0:11" counter -- only while genuinely still listening, never a
        // stale or fabricated value once the stage has already passed or failed.
        trailing = facts.elapsedListeningMillis?.takeIf { RouteCheckStage.SIGNAL !in passed }
            ?.let(::formatElapsed),
        testTag = "setup-verify-check-signal",
    )
    CheckRow(
        title = "Resampler identity recorded",
        detail = null,
        done = RouteCheckStage.RESAMPLER in passed,
        testTag = "setup-verify-check-resampler",
    )

    // R-943: the board's own Input waveform card -- real per-sample level history taken during the
    // SIGNAL listen (empty, honestly, before listening has started at all: an empty chart, never a
    // fabricated one, matching the meter's own constitution-I rule).
    InputWaveformCard(
        bars = facts.levelBars,
        noiseFloorDbfs = facts.noiseFloorDbfs,
        signalHeard = RouteCheckStage.SIGNAL in passed,
    )
}

/**
 * R-1170: what [ListenScreen]'s primary says when it refuses. **This is the careful one.**
 * Constitution IV is absolute that a route which is not the selected device halts capture, and
 * `ROUTE_MISMATCH` is the one deliberate hard halt in the whole flow — so keeping the button lit must
 * never become a way past an unverified route. The honest version, and the one built: the tap *tells
 * the operator the check has not passed and what to do about it*; it does not advance. The gate is
 * untouched; only how the block is communicated has changed.
 */
internal const val VERIFY_NOT_PASSED_YET: String =
    "The route check has not passed yet. Setup cannot continue until it does — wait for the checks " +
        "above to finish, or choose a different input."

/** The handful of real, optional facts the checklist/waveform card render — bundled so deriving them
 * from a [RouteCheckState] is one `when`, not five. Every field is `null`/empty for any state that
 * never carries it (constitution I). */
private data class RouteCheckFacts(
    val nativeRateHz: Int?,
    val routedDeviceLabel: String?,
    val elapsedListeningMillis: Long?,
    val levelBars: List<Float>,
    val noiseFloorDbfs: Double?,
    val audioSourceLabel: String?,
)

private fun routeCheckFactsFrom(check: RouteCheckState?): RouteCheckFacts = when (check) {
    is RouteCheckState.InProgress -> RouteCheckFacts(
        check.nativeRateHz,
        check.routedDeviceLabel,
        check.elapsedListeningMillis,
        check.levelBars,
        check.noiseFloorDbfs,
        check.audioSourceLabel,
    )
    is RouteCheckState.Passed -> RouteCheckFacts(
        check.nativeRateHz,
        check.routedDeviceLabel,
        null,
        check.levelBars,
        check.noiseFloorDbfs,
        check.audioSourceLabel,
    )
    else -> RouteCheckFacts(null, null, null, emptyList(), null, null)
}

/** `"48000"` → `"48 000"` (space-grouped thousands) — the board's own formatting for the native-rate
 * detail line. `Locale.ROOT`'s own grouping separator is a comma, replaced with a plain space to match
 * the board rather than assuming any particular locale's own convention. */
internal fun formatHzGrouped(hz: Int): String = String.format(Locale.ROOT, "%,d", hz).replace(',', ' ')

/** Milliseconds elapsed listening, as the board's own `"0:11"` (minutes:seconds, zero-padded
 * seconds). */
internal fun formatElapsed(millis: Long): String {
    val totalSeconds = millis / MILLIS_PER_SECOND
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L

@Composable
private fun CheckRow(
    title: String,
    detail: String?,
    done: Boolean,
    testTag: String,
    modifier: Modifier = Modifier,
    inProgress: Boolean = false,
    failed: Boolean = false,
    trailing: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 13.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when {
                failed -> Icon(
                    imageVector = OrtIcons.dismiss,
                    contentDescription = "did not complete",
                    tint = OrtColors.haltOnFill,
                    modifier = Modifier
                        .size(20.dp)
                        .background(OrtColors.haltFill, CircleShape)
                        .padding(4.dp),
                )
                done -> Icon(
                    imageVector = OrtIcons.check,
                    contentDescription = "done",
                    tint = OrtColors.accentOnGreen,
                    modifier = Modifier
                        .size(20.dp)
                        .background(OrtColors.accentGreen, CircleShape)
                        .padding(4.dp),
                )
                inProgress -> InProgressRing(size = 20.dp, color = OrtColors.accentGreen, strokeWidth = 2.dp)
                // R-943 (register, reviewer A3 run 4a, design): this used to be a filled circle the
                // exact colour of the screen's own background -- invisible, not the board's own dim
                // *ring* a not-yet-reached check draws.
                else -> Box(
                    modifier = Modifier
                        .size(20.dp)
                        .border(1.5.dp, OrtColors.textLow, CircleShape)
                        .testTag("$testTag-pending-ring"),
                )
            }
        }
        Column(modifier = Modifier.padding(start = 13.dp).weight(1f)) {
            Text(
                text = title,
                style = OrtType.control,
                color = if (done || inProgress || failed) OrtColors.textHigh else OrtColors.textLow,
            )
            detail?.let { Text(text = it, style = OrtType.timeFreq, color = OrtColors.textDim) }
        }
        // R-943: the board's own live "0:11" elapsed counter, e.g. beside "Listening for signal".
        trailing?.let { Text(text = it, style = OrtType.timeFreq, color = OrtColors.accentGreenDim) }
    }
}

/**
 * R-943 (register, reviewer A3 run 4a, design): the board's own "Input" waveform card — the real
 * per-sample level history [RouteCheckState.InProgress.levelBars]/[RouteCheckState.Passed.levelBars]
 * built up during the [RouteCheckStage.SIGNAL] listen, drawn the same proportional-bar way the meter
 * draws its own (constitution I: an honest flat line — [bars] empty — before listening has produced
 * any real sample at all, never a fabricated waveform). [signalHeard] only ever reads "signal heard"
 * once [RouteCheckStage.SIGNAL] has genuinely passed — never claimed early.
 *
 * R-981 (register, design, reopened): the board's own right-hand caption is a real `space-between` row
 * with the left-hand noise-floor fact, not a "heard" badge that only exists in the illustrated case.
 * It now always names the real fact: "signal heard" once true, an honest "not yet" while
 * [signalHeard] is still `false`.
 */
@Composable
private fun InputWaveformCard(
    bars: List<Float>,
    noiseFloorDbfs: Double?,
    signalHeard: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().testTag("setup-verify-input-waveform")) {
        // P39: labelled "Signal" rather than the board's old "Input" — on the merged screen the route
        // list directly above is what "input" now means, and two sections a thumb apart cannot both
        // carry that word without the waveform reading as a second route picker.
        Text(text = "Signal".uppercase(), style = OrtType.sectionLabel, color = OrtColors.textFaint)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .padding(top = 10.dp)
                .testTag("setup-verify-input-waveform-canvas"),
        ) {
            val gapPx = 2.dp.toPx()
            levelBarRects(bars, size.width, size.height, gapPx).forEach { rect ->
                drawRect(
                    color = rect.color,
                    topLeft = Offset(rect.x, rect.topY),
                    size = Size(rect.width, size.height - rect.topY),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = noiseFloorDbfs?.let { "noise floor %.0f dBFS".format(it).replace('-', '−') } ?: "noise floor —",
                style = OrtType.axis,
                color = OrtColors.textLow,
            )
            Text(
                text = if (signalHeard) "signal heard" else "not yet",
                style = OrtType.axis,
                color = if (signalHeard) OrtColors.accentGreenDim else OrtColors.textLow,
                modifier = Modifier.testTag("setup-verify-input-waveform-signal-caption"),
            )
        }
    }
}
