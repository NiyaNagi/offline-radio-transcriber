package org.ort.pipeline.archive

import org.ort.capture.android.archive.ArchiveChunkMeta
import org.ort.capture.android.archive.ArchiveReader
import org.ort.segment.RecordingSegmentSink
import org.ort.segment.SegmentConfig
import org.ort.segment.SegmentRecord
import org.ort.segment.Segmenter
import org.ort.segment.Vad

/**
 * technical design §6.1's continuous-archive mode, closing the loop for AC-96: replays a
 * session archived by `:capture-android`'s `ContinuousArchiveWriter` through a fresh
 * [Segmenter] with a **different** [SegmentConfig], and produces a new, valid set of
 * transmissions. `:capture-android` may not depend on `:segment` (module graph) — `:pipeline`
 * is where these two meet.
 */
public object ReSegmenter {

    public fun reSegment(chunks: List<ArchiveChunkMeta>, config: SegmentConfig, vad: Vad): List<SegmentRecord> {
        val sink = RecordingSegmentSink()
        val ordered = chunks.sortedBy { it.startSample }
        val originSample = ordered.firstOrNull()?.startSample ?: 0L
        val segmenter = Segmenter(config, vad, sink, originSample = originSample)
        for ((_, samples) in ArchiveReader(ordered).readAll()) {
            segmenter.onAudio(FloatArray(samples.size) { samples[it] / SHORT_TO_FLOAT_SCALE })
        }
        segmenter.finish()
        return sink.records
    }

    private const val SHORT_TO_FLOAT_SCALE = 32768.0f
}
