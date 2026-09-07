package org.ort.segment

/**
 * The capture-side segmenter: an explicit `IDLE → SPEECH → HANGOVER → CLOSED` state machine
 * over VAD frames (technical design §6).
 *
 * - Audio is written to the [SegmentSink] **incrementally** once a segment is confirmed, never
 *   buffered for its whole length — a 60-second stuck carrier costs one open writer.
 * - Every emitted segment carries generous pre-roll (from an internal rolling buffer) and
 *   post-roll past the VAD close (FR-SEG-4, FR-SEG-8 → AC-3, AC-95).
 * - A segment reaching [SegmentConfig.maxSegmentMs] is force-split (FR-SEG-3 → AC-70).
 * - A run of speech shorter than [SegmentConfig.minSpeechMs] is recorded
 *   `REJECTED_TOO_SHORT` with **no ASR model invoked** (FR-SEG-6 → AC-72).
 *
 * **The constructor takes no [org.ort.core.Tier] and cannot be given one** (FR-SEG-7,
 * CON-SEG-1 → AC-94). See [SegmentConfig].
 */
public class Segmenter(
    private val config: SegmentConfig,
    private val vad: Vad,
    private val sink: SegmentSink,
    private val originSample: Long = 0L,
) {
    init {
        require(config.sampleRate == FrameSpec.SAMPLE_RATE) {
            "segmenter runs at ${FrameSpec.SAMPLE_RATE} Hz; resample upstream"
        }
    }

    private enum class State { IDLE, TENTATIVE, SPEECH, HANGOVER }

    private val frameSamples = FrameSpec.SIZE
    private val frameMs = FrameSpec.DURATION_MS
    private val preRollSamples = config.msToSamples(config.preRollMs)
    private val postRollSamples = config.msToSamples(config.postRollMs)
    private val maxSegmentSamples = config.msToSamples(config.maxSegmentMs).toLong()

    private val preRoll = FloatRing(preRollSamples)
    private val carry = ArrayList<Float>(frameSamples)
    private var consumed = 0L

    private var state = State.IDLE
    private var nextId = 0

    // per-tentative / per-segment scratch
    private val tentativeFrames = ArrayList<FloatArray>()
    private var triggerSample = 0L
    private var speechMs = 0
    private var writer: SegmentWriter? = null
    private var segId = SegmentId(0)
    private var segStartSample = 0L
    private var segVadStartSample = 0L
    private var hangoverStartSample = 0L
    private var silenceMs = 0
    private val hangoverBuf = ArrayList<FloatArray>()

    /** Absolute sample position of the next sample to be fed. */
    public fun position(): Long = originSample + consumed

    /** Feed contiguous mono 16 kHz audio. Any length; framed internally. */
    public fun onAudio(pcm: FloatArray) {
        for (s in pcm) carry.add(s)
        while (carry.size >= frameSamples) {
            val frame = FloatArray(frameSamples) { carry[it] }
            carry.subList(0, frameSamples).clear()
            val frameStart = originSample + consumed
            consumed += frameSamples
            handleFrame(frame, frameStart)
        }
    }

    /** End of stream: close whatever is open. */
    public fun finish() {
        when (state) {
            State.TENTATIVE -> emitRejected(endSample = originSample + consumed)
            State.SPEECH -> closeSpeech(vadEnd = originSample + consumed, end = originSample + consumed, forced = false)
            State.HANGOVER -> {
                // Computed once, before flushHangover() clears hangoverBuf — calling
                // bufferedHangoverSamples() a second time after the clear silently sees 0 and
                // under-reports endSample versus what was actually appended to the sink (found
                // by adversarial review: a stream ending mid-hangover, before minSilenceMs
                // elapses, produced a SegmentRecord whose endSample - startSample didn't match
                // its real sampleCount).
                val flushed = minOf(postRollSamples.toLong(), bufferedHangoverSamples())
                flushHangover(limitSamples = flushed)
                closeSpeech(
                    vadEnd = hangoverStartSample,
                    end = hangoverStartSample + flushed,
                    forced = false,
                )
            }
            State.IDLE -> Unit
        }
        state = State.IDLE
    }

    private fun handleFrame(frame: FloatArray, frameStart: Long) {
        val wasIdle = state == State.IDLE
        val decision = vad.accept(frame)
        when (state) {
            State.IDLE -> if (decision == VadDecision.SPEECH) beginTentative(frame, frameStart)
            State.TENTATIVE -> tentative(frame, frameStart, decision)
            State.SPEECH -> speech(frame, frameStart, decision)
            State.HANGOVER -> hangover(frame, frameStart, decision)
        }
        // Only audio that stayed idle backs the pre-roll ring — anything claimed by a tentative
        // or open segment is already written via tentativeFrames/hangoverBuf/the writer, and
        // must not also be replayed out of the ring (that would duplicate it in the output).
        if (wasIdle && decision == VadDecision.SILENCE) preRoll.push(frame)
    }

    private fun beginTentative(frame: FloatArray, frameStart: Long) {
        state = State.TENTATIVE
        triggerSample = frameStart
        speechMs = frameMs
        tentativeFrames.clear()
        tentativeFrames.add(frame)
    }

    private fun tentative(frame: FloatArray, frameStart: Long, decision: VadDecision) {
        if (decision == VadDecision.SILENCE) {
            emitRejected(endSample = frameStart)
            return
        }
        tentativeFrames.add(frame)
        speechMs += frameMs
        if (speechMs >= config.minSpeechMs) confirm()
    }

    private fun confirm() {
        segId = SegmentId(nextId++)
        segVadStartSample = triggerSample
        segStartSample = maxOf(originSample, triggerSample - preRollSamples)
        val w = sink.open(segId, segStartSample)
        writer = w
        w.append(preRoll.snapshot(atMost = (triggerSample - segStartSample).toInt()))
        for (f in tentativeFrames) w.append(f)
        tentativeFrames.clear()
        state = State.SPEECH
    }

    private fun speech(frame: FloatArray, frameStart: Long, decision: VadDecision) {
        val w = writer ?: return
        if (decision == VadDecision.SILENCE) {
            // Deferred to hangoverBuf, not appended here — flushHangover() writes it exactly
            // once, either as trimmed post-roll on close or in full if speech resumes.
            state = State.HANGOVER
            hangoverStartSample = frameStart
            silenceMs = frameMs
            hangoverBuf.clear()
            hangoverBuf.add(frame.copyOf())
            return
        }
        w.append(frame)
        val end = frameStart + frameSamples
        if (end - segStartSample >= maxSegmentSamples) {
            closeSpeech(vadEnd = end, end = end, forced = true)
            segId = SegmentId(nextId++)
            segStartSample = end
            segVadStartSample = end
            writer = sink.open(segId, segStartSample)
            state = State.SPEECH
        }
    }

    private fun hangover(frame: FloatArray, frameStart: Long, decision: VadDecision) {
        if (decision == VadDecision.SPEECH) {
            val w = writer!!
            for (f in hangoverBuf) w.append(f)
            hangoverBuf.clear()
            state = State.SPEECH
            silenceMs = 0
            speech(frame, frameStart, VadDecision.SPEECH)
            return
        }
        hangoverBuf.add(frame.copyOf())
        silenceMs += frameMs
        if (silenceMs >= config.minSilenceMs) {
            flushHangover(limitSamples = postRollSamples.toLong())
            closeSpeech(
                vadEnd = hangoverStartSample,
                end = hangoverStartSample + postRollSamples,
                forced = false,
            )
        }
    }

    private fun bufferedHangoverSamples(): Long = hangoverBuf.sumOf { it.size.toLong() }

    private fun flushHangover(limitSamples: Long) {
        val w = writer ?: return
        var remaining = limitSamples
        for (f in hangoverBuf) {
            if (remaining <= 0) break
            if (f.size <= remaining) {
                w.append(f)
                remaining -= f.size
            } else {
                w.append(f.copyOf(remaining.toInt()))
                remaining = 0
            }
        }
        hangoverBuf.clear()
    }

    private fun closeSpeech(vadEnd: Long, end: Long, forced: Boolean) {
        val w = writer ?: return
        w.close(
            SegmentRecord(
                id = segId,
                startSample = segStartSample,
                endSample = end,
                vadStartSample = segVadStartSample,
                vadEndSample = vadEnd,
                sampleCount = 0,
                outcome = SegmentOutcome.SPEECH,
                forcedSplit = forced,
            ),
        )
        writer = null
        if (!forced) state = State.IDLE
    }

    private fun emitRejected(endSample: Long) {
        val id = SegmentId(nextId++)
        val start = maxOf(originSample, triggerSample - preRollSamples)
        val w = sink.open(id, start)
        w.append(preRoll.snapshot(atMost = (triggerSample - start).toInt()))
        for (f in tentativeFrames) w.append(f)
        w.close(
            SegmentRecord(
                id = id,
                startSample = start,
                endSample = endSample,
                vadStartSample = triggerSample,
                vadEndSample = endSample,
                sampleCount = 0,
                outcome = SegmentOutcome.REJECTED_TOO_SHORT,
            ),
        )
        tentativeFrames.clear()
        state = State.IDLE
    }
}

/** A tiny fixed-capacity rolling buffer of the most recent samples — the segmenter's pre-roll. */
internal class FloatRing(capacity: Int) {
    private val cap = maxOf(1, capacity)
    private val buf = FloatArray(cap)
    private var count = 0
    private var head = 0

    fun push(frame: FloatArray) {
        for (s in frame) {
            buf[head] = s
            head = (head + 1) % cap
            if (count < cap) count++
        }
    }

    /** The most recent [atMost] samples (or fewer if less has been seen), oldest first. */
    fun snapshot(atMost: Int): FloatArray {
        val n = minOf(atMost.coerceAtLeast(0), count)
        val out = FloatArray(n)
        var idx = (head - n + cap * 2) % cap
        for (i in 0 until n) {
            out[i] = buf[idx]
            idx = (idx + 1) % cap
        }
        return out
    }
}
