package org.ort.captureapi

import kotlinx.coroutines.flow.Flow

/**
 * A source of 16 kHz mono PCM, post-resample (technical design §5.1). Two implementations
 * exist: [WavFileSource] here in `:capture-api`, and `AudioRecordSource` in `:capture-android`
 * (build-plan P8). The harness and every timing-independent test use the WAV source.
 *
 * Two formats, not one: [deviceFormat] is what the hardware actually gives us, [outputFormat]
 * is always [AudioFormat.MODEL_INPUT]. The resampler that bridges them
 * ([resamplerIdentity]) is in the signal chain of every accuracy number the project publishes,
 * so its identity is recorded (FR-CAP-2a, FR-TST-4 → AC-97).
 */
public interface CaptureSource {

    public val deviceFormat: AudioFormat

    public val outputFormat: AudioFormat

    /**
     * The resampler used to bridge [deviceFormat] to [outputFormat], or `null` when the device
     * already produces the output rate and no resampling occurred.
     */
    public val resamplerIdentity: ResamplerIdentity?

    /** Begins producing events. Cold: collecting starts capture, cancelling the collector stops it. */
    public fun start(): Flow<CaptureEvent>

    /** Requests an in-progress [start] collection to end at the next opportunity. */
    public fun stop()

    /** The device the OS actually routed to, for post-hoc verification. `null` on the file source. */
    public fun routedDevice(): String?
}

/** Everything a [CaptureSource] emits on its [CaptureSource.start] flow. */
public sealed interface CaptureEvent {

    /**
     * A block of output-rate mono PCM16. [framePosition] is the sample index of `pcm[0]` in the
     * session timeline (0-based, monotonically increasing, gap-free within a run).
     */
    public data class Frames(val pcm: ShortArray, val framePosition: Long) : CaptureEvent {
        override fun equals(other: Any?): Boolean =
            other is Frames && framePosition == other.framePosition && pcm.contentEquals(other.pcm)

        override fun hashCode(): Int = 31 * pcm.contentHashCode() + framePosition.hashCode()
    }

    /** The OS reported a route change; the consumer must re-verify against its selection. */
    public data object RouteChanged : CaptureEvent

    /** Capture was interrupted (focus loss, device error). A gap opens until [Resumed]. */
    public data class Interrupted(val cause: String) : CaptureEvent

    /** Capture resumed after an [Interrupted]. */
    public data object Resumed : CaptureEvent

    /** The source is exhausted (file source only) — a clean end, not a failure. */
    public data object EndOfStream : CaptureEvent

    /** Capture failed unrecoverably. */
    public data class Failed(val error: String) : CaptureEvent
}
