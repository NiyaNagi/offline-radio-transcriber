package org.ort.app.ui.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import org.ort.core.PassId
import org.ort.data.OrtDatabase
import org.ort.data.entity.WorkQueueItemEntity
import org.ort.data.entity.WorkQueueState
import org.ort.pipeline.passb.FlacSegmentAudioProvider

/**
 * The real [TransmissionAudioPlayer] (build-plan P14, FR-UI-5). Decodes a transmission's retained
 * audio through [FlacSegmentAudioProvider] — the exact same public class `:pipeline`'s Pass B
 * already uses to read audio back for ASR (build-plan P12) — rather than a second decode path
 * that could silently drift from it (constitution III: retained audio must remain sufficient to
 * re-run every pass; a playback-only decoder that disagreed with the ASR decoder would violate
 * that quietly). [FlacSegmentAudioProvider.forItem] takes a [WorkQueueItemEntity]; this constructs
 * a throwaway one (never inserted into the queue) purely to reuse that public entry point rather
 * than duplicating its decode logic.
 *
 * No device is available to prove sound actually reaches a speaker in this session — see
 * `RealTransmissionAudioPlayerTest`'s doc comment. What is genuinely proven here is the decode
 * path and the honesty of [PlaybackOutcome.Unavailable] when there is nothing to play.
 */
public class RealTransmissionAudioPlayer(private val context: Context) : TransmissionAudioPlayer {

    private var track: AudioTrack? = null

    override suspend fun play(transmissionId: String): PlaybackOutcome {
        stop()
        val db = OrtDatabase.create(context.applicationContext)
        val entity = db.transmissionDao().getById(transmissionId)
            ?: return PlaybackOutcome.Unavailable("no transmission row for $transmissionId")
        val audioFile = java.io.File(context.filesDir, entity.audioPath())
        if (!audioFile.isFile) {
            return PlaybackOutcome.Unavailable("no retained audio at ${entity.audioPath()}")
        }
        val provider = FlacSegmentAudioProvider(context.filesDir, db)
        val decoded = try {
            provider.forItem(
                WorkQueueItemEntity(
                    transmissionId = transmissionId,
                    pass = PassId.B_OFFLINE,
                    state = WorkQueueState.READY,
                    priority = 0,
                    enqueuedAt = 0L,
                ),
            )
        } catch (e: Exception) {
            return PlaybackOutcome.Unavailable("could not decode retained audio: ${e.message}")
        }
        return try {
            playPcm(floatsToPcm16(decoded.samples), sampleRateFromFormat(entity.audioFormat))
            PlaybackOutcome.Played
        } catch (e: Exception) {
            PlaybackOutcome.Unavailable("could not start playback: ${e.message}")
        }
    }

    override fun stop() {
        track?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        track = null
    }

    private fun playPcm(pcm: ShortArray, sampleRateHz: Int) {
        val minBufferBytes = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = maxOf(minBufferBytes, pcm.size * 2)

        // The classic constructor, not the `AudioTrack.Builder` API — Robolectric's shadow
        // supports this path reliably; the newer builder left the shadowed track uninitialized in
        // this session's Robolectric version, which surfaced only under test, not on the codec
        // decode this class actually needs to prove here.
        @Suppress("DEPRECATION")
        val newTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
            AudioTrack.MODE_STATIC,
        )
        newTrack.write(pcm, 0, pcm.size)
        track = newTrack
        newTrack.play()
    }

    private companion object {
        /** The exact inverse of [org.ort.pipeline.passb.FlacSegmentAudioProvider]'s float samples. */
        fun floatsToPcm16(samples: FloatArray): ShortArray =
            ShortArray(samples.size) { i -> (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }

        /** `TransmissionEntity.audioFormat` is written as e.g. "flac/16k/mono" by `RealSegmentSink`. */
        fun sampleRateFromFormat(audioFormat: String): Int {
            val kilohertz = Regex("""(\d+)k""").find(audioFormat)?.groupValues?.get(1)?.toIntOrNull()
            return (kilohertz ?: 16) * 1_000
        }
    }
}
