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
 * Why a segment (or a too-short candidate) stopped growing — FR-OBS-1's "VAD statistics", the
 * hangover/close reason half of it (Q20). One of the genuinely three ways [Segmenter] ever closes
 * anything; never invented per-instance, always the exact branch that fired.
 */
public enum class SegmentCloseReason {
    /** The VAD's hangover/silence window elapsed naturally, or (for a rejected candidate) the VAD
     * flipped back to silence before [SegmentConfig.minSpeechMs] was reached. */
    SILENCE,

    /** [SegmentConfig.maxSegmentMs] forced this cut, not a silence (AC-70). */
    MAX_DURATION,

    /** The audio stream ended ([Segmenter.finish]) while this segment or candidate was still open. */
    END_OF_STREAM,
}

/**
 * The closed record of one segment. Boundaries are reported twice on purpose:
 * [vadStartSample]/[vadEndSample] are the keying edges (what AC-69 measures), while
 * [startSample]/[endSample] include the generous pre- and post-roll (FR-SEG-8 → AC-95).
 *
 * [closeReason], [vadFrameCount] and [vadSpeechFrameCount] are FR-OBS-1's per-transmission VAD
 * statistics (Q20): the honest facts the segmenter itself has about *why* it stopped and *how much
 * of the window the VAD called speech* — a speech-frame ratio is [vadSpeechFrameCount] over
 * [vadFrameCount], left to the reader to divide rather than pre-computed and rounded here.
 */
public data class SegmentRecord(
    val id: SegmentId,
    val startSample: Long,
    val endSample: Long,
    val vadStartSample: Long,
    val vadEndSample: Long,
    val sampleCount: Long,
    val outcome: SegmentOutcome,
    /** `true` when [SegmentConfig.maxSegmentMs] forced this cut, not a silence (AC-70). Equivalent
     * to `closeReason == SegmentCloseReason.MAX_DURATION`, kept as its own field since it predates
     * [closeReason] and existing callers already match on it. */
    val forcedSplit: Boolean = false,
    val closeReason: SegmentCloseReason,
    /** Total VAD frames evaluated over this segment's (or candidate's) own active window — from
     * the frame that triggered it to the frame that closed it, inclusive. */
    val vadFrameCount: Int,
    /** Of [vadFrameCount], how many the VAD called [VadDecision.SPEECH]. */
    val vadSpeechFrameCount: Int,
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
