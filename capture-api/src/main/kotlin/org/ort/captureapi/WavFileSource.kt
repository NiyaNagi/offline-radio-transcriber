package org.ort.captureapi

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * Replays a WAV file through the [CaptureSource] contract (technical design §5.1, FR-TST-1).
 *
 * The whole file is decoded and, if its rate differs from [outputRate], resampled **once** up
 * front with [PolyphaseResampler]; frames are then emitted from that fixed buffer. This is what
 * makes replay a pure function of the input: a real-time replay ([realTime] = true, which
 * sleeps one frame-duration per block) and an as-fast-as-possible replay emit exactly the same
 * [CaptureEvent.Frames], in the same order, with the same `framePosition`s (AC-89). The
 * resampler used is reported via [resamplerIdentity] and recorded on the session (AC-97).
 */
public class WavFileSource(
    private val source: WavAudio,
    private val outputRate: Int = AudioFormat.OUTPUT_SAMPLE_RATE,
    private val frameSize: Int = DEFAULT_FRAME_SIZE,
    private val realTime: Boolean = false,
) : CaptureSource {

    public constructor(
        file: File,
        outputRate: Int = AudioFormat.OUTPUT_SAMPLE_RATE,
        frameSize: Int = DEFAULT_FRAME_SIZE,
        realTime: Boolean = false,
    ) : this(WavIo.read(file), outputRate, frameSize, realTime)

    init {
        require(frameSize > 0) { "frameSize must be positive" }
    }

    private val resampler: PolyphaseResampler? =
        if (source.sampleRate == outputRate) null else PolyphaseResampler(source.sampleRate, outputRate)

    /** The output-rate mono PCM this source replays. Computed once. */
    public val output: ShortArray = resampler?.resample(source.samples) ?: source.samples.copyOf()

    override val deviceFormat: AudioFormat = AudioFormat(source.sampleRate, channels = 1)
    override val outputFormat: AudioFormat = AudioFormat(outputRate, channels = 1)
    override val resamplerIdentity: ResamplerIdentity? = resampler?.identity

    @Volatile private var stopRequested = false

    override fun routedDevice(): String? = null

    override fun stop() {
        stopRequested = true
    }

    override fun start(): Flow<CaptureEvent> = flow {
        stopRequested = false
        val frameNanos = 1_000_000_000L / outputRate
        var pos = 0
        while (pos < output.size) {
            currentCoroutineContext().ensureActive()
            if (stopRequested) break
            val len = minOf(frameSize, output.size - pos)
            val block = ShortArray(len)
            System.arraycopy(output, pos, block, 0, len)
            emit(CaptureEvent.Frames(block, pos.toLong()))
            pos += len
            if (realTime) delay(len * frameNanos / 1_000_000L)
        }
        if (!stopRequested) emit(CaptureEvent.EndOfStream)
    }

    public companion object {
        public const val DEFAULT_FRAME_SIZE: Int = 512
    }
}
