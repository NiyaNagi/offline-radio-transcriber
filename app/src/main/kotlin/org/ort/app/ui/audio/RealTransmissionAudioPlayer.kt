package org.ort.app.ui.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.PlaybackParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ort.core.PassId
import org.ort.data.OrtDatabase
import org.ort.data.entity.WorkQueueItemEntity
import org.ort.data.entity.WorkQueueState
import org.ort.pipeline.passb.FlacSegmentAudioProvider
import java.util.concurrent.ConcurrentHashMap

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

    /** Total frames written to [track] by the current [play] call — [positionFraction]'s denominator. */
    private var totalFrames: Int = 0

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
        totalFrames = 0
    }

    /** R-054: pauses without releasing — [resume] continues from the same [AudioTrack.getPlaybackHeadPosition]. */
    override fun pause() {
        track?.let { runCatching { it.pause() } }
    }

    override fun resume() {
        track?.let { runCatching { it.play() } }
    }

    /**
     * `Detail-Playback.dc.html`: "drag anywhere on the waveform to scrub". [AudioTrack] only
     * accepts [AudioTrack.setPlaybackHeadPosition] while stopped or paused, so this pauses first
     * when playing and resumes afterward — a caller sees no state change beyond the new position.
     */
    override fun seekToFraction(fraction: Float) {
        val current = track ?: return
        val frame = (fraction.coerceIn(0f, 1f) * totalFrames).toInt()
        val wasPlaying = current.playState == AudioTrack.PLAYSTATE_PLAYING
        runCatching {
            if (wasPlaying) current.pause()
            current.setPlaybackHeadPosition(frame)
            if (wasPlaying) current.play()
        }
    }

    /** `1x`/`0.75x`/`0.5x` — [PlaybackParams.setPitch] at `1f` keeps pitch constant while speed changes. */
    override fun setRate(rate: PlaybackRate) {
        val current = track ?: return
        runCatching {
            current.playbackParams = PlaybackParams().setSpeed(rate.multiplier).setPitch(1f)
        }
    }

    override fun positionFraction(): Float {
        val current = track ?: return 0f
        if (totalFrames <= 0) return 0f
        return (current.playbackHeadPosition.toFloat() / totalFrames).coerceIn(0f, 1f)
    }

    override fun isPlaying(): Boolean = track?.playState == AudioTrack.PLAYSTATE_PLAYING

    /** Wraps a cached result so `null` (no audio / decode failure) is itself a real, storable
     * cache entry — [ConcurrentHashMap] cannot hold a `null` value directly. */
    private class CacheEntry(val summary: WaveformSummary?)
    private val waveformCache = ConcurrentHashMap<String, CacheEntry>()

    override suspend fun waveformSummary(transmissionId: String): WaveformSummary? {
        waveformCache[transmissionId]?.let { return it.summary }
        val computed = withContext(Dispatchers.IO) { computeWaveformSummary(transmissionId) }
        waveformCache[transmissionId] = CacheEntry(computed)
        return computed
    }

    /** The exact same decode [play] uses ([FlacSegmentAudioProvider], the codec [play] itself
     * reuses) — never a second decode path that could silently disagree with it (constitution III,
     * this file's own class doc). */
    private suspend fun computeWaveformSummary(transmissionId: String): WaveformSummary? {
        val db = OrtDatabase.create(context.applicationContext)
        val entity = db.transmissionDao().getById(transmissionId) ?: return null
        val audioFile = java.io.File(context.filesDir, entity.audioPath())
        if (!audioFile.isFile) return null
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
            return null
        }
        return WaveformSummaryComputer.summarize(decoded.samples)
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
        totalFrames = pcm.size
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
