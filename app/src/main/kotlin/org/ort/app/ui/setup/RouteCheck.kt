package org.ort.app.ui.setup

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioIo
import org.ort.capture.android.RouteVerdict
import org.ort.capture.android.RouteVerifier
import org.ort.pipeline.capture.InputStatus
import kotlin.math.abs
import kotlin.math.log10

/** One of `Setup-Verify.dc.html`'s four checks, in board order. */
public enum class RouteCheckStage { NATIVE_RATE, ROUTE_MATCH, SIGNAL, RESAMPLER }

/** Everything [RouteCheck.run] can report — `Setup-Verify.dc.html`'s progressing checklist and
 * `Setup-Route-Mismatch.dc.html`'s halt are both driven from this one sealed type. */
public sealed interface RouteCheckState {
    /** [passed] grows one [RouteCheckStage] at a time as the checklist progresses.
     *
     * R-943 (register, reviewer A3 run 4a, design): [routedDeviceLabel], [levelBars] and
     * [noiseFloorDbfs] are the real facts `Setup-Verify.dc.html`'s own second check line and Input
     * waveform card need — never invented (constitution I). [routedDeviceLabel] is `null` until
     * [RouteCheckStage.ROUTE_MATCH] actually passes (before that, what `getRoutedDevice()` will
     * report is not yet a settled fact); [levelBars] is the last few real peak samples taken during
     * the [RouteCheckStage.SIGNAL] wait, as `0f..1f` fractions on the exact same `-60..0` dBFS scale
     * [levelBarFraction] already gives S07's own meter — empty before listening starts, same shape
     * as [LevelReading.bars]. [noiseFloorDbfs] is a running minimum of the same real peak readings,
     * the identical policy [RealLevelCheck] already uses for S07's own noise-floor fact — `null`
     * until at least one real sample has been read.
     */
    public data class InProgress(
        val passed: Set<RouteCheckStage>,
        val nativeRateHz: Int,
        val elapsedListeningMillis: Long,
        val routedDeviceLabel: String? = null,
        val levelBars: List<Float> = emptyList(),
        val noiseFloorDbfs: Double? = null,
    ) : RouteCheckState

    /**
     * All four checks passed — `Continue` unlocks. [resamplerDescription] is the real pipeline
     * string (`InputStatus.State.Opened.resamplerId` — see [RealRouteCheck]'s own doc comment)
     * whenever a live capture session already has this exact device open; otherwise it is `null`
     * when [nativeRateHz] already matches the pipeline's target rate (no resampling occurs), or a
     * locally-computed, honest description that resampling will happen. [routedDeviceLabel]/
     * [levelBars]/[noiseFloorDbfs]: see [InProgress]'s own doc comment — both stay whatever the
     * real listen loop last built up (empty/`null` for the [publishedVerificationFor] short-circuit
     * path, which never ran a real listen of its own).
     */
    public data class Passed(
        val nativeRateHz: Int,
        val resamplerDescription: String?,
        val routedDeviceLabel: String? = null,
        val levelBars: List<Float> = emptyList(),
        val noiseFloorDbfs: Double? = null,
    ) : RouteCheckState

    /** `Setup-Route-Mismatch.dc.html`: the OS routed audio somewhere other than [selected]. */
    public data class Mismatch(
        val selected: AudioDeviceDescriptor,
        val routed: AudioDeviceDescriptor?,
        val reason: String,
    ) : RouteCheckState

    /** The device would not even open — distinct from a routing mismatch. */
    public data class OpenFailed(val reason: String) : RouteCheckState

    /** No signal was heard within the listening window. */
    public data object TimedOut : RouteCheckState
}

/**
 * S05/S06's live check (FR-CAP-2a, FR-CAP-3, FR-CAP-3a → AC-2, AC-97, AC-98) — the exact same
 * [RouteVerifier] and [AudioIo.routedDevice] (`getRoutedDevice()`) capture itself checks before
 * ever recording (technical design §5.2), driven here against the operator's chosen input before
 * setup ever lets capture start. A behavioural fake is not needed separately from [RealRouteCheck]
 * itself — it already runs, in tests, against `:capture-android`'s own
 * [org.ort.capture.android.fake.FakeAudioIo] (constitution II), so a second implementation would
 * only add a place for the two to drift apart. On real hardware the caller supplies
 * `AndroidAudioIo` instead — the same [AudioIo] seam capture's own `AudioRecordSource` uses.
 *
 * **The `:capture-api` classpath gap (register R-081) is now resolved for this check's real
 * purpose, not worked around.** This class still reads [AudioIo] directly (raw
 * `select`/`open`/`routedDevice`/`read`) rather than wrapping `AudioRecordSource`, because
 * `AudioRecordSource`'s public surface (`resamplerIdentity`, `deviceFormat`, `outputFormat`,
 * `start(): Flow<CaptureEvent>`) is still typed entirely in `:capture-api`, which is still not on
 * `:app`'s compile classpath through any edge this package may add (`:capture-android` and
 * `:pipeline` both depend on it with `implementation`, not `api`). What changed is
 * [org.ort.pipeline.capture.InputStatus] (WP11c): its `State.Opened.resamplerId` is the pipeline's
 * *own* `ResamplerIdentity.toString()` (or the honest `"none (native rate matches output)"` when
 * no resampling occurs — `RealCaptureService`'s own doc comment), already reduced to a `String`
 * pipeline-side specifically so `:app` can read it without ever needing the typed object. This
 * class's fourth check reads that string whenever [InputStatus.State.Opened] already exists for
 * the device being verified — see [Passed]'s own doc comment. **This is a partial, not complete,
 * resolution**: `InputStatus` is published only by `RealCaptureService` itself, so it holds a real
 * value for this device only once a capture session has actually opened it — which is not yet true
 * during the ordinary first-run path (S05 runs *before* capture ever starts). The
 * locally-computed fallback below therefore remains the primary path for a fresh install, not dead
 * code; it becomes secondary only once setup is re-entered while capture is already running (an
 * operator revisiting Settings, or S05 re-verifying after `Setup-Route-Mismatch`, with a capture
 * session still up on the same device).
 *
 * **R-224 (validator pass 2) extends that same "secondary path" to the third check too, not only
 * the fourth.** Before: the third check (`SIGNAL`) always ran its own real, up-to-30s raw-audio
 * listening loop even when [InputStatus] already reported this exact device open, route-verified,
 * with a real resampler identity — on the validator's `input-verified` scenario (a live capture
 * session already has the device open) that raw loop reads nothing, because nothing is feeding it
 * audio outside the real capture session's own read loop, and the check timed out every time
 * despite the pipeline already knowing the route is good. Now: once the first two checks pass,
 * [publishedVerificationFor] is read once — a real, already-verified [InputStatus.State.Opened]
 * for this exact device short-circuits stages three and four straight to [Passed] with the
 * pipeline's own `resamplerId`, never re-probing a device a live session already has open. The
 * fallback (fresh-install, first-run) path — this class's own raw signal-wait loop — is otherwise
 * unchanged and remains the primary path, exactly as documented above for the fourth check alone.
 */
public interface RouteCheck {
    public fun run(io: AudioIo, selected: AudioDeviceDescriptor): Flow<RouteCheckState>
}

public class RealRouteCheck(
    private val listenTimeoutMillis: Long = DEFAULT_LISTEN_TIMEOUT_MILLIS,
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
    private val signalThresholdDbfs: Double = DEFAULT_SIGNAL_THRESHOLD_DBFS,
) : RouteCheck {

    override fun run(io: AudioIo, selected: AudioDeviceDescriptor): Flow<RouteCheckState> = flow {
        if (!io.open()) {
            emit(RouteCheckState.OpenFailed("device open failed"))
            return@flow
        }
        val nativeRate = io.deviceSampleRate
        var passed = setOf(RouteCheckStage.NATIVE_RATE)
        emit(RouteCheckState.InProgress(passed, nativeRate, 0L))

        val routedDevice = io.routedDevice()
        val verdict = RouteVerifier.verify(selected, routedDevice)
        if (verdict is RouteVerdict.Mismatch) {
            io.close()
            emit(RouteCheckState.Mismatch(selected, verdict.routed, verdict.reason))
            return@flow
        }
        passed = passed + RouteCheckStage.ROUTE_MATCH
        val routedDeviceLabel = routedDevice?.label
        emit(RouteCheckState.InProgress(passed, nativeRate, 0L, routedDeviceLabel))

        val published = publishedVerificationFor(selected)
        if (published != null) {
            io.close()
            emit(
                RouteCheckState.Passed(
                    published.nativeRateHz,
                    published.resamplerId,
                    routedDeviceLabel ?: published.descriptor.label,
                ),
            )
            return@flow
        }

        val listened = listenForSignal(io, passed, nativeRate, routedDeviceLabel)
        if (listened == null) {
            io.close()
            emit(RouteCheckState.TimedOut)
            return@flow
        }
        passed = passed + RouteCheckStage.SIGNAL
        emit(
            RouteCheckState.InProgress(
                passed,
                nativeRate,
                listened.elapsed,
                routedDeviceLabel,
                listened.levelBars,
                listened.noiseFloorDbfs,
            ),
        )

        val resamplerDescription = resamplerDescriptionFor(nativeRate)
        io.close()
        emit(
            RouteCheckState.Passed(
                nativeRate,
                resamplerDescription,
                routedDeviceLabel,
                listened.levelBars,
                listened.noiseFloorDbfs,
            ),
        )
    }

    /** [listenForSignal]'s own real, accumulated facts once a signal was genuinely heard — a `null`
     * return from that function means the listen timed out instead; this type is never constructed
     * for that case. */
    private class ListenResult(val elapsed: Long, val levelBars: List<Float>, val noiseFloorDbfs: Double?)

    /**
     * R-943: split out of [run] purely to keep that function under detekt's length/complexity
     * ceiling — no behaviour changed from the original inline loop. Real-time listens for a signal
     * above [signalThresholdDbfs], emitting an [RouteCheckState.InProgress] (carrying the real,
     * accumulated [ListenResult] facts so far) after every unsuccessful poll; `null` once
     * [listenTimeoutMillis] elapses with nothing heard, exactly [kotlinx.coroutines.withTimeoutOrNull]'s
     * own contract.
     */
    private suspend fun FlowCollector<RouteCheckState>.listenForSignal(
        io: AudioIo,
        passed: Set<RouteCheckStage>,
        nativeRate: Int,
        routedDeviceLabel: String?,
    ): ListenResult? {
        var elapsed = 0L
        var heard = false
        val buffer = ShortArray(READ_BUFFER_FRAMES)
        // R-943: the same real, per-sample rolling window RealLevelCheck's own bars use (never
        // fabricated) — accumulated only while a raw sample was actually read this iteration.
        val levelBars = ArrayDeque<Float>()
        var noiseFloorDbfs: Double? = null
        return withTimeoutOrNull(listenTimeoutMillis) {
            while (!heard) {
                val n = io.read(buffer)
                if (n > 0) {
                    val peak = peakDbfs(buffer, n)
                    levelBars.addLast(levelBarFraction(peak))
                    if (levelBars.size > INPUT_BAR_COUNT) levelBars.removeFirst()
                    noiseFloorDbfs = noiseFloorDbfs?.let { minOf(it, peak) } ?: peak
                    heard = peak >= signalThresholdDbfs
                }
                if (!heard) {
                    delay(pollIntervalMillis)
                    elapsed += pollIntervalMillis
                    emit(
                        RouteCheckState.InProgress(
                            passed,
                            nativeRate,
                            elapsed,
                            routedDeviceLabel,
                            levelBars.toList(),
                            noiseFloorDbfs,
                        ),
                    )
                }
            }
            ListenResult(elapsed, levelBars.toList(), noiseFloorDbfs)
        }
    }

    /**
     * The raw signal-wait loop's own, locally-computed resampler description — reached only when
     * [publishedVerificationFor] found nothing (this is the fresh-install, first-run path;
     * `InputStatus` holds no real value for this device yet, or has not yet been verified). A real
     * pipeline value is never available here without also satisfying the short-circuit above — see
     * this class's own doc comment for the R-224 split between the two paths.
     */
    private fun resamplerDescriptionFor(nativeRate: Int): String? {
        if (nativeRate == OUTPUT_SAMPLE_RATE_HZ) return null
        return "$nativeRate Hz -> $OUTPUT_SAMPLE_RATE_HZ Hz, resampled"
    }

    /** R-224 (validator pass 2): the one place both the short-circuit above and (indirectly, by
     * its absence) [resamplerDescriptionFor] key off [InputStatus] — a real
     * [InputStatus.State.Opened] for this exact device (matched by id, never borrowed from a
     * different one), *and* [InputStatus.State.Opened.routeVerified] true. `routeVerified` alone
     * is the gate: a device [InputStatus] merely has open but not yet verified (the moment after
     * selection, before `AudioRecordSource`'s own first-read check — see that class's kdoc) must
     * still run this class's own real checks, never borrow an unverified pipeline state as if it
     * were a pass. */
    private fun publishedVerificationFor(selected: AudioDeviceDescriptor): InputStatus.State.Opened? =
        (InputStatus.state as? InputStatus.State.Opened)
            ?.takeIf { it.descriptor.id == selected.id && it.routeVerified }

    /** R-943: a genuinely silent buffer used to return `-Infinity`, which is harmless for
     * [levelBarFraction] (it clamps) but poisons a running-minimum noise floor forever once hit —
     * the identical trap [RealLevelCheck]'s own `peakDbfs` already documents and avoids. Returns
     * the chart's own floor sentinel instead, matching that fix exactly; the [signalThresholdDbfs]
     * comparison this feeds is unaffected either way (`-60.0 >= -40.0` is exactly as false as
     * `-Infinity >= -40.0` was). */
    private fun peakDbfs(buffer: ShortArray, n: Int): Double {
        var peak = 0
        for (i in 0 until n) {
            val a = abs(buffer[i].toInt())
            if (a > peak) peak = a
        }
        if (peak == 0) return NOISE_FLOOR_SILENCE_DBFS
        return 20.0 * log10(peak / SHORT_FULL_SCALE)
    }

    public companion object {
        public const val DEFAULT_LISTEN_TIMEOUT_MILLIS: Long = 30_000L
        public const val DEFAULT_POLL_INTERVAL_MILLIS: Long = 200L

        /** R-943: `Setup-Verify.dc.html`'s own Input waveform card draws exactly 12 bars — matched
         * here, not [RealLevelCheck.DEFAULT_BAR_COUNT] (15, S07's own board), so each screen's
         * rolling window matches its own board precisely. */
        public const val INPUT_BAR_COUNT: Int = 12

        /** A conservative floor: real speech from a squelch-open radio sits well above this;
         * ordinary line/circuit noise on an idle input does not. Not a spec-mandated figure —
         * report this to the lead if a measured target ships later. */
        public const val DEFAULT_SIGNAL_THRESHOLD_DBFS: Double = -40.0

        /** `:capture-api`'s `AudioFormat.OUTPUT_SAMPLE_RATE` — duplicated as a literal rather than
         * imported because that module is not reachable from `:app` (see this class's own doc
         * comment). Kept in sync by citation, not by type: `capture-api/.../AudioFormat.kt`. */
        public const val OUTPUT_SAMPLE_RATE_HZ: Int = 16_000

        private const val READ_BUFFER_FRAMES: Int = 1_600
        private const val SHORT_FULL_SCALE: Double = 32_768.0

        /** [peakDbfs]'s sentinel for "read nothing at all this frame" — the identical value and
         * reasoning [RealLevelCheck]'s own `NOISE_FLOOR_SILENCE_DBFS` documents (deliberately far
         * below the chart's own floor so a running-minimum noise floor is never poisoned to
         * `-Infinity` by one silent read). */
        private const val NOISE_FLOOR_SILENCE_DBFS: Double = -90.0
    }
}
