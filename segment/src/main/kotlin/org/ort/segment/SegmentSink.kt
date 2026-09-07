package org.ort.segment

/** A sequential id for a segment within one [Segmenter] run. Maps to a `TransmissionId` downstream. */
@JvmInline
public value class SegmentId(public val index: Int) {
    override fun toString(): String = "seg-$index"
}

/** Why a segment closed. */
public enum class SegmentOutcome {
    /** Contained speech and was emitted for processing. */
    SPEECH,

    /** Below [SegmentConfig.minSpeechMs]; recorded, audio retained, **no ASR model invoked** (FR-SEG-6 → AC-72). */
    REJECTED_TOO_SHORT,
}

/**
 * The closed record of one segment. Boundaries are reported twice on purpose:
 * [vadStartSample]/[vadEndSample] are the keying edges (what AC-69 measures), while
 * [startSample]/[endSample] include the generous pre- and post-roll (FR-SEG-8 → AC-95).
 */
public data class SegmentRecord(
    val id: SegmentId,
    val startSample: Long,
    val endSample: Long,
    val vadStartSample: Long,
    val vadEndSample: Long,
    val sampleCount: Long,
    val outcome: SegmentOutcome,
    /** `true` when [SegmentConfig.maxSegmentMs] forced this cut, not a silence (AC-70). */
    val forcedSplit: Boolean = false,
)

/**
 * Where segment audio is written **incrementally as it arrives** — never buffered (technical
 * design §6). A 60-second stuck carrier costs one open writer, not 60 seconds of heap.
 */
public interface SegmentSink {
    public fun open(id: SegmentId, startSample: Long): SegmentWriter
}

public interface SegmentWriter {
    /** Append decoded mono samples. Called many times between [open] and [close]. */
    public fun append(pcm: FloatArray)

    /** Finalise; the returned record's [SegmentRecord.sampleCount] is the total appended. */
    public fun close(record: SegmentRecord): SegmentRecord
}

/**
 * An in-memory [SegmentSink] that keeps every segment's audio for assertions — the behavioural
 * fake for everything downstream of the segmenter (constitution II).
 */
public class RecordingSegmentSink : SegmentSink {

    private val audio = LinkedHashMap<SegmentId, MutableList<Float>>()
    private val _records = mutableListOf<SegmentRecord>()

    public val records: List<SegmentRecord> get() = _records

    public fun audioFor(id: SegmentId): FloatArray = (audio[id] ?: emptyList()).toFloatArray()

    override fun open(id: SegmentId, startSample: Long): SegmentWriter {
        val buf = audio.getOrPut(id) { mutableListOf() }
        return object : SegmentWriter {
            override fun append(pcm: FloatArray) {
                for (s in pcm) buf.add(s)
            }

            override fun close(record: SegmentRecord): SegmentRecord {
                val finalised = record.copy(sampleCount = buf.size.toLong())
                _records.add(finalised)
                return finalised
            }
        }
    }
}
