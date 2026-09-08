package org.ort.app.ui.audio

import org.ort.app.ui.components.WaveformBar
import kotlin.math.abs

/**
 * R-181, `Detail-Playback.dc.html`'s own gapped-bar waveform: a real summary of a transmission's
 * decoded retained audio, computed once and cached by [TransmissionAudioPlayer.waveformSummary]'s
 * real implementation ([RealTransmissionAudioPlayer]). [bars] feeds
 * [org.ort.app.ui.components.WaveformCard] directly, in place of the empty bar list every state
 * used before this — the board's own artboard shows a real, non-uniform shape, and constitution I
 * forbids standing in a flat or fabricated one for it once real amplitude data exists to draw from.
 */
public data class WaveformSummary(public val bars: List<WaveformBar>)

/**
 * The pure bucketing arithmetic, kept separate from the real decode/`:data` read
 * ([RealTransmissionAudioPlayer.waveformSummary]) so it is directly unit-testable against a
 * synthetic PCM buffer — no Robolectric, no database, no codec.
 *
 * [bucketCount] (default [BUCKET_COUNT]) is a data-resolution choice: `Detail-Playback.dc.html`
 * itself specifies no fixed bar count — `WaveformCardContent`'s own `Canvas` sizes bars to fit
 * whatever list it is given (`(size.width - gap * (bars.size - 1)) / bars.size`) — 96 buckets reads
 * as a smooth waveform at the card's own on-screen width without over-resolving past what a mono
 * 16 kHz segment's amplitude envelope actually carries.
 *
 * Each bucket's value is the **peak absolute amplitude** of the samples that fall in it — a peak
 * read, not an average/RMS, is the conventional waveform-display statistic, and it is the one that
 * survives a mostly-quiet segment with one loud syllable without smoothing it away — normalised
 * against the single loudest bucket in the whole segment, so the tallest real bar always renders at
 * full height (the same convention every real waveform UI uses; never a bar taller than the actual
 * loudest moment). [WaveformBar.isSpeech] is a second real, computed signal, not a fabricated VAD
 * result: a bucket whose peak clears [SPEECH_THRESHOLD_FRACTION] of the segment's own peak reads as
 * speech, matching the amber/green "wave/speech vs quiet" colour split
 * [org.ort.app.ui.components.WaveformCard] already draws — a real energy read, honestly weaker than
 * a true VAD decision, but never invented from nothing.
 */
public object WaveformSummaryComputer {
    public const val BUCKET_COUNT: Int = 96

    /** `WaveformBar.heightFraction`'s own floor (`Inspection.kt`'s render clamps to this too) — a
     * silent bucket still draws a visible sliver, never a bar of zero height. */
    private const val MIN_BAR_HEIGHT: Float = 0.08f
    private const val SPEECH_THRESHOLD_FRACTION: Float = 0.12f

    /** `null` only for a genuinely empty sample buffer or a non-positive bucket count — every real
     * decoded segment (even total silence) still yields [bucketCount] real, honestly-flat bars. */
    public fun summarize(samples: FloatArray, bucketCount: Int = BUCKET_COUNT): WaveformSummary? {
        if (samples.isEmpty() || bucketCount <= 0) return null
        val peaks = peaksPerBucket(samples, bucketCount)
        val overallPeak = peaks.max()
        if (overallPeak <= 0f) {
            return WaveformSummary(List(bucketCount) { WaveformBar(heightFraction = MIN_BAR_HEIGHT, isSpeech = false) })
        }
        val bars = peaks.map { peak ->
            val fraction = (peak / overallPeak).coerceIn(0f, 1f)
            WaveformBar(
                heightFraction = fraction.coerceAtLeast(MIN_BAR_HEIGHT),
                isSpeech = fraction >= SPEECH_THRESHOLD_FRACTION,
            )
        }
        return WaveformSummary(bars)
    }

    private fun peaksPerBucket(samples: FloatArray, bucketCount: Int): FloatArray {
        val bucketSize = samples.size.toDouble() / bucketCount
        return FloatArray(bucketCount) { bucket ->
            val start = (bucket * bucketSize).toInt()
            val end = ((bucket + 1) * bucketSize).toInt().coerceAtMost(samples.size).coerceAtLeast(start + 1)
            var peak = 0f
            for (i in start until end) {
                val magnitude = abs(samples[i])
                if (magnitude > peak) peak = magnitude
            }
            peak
        }
    }
}
