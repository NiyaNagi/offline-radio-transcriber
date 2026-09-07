package org.ort.segment

import org.ort.segment.fake.ScriptedVad

/** A distinctive, exactly-sliceable signal: sample value == its absolute index (mod short range). */
internal fun rampSignal(totalSamples: Int): FloatArray = FloatArray(totalSamples) { it.toFloat() }

/** Feeds [signal] through [segmenter] in [FrameSpec.SIZE]-sample chunks, then [Segmenter.finish]. */
internal fun runToCompletion(segmenter: Segmenter, signal: FloatArray) {
    var i = 0
    while (i < signal.size) {
        val n = minOf(FrameSpec.SIZE, signal.size - i)
        segmenter.onAudio(signal.copyOfRange(i, i + n))
        i += n
    }
    segmenter.finish()
}

/** Builds a [ScriptedVad] and a matching total-sample count for a list of speech regions (ms). */
internal fun regionScript(totalMs: Int, vararg regionsMs: IntRange): Pair<ScriptedVad, Int> {
    val totalSamples = totalMs * FrameSpec.SAMPLE_RATE / 1000
    return ScriptedVad.fromRegions(totalMs, regionsMs.toList(), FrameSpec.DURATION_MS) to totalSamples
}
