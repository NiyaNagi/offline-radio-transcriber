package org.ort.pipeline.passb

import org.ort.asrapi.SegmentCandidate
import org.ort.capture.android.codec.DeflatePredictiveCodec
import org.ort.capture.android.codec.LosslessCodec
import org.ort.data.OrtDatabase
import java.io.File

/**
 * The real [SegmentAudioProvider] (build-plan P12, defect 3): [SegmentAudioProvider]'s own doc
 * comment names "wiring a real [SegmentAudioProvider] to [org.ort.capture.android.codec.FlacStore]'s
 * decoder" as left to a session with a device to verify it against — this is that wiring. It
 * reads back exactly what [org.ort.pipeline.capture.RealSegmentSink] wrote: the transmission's
 * derived [org.ort.data.entity.TransmissionEntity.audioPath], decoded with the same
 * [LosslessCodec] `RealSegmentSink` encoded it with (constitution III: retained audio must remain
 * sufficient to re-run every pass — decoding with a *different* codec than encoded would silently
 * violate that).
 *
 * [SegmentCandidate.vadDetectedSpeech] is always `true` here: [RealSegmentSink] only ever enqueues
 * a segment whose [org.ort.segment.SegmentOutcome] was `SPEECH` (a rejected segment's staged PCM
 * is deleted, never enqueued), so a leased item reaching this provider was, by construction,
 * already judged speech by the segmenter's VAD.
 */
public class FlacSegmentAudioProvider(
    private val filesDir: File,
    private val db: OrtDatabase,
    private val codec: LosslessCodec = DeflatePredictiveCodec(),
) : SegmentAudioProvider {

    override suspend fun forItem(item: org.ort.data.entity.WorkQueueItemEntity): SegmentAudio {
        val transmission = db.transmissionDao().getById(item.transmissionId)
            ?: error("no transmission row for ${item.transmissionId} -- cannot load its audio")
        val encoded = File(filesDir, transmission.audioPath())
        check(encoded.isFile) { "expected retained audio at ${encoded.path} for ${item.transmissionId}" }
        val pcmBytes = codec.decode(encoded.readBytes())
        val samples = pcmBytesToFloats(pcmBytes)
        return SegmentAudio(
            samples = samples,
            candidate = SegmentCandidate(durationMs = transmission.durationMs.toInt(), vadDetectedSpeech = true),
        )
    }

    /** The exact inverse of [org.ort.pipeline.capture.RealSegmentSink]'s little-endian 16-bit write. */
    private fun pcmBytesToFloats(bytes: ByteArray): FloatArray {
        val samples = FloatArray(bytes.size / 2)
        for (i in samples.indices) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            val short = ((hi shl 8) or lo).toShort()
            samples[i] = short / SHORT_MAX
        }
        return samples
    }

    private companion object {
        const val SHORT_MAX: Float = 32_768f
    }
}
