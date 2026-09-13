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

    /**
     * FR-SEG-5: a rig-squelch-gated interval (fusion active — [SegmentRecord.rigSquelchFusionApplied]
     * where both edges were fusion-decided) that VAD found **no speech inside at all** — squelch
     * opened and closed, but nothing was said (e.g. a carrier with no modulation, a stray key-up).
     * Recorded and retained exactly like [REJECTED_TOO_SHORT] — **never silently dropped**
     * (constitution III) — with no ASR model invoked, since there is nothing for one to transcribe.
     */
    REJECTED_NO_SPEECH,
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

    /**
     * FR-SEG-5: rig squelch closed while fusion was active for this interval — a genuine
     * measurement, not a VAD inference (technical rationale: this is the entire point of fusion,
     * converting the close from an inference into an observed fact). Never produced when no
     * [SquelchGate] is wired, or when squelch state was not yet known at the time this interval
     * opened — see [Segmenter]'s own kdoc for exactly when fusion engages.
     */
    SQUELCH_CLOSE,

    /**
     * R-1062 follow-up (FR-SEG-5, constitution IV "capture never lies"): squelch authority was
     * **lost** — the rig disconnected, its transport was lost, or it went stale — while a
     * squelch-gated segment was open. Kept generous post-roll exactly like [SQUELCH_CLOSE]
     * (constitution never clips a callsign for want of it), but it is honestly a **different**
     * fact: not a measurement of the transmission's real end, an administrative cut forced by
     * losing the instrument that was measuring it. [SegmentRecord.rigSquelchFusionApplied] is
     * always `false` for this reason — see [SquelchUpdate.Loss]'s own kdoc for the producer side.
     */
    RIG_LOST,
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
    /**
     * FR-SEG-5 / FR-SEG-10: whether rig squelch fusion actually decided **both** of this
     * segment's edges — the start (a squelch-open event, not a VAD trigger) and the end (a
     * squelch-close event, not VAD hangover or a forced split). `false` whenever either edge was
     * decided some other way, so a segment that started on a real squelch open but was cut short
     * by [SegmentCloseReason.MAX_DURATION] — or whose rig went silent before ever reporting a
     * close — reports this honestly as `false` rather than claiming a fusion decision that only
     * half happened (constitution I: an attribution without its confidence state is a bug, and
     * the same discipline applies to this boundary's own provenance). Always `false` when no
     * [SquelchGate] was wired at all — the pre-FR-SEG-5 path, unchanged.
     */
    val rigSquelchFusionApplied: Boolean = false,
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
