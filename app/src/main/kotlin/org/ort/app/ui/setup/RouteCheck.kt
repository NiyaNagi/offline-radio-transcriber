package org.ort.app.ui.setup

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
    /** [passed] grows one [RouteCheckStage] at a time as the checklist progresses. */
    public data class InProgress(
        val passed: Set<RouteCheckStage>,
        val nativeRateHz: Int,
        val elapsedListeningMillis: Long,
    ) : RouteCheckState

    /**
     * All four checks passed — `Continue` unlocks. [resamplerDescription] is the real pipeline
     * string (`InputStatus.State.Opened.resamplerId` — see [RealRouteCheck]'s own doc comment)
     * whenever a live capture session already has this exact device open; otherwise it is `null`
     * when [nativeRateHz] already matches the pipeline's target rate (no resampling occurs), or a
     * locally-computed, honest description that resampling will happen.
     */
    public data class Passed(val nativeRateHz: Int, val resamplerDescription: String?) : RouteCheckState

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

        val verdict = RouteVerifier.verify(selected, io.routedDevice())
        if (verdict is RouteVerdict.Mismatch) {
            io.close()
            emit(RouteCheckState.Mismatch(selected, verdict.routed, verdict.reason))
            return@flow
        }
        passed = passed + RouteCheckStage.ROUTE_MATCH
        emit(RouteCheckState.InProgress(passed, nativeRate, 0L))

        var elapsed = 0L
        var heard = false
        val buffer = ShortArray(READ_BUFFER_FRAMES)
        val finishedListening = withTimeoutOrNull(listenTimeoutMillis) {
            while (!heard) {
                val n = io.read(buffer)
                if (n > 0 && peakDbfs(buffer, n) >= signalThresholdDbfs) {
                    heard = true
                } else {
                    delay(pollIntervalMillis)
                    elapsed += pollIntervalMillis
                    emit(RouteCheckState.InProgress(passed, nativeRate, elapsed))
                }
            }
        }
        if (finishedListening == null) {
            io.close()
            emit(RouteCheckState.TimedOut)
            return@flow
        }
        passed = passed + RouteCheckStage.SIGNAL
        emit(RouteCheckState.InProgress(passed, nativeRate, elapsed))

        val resamplerDescription = resamplerDescriptionFor(nativeRate, selected)
        io.close()
        emit(RouteCheckState.Passed(nativeRate, resamplerDescription))
    }

    /** [InputStatus.state] when it is a real [InputStatus.State.Opened] for this exact device
     * (matched by id — never borrowing a different device's identity) is the pipeline's own,
     * already-honest string; otherwise a locally-computed description — see this class's own doc
     * comment for exactly when each path applies. */
    private fun resamplerDescriptionFor(nativeRate: Int, selected: AudioDeviceDescriptor): String? {
        val fromPipeline = (InputStatus.state as? InputStatus.State.Opened)
            ?.takeIf { it.descriptor.id == selected.id }
            ?.resamplerId
        if (fromPipeline != null) return fromPipeline
        if (nativeRate == OUTPUT_SAMPLE_RATE_HZ) return null
        return "$nativeRate Hz -> $OUTPUT_SAMPLE_RATE_HZ Hz, resampled"
    }

    private fun peakDbfs(buffer: ShortArray, n: Int): Double {
        var peak = 0
        for (i in 0 until n) {
            val a = abs(buffer[i].toInt())
            if (a > peak) peak = a
        }
        if (peak == 0) return Double.NEGATIVE_INFINITY
        return 20.0 * log10(peak / SHORT_FULL_SCALE)
    }

    public companion object {
        public const val DEFAULT_LISTEN_TIMEOUT_MILLIS: Long = 30_000L
        public const val DEFAULT_POLL_INTERVAL_MILLIS: Long = 200L

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
    }
}
