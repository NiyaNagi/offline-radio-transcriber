package org.ort.segment

/**
 * The capture-side segmenter: an explicit `IDLE → SPEECH → HANGOVER → CLOSED` state machine
 * over VAD frames (technical design §6), extended by FR-SEG-5 with a parallel
 * `IDLE → SQUELCH_OPEN → SQUELCH_POSTROLL → CLOSED` path driven by rig squelch instead of VAD.
 *
 * - Audio is written to the [SegmentSink] **incrementally** once a segment is confirmed, never
 *   buffered for its whole length — a 60-second stuck carrier costs one open writer.
 * - Every emitted segment carries generous pre-roll (from an internal rolling buffer) and
 *   post-roll past the VAD close (FR-SEG-4, FR-SEG-8 → AC-3, AC-95).
 * - A segment reaching [SegmentConfig.maxSegmentMs] is force-split (FR-SEG-3 → AC-70).
 * - A run of speech shorter than [SegmentConfig.minSpeechMs] is recorded
 *   `REJECTED_TOO_SHORT` with **no ASR model invoked** (FR-SEG-6 → AC-72).
 *
 * **FR-SEG-5 fusion.** When constructed with a non-null [squelchGate], **rig squelch is
 * authoritative for boundaries; VAD is authoritative only for whether the gated interval
 * contains speech.** Concretely, once the first [SquelchUpdate.Transition] has been drained
 * (before that, or with no [squelchGate] at all, this class behaves exactly as it always has — VAD-only
 * boundaries, [SegmentRecord.rigSquelchFusionApplied] always `false`, matching "no squelch
 * capability, or rig disconnected" and "if rig state is late, segmentation proceeds on VAD"):
 * - Squelch open (while [State.IDLE]) starts a segment immediately, with the same [preRollMs]
 *   generosity a VAD trigger gets, regardless of what VAD says about that same frame — squelch
 *   closed is likewise authoritative for staying idle, so a stray VAD trigger while squelch is
 *   known closed never opens anything (this is exactly what stops squelch-tail hallucination).
 * - While the squelch-gated interval is open, VAD only tallies whether any frame was speech; on
 *   close, an interval with **no** speech frames is recorded [SegmentOutcome.REJECTED_NO_SPEECH]
 *   — never silently dropped (constitution III) — one with any speech is [SegmentOutcome.SPEECH].
 * - Squelch close starts the same [postRollMs] window FR-SEG-8 gives a VAD hangover close,
 *   trimmed to the stream's own end exactly as the VAD path already is. A brief re-open during
 *   that window resumes the **same** segment rather than splitting it, mirroring VAD hangover's
 *   own "speech resumed" reunification.
 * - A boundary decided by anything else — [SegmentCloseReason.MAX_DURATION]'s forced split, or
 *   [SegmentCloseReason.END_OF_STREAM] — is honestly *not* a fusion decision, so
 *   [SegmentRecord.rigSquelchFusionApplied] is `true` only when **both** the segment's open and
 *   its close were squelch-decided.
 * - **R-1062 follow-up (constitution IV): squelch authority can be *lost*, not just closed.**
 *   [SquelchGate.markUnknown] reverts [squelchOpen] to `null` — exactly the pre-first-transition
 *   "not yet known" state — so the very next frame falls back to VAD-only, whether squelch was
 *   open or closed at the moment authority was lost. A segment that was genuinely open when this
 *   happens is not left running silently forever: it closes at the loss point with the same
 *   generous post-roll a real close gets, tagged [SegmentCloseReason.RIG_LOST] (never
 *   [SegmentCloseReason.SQUELCH_CLOSE], and [SegmentRecord.rigSquelchFusionApplied] always
 *   `false`) — see [handleSquelchLoss]'s own kdoc. *Producing* that signal (a transport lost, a
 *   disconnect, or squelch going stale past a bound) is `:pipeline`'s job, never this class's —
 *   see [org.ort.segment.SquelchGate]'s own kdoc for the split of responsibility. FR-SEG-3's own
 *   maximum-length safety net remains the backstop if a loss signal is ever missed entirely: a
 *   squelch-gated interval that somehow never hears either a close or a loss still cannot run
 *   unbounded, and that forced cut is likewise never attributed to fusion.
 *
 * **The constructor takes no [org.ort.core.Tier] and cannot be given one** (FR-SEG-7,
 * CON-SEG-1 → AC-94). See [SegmentConfig].
 */
public class Segmenter(
    private val config: SegmentConfig,
    private val vad: Vad,
    private val sink: SegmentSink,
    /** FR-SEG-5: `null` (the default) is the pre-fusion, VAD-only segmenter. See this class's own
     * kdoc for the exact fusion rule once one is supplied. */
    private val squelchGate: SquelchGate? = null,
    private val originSample: Long = 0L,
) {
    init {
        require(config.sampleRate == FrameSpec.SAMPLE_RATE) {
            "segmenter runs at ${FrameSpec.SAMPLE_RATE} Hz; resample upstream"
        }
    }

    private enum class State { IDLE, TENTATIVE, SPEECH, HANGOVER, SQUELCH_OPEN, SQUELCH_POSTROLL }

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

    // FR-SEG-5 fusion scratch. `squelchOpen == null` means "not yet known" -- the honest state
    // before the first SquelchUpdate.Transition ever arrives, OR after a SquelchUpdate.Loss
    // reverts it (R-1062 follow-up) -- in which this class behaves exactly as the pre-fusion
    // VAD-only segmenter (see the dispatch in handleFrame()).
    private var squelchOpen: Boolean? = null
    private var openedBySquelch = false
    private var closedBySquelch = false
    private var squelchCloseVadEnd = 0L
    private val squelchPostRollBuf = ArrayList<FloatArray>()

    // R-1062 follow-up: which SegmentCloseReason the current SQUELCH_POSTROLL window is heading
    // toward -- SQUELCH_CLOSE for a genuine squelch close, RIG_LOST when authority was lost while
    // the segment was open (see beginSquelchPostRollFromLoss()). MAX_DURATION always overrides it
    // (squelchPostRollFrame()'s own hitMax check), so the default value here is never observed
    // unless one of the two entry points below sets it first.
    private var postRollCloseReason = SegmentCloseReason.SQUELCH_CLOSE

    // FR-OBS-1 (Q20): tallied per active window (TENTATIVE/SPEECH/HANGOVER/SQUELCH_OPEN/
    // SQUELCH_POSTROLL), reset to 0 the instant a segment or rejected candidate closes -- see
    // handleFrame()'s own comment for exactly which frames count.
    private var activeFrameCount = 0
    private var activeSpeechFrameCount = 0

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
            State.TENTATIVE -> emitRejected(
                endSample = originSample + consumed,
                closeReason = SegmentCloseReason.END_OF_STREAM,
            )
            State.SPEECH -> closeSpeech(
                vadEnd = originSample + consumed,
                end = originSample + consumed,
                closeReason = SegmentCloseReason.END_OF_STREAM,
            )
            State.HANGOVER -> {
                // Computed once, before flushHangover() clears hangoverBuf — calling
                // bufferedHangoverSamples() a second time after the clear silently sees 0 and
                // under-reports endSample versus what was actually appended to the sink (found
                // by adversarial review: a stream ending mid-hangover, before minSilenceMs
                // elapsed, produced a SegmentRecord whose endSample - startSample didn't match
                // its real sampleCount).
                val flushed = minOf(postRollSamples.toLong(), bufferedSamples(hangoverBuf))
                flushBuffered(hangoverBuf, flushed)
                closeSpeech(
                    vadEnd = hangoverStartSample,
                    end = hangoverStartSample + flushed,
                    closeReason = SegmentCloseReason.END_OF_STREAM,
                )
            }
            State.SQUELCH_OPEN -> closeSquelchSegment(
                vadEnd = originSample + consumed,
                end = originSample + consumed,
                closeReason = SegmentCloseReason.END_OF_STREAM,
                closedBySquelch = false,
            )
            State.SQUELCH_POSTROLL -> {
                val flushed = minOf(postRollSamples.toLong(), bufferedSamples(squelchPostRollBuf))
                flushBuffered(squelchPostRollBuf, flushed)
                closeSquelchSegment(
                    vadEnd = squelchCloseVadEnd,
                    end = squelchCloseVadEnd + flushed,
                    closeReason = SegmentCloseReason.END_OF_STREAM,
                    closedBySquelch = false,
                )
            }
            State.IDLE -> Unit
        }
        state = State.IDLE
    }

    private fun handleFrame(frame: FloatArray, frameStart: Long) {
        val wasIdle = state == State.IDLE
        val decision = vad.accept(frame)

        drainSquelchTransitions(frameStart)
        tallyActiveWindow(decision)

        when (state) {
            State.IDLE -> when {
                squelchOpen == true -> beginSquelchSegment(frame, frameStart)
                // FR-SEG-5: squelch known closed is authoritative; VAD is not consulted for opening.
                squelchOpen == false -> Unit
                // squelch unknown: pre-fusion VAD-only behaviour, unchanged.
                else -> if (decision == VadDecision.SPEECH) beginTentative(frame, frameStart)
            }
            State.TENTATIVE -> tentative(frame, frameStart, decision)
            State.SPEECH -> speech(frame, frameStart, decision)
            State.HANGOVER -> hangover(frame, frameStart, decision)
            State.SQUELCH_OPEN -> squelchOpenFrame(frame, frameStart)
            State.SQUELCH_POSTROLL -> squelchPostRollFrame(frame, frameStart)
        }
        // Only audio that stayed idle for this whole frame backs the pre-roll ring -- anything
        // claimed by a tentative/open segment or squelch-gated interval is already written via
        // tentativeFrames/hangoverBuf/squelchPostRollBuf/the writer, and must not also be replayed
        // out of the ring (that would duplicate it in the output). Equivalent to the pre-fusion
        // `decision == SILENCE` guard when squelch is unknown or absent (IDLE only ever leaves
        // IDLE on a VAD SPEECH trigger in that case) and correctly generalises it for squelch
        // known-closed, where a spurious VAD SPEECH frame must still be treated as unclaimed.
        if (wasIdle && state == State.IDLE) preRoll.push(frame)
    }

    /**
     * FR-SEG-5 / FR-RUN-1: a plain, non-suspending queue drain -- never a suspension point, never
     * a lock the rig's own coroutine could contend for. Updates are applied in arrival order; the
     * last one at or before this frame's own end is what this frame's decision uses (a squelch
     * flap entirely inside one 32 ms frame is not expected to be separately observable at this
     * granularity, and none of FR-SEG-5's callers need it to be).
     *
     * R-1062 follow-up: a [SquelchUpdate.Loss] is handled inline, in the same order as every
     * [SquelchUpdate.Transition] -- both [squelchOpen] and (if a segment was open) the segment's
     * own close are decided at the point in the batch the loss actually occurred, never
     * retroactively.
     */
    private fun drainSquelchTransitions(frameStart: Long) {
        val gate = squelchGate ?: return
        for (update in gate.drainBefore(frameStart + frameSamples)) {
            when (update) {
                is SquelchUpdate.Transition -> squelchOpen = update.open
                is SquelchUpdate.Loss -> handleSquelchLoss(frameStart)
            }
        }
    }

    /**
     * R-1062 follow-up (FR-SEG-5, constitution IV): squelch authority is gone as of [frameStart]
     * -- reverts to the honest "not yet known" state exactly as if no transition had ever
     * arrived, so [handleFrame]'s own IDLE dispatch falls back to VAD immediately, on the very
     * next frame. If a squelch-gated segment was actually open when authority was lost, it is not
     * left running silently: it is cut here, with the same generous post-roll a genuine squelch
     * close gets, tagged [SegmentCloseReason.RIG_LOST] rather than [SegmentCloseReason.SQUELCH_CLOSE]
     * so nothing downstream can mistake an administrative cut for a real measurement -- and
     * [SegmentRecord.rigSquelchFusionApplied] is `false` regardless of how the segment opened
     * (this class's own "true only when both edges were squelch-decided" rule already gives that
     * for free: `closedBySquelch` is never set true here).
     *
     * A loss while [State.SQUELCH_POSTROLL] is already in flight (a squelch close and a loss
     * landing in close succession) is left to finish exactly as it was already going to -- the
     * segment is already closing honestly; retagging it would not make it more honest, only more
     * confusing. A loss while idle, or mid a pure-VAD segment that predates fusion ever engaging,
     * touches nothing but [squelchOpen] itself.
     */
    private fun handleSquelchLoss(frameStart: Long) {
        squelchOpen = null
        if (state != State.SQUELCH_OPEN) return
        closedBySquelch = false
        squelchCloseVadEnd = frameStart
        postRollCloseReason = SegmentCloseReason.RIG_LOST
        squelchPostRollBuf.clear()
        state = State.SQUELCH_POSTROLL
    }

    /**
     * FR-OBS-1 (Q20): a frame counts toward the active window's tally either when a window is
     * already open (TENTATIVE/SPEECH/HANGOVER/SQUELCH_OPEN/SQUELCH_POSTROLL) or when this very
     * frame is the one about to open one -- a VAD trigger while squelch is unknown (the
     * pre-fusion rule), or any frame at all while squelch is known open (FR-SEG-5: squelch alone
     * decides the open, not VAD). A frame that stays IDLE because squelch is known *closed* is
     * correctly excluded even if VAD called it SPEECH -- that frame was never claimed by anything
     * (see [handleFrame]'s dispatch), so it must not be tallied into a window that never opened.
     * Reset back to 0 happens once, in emitRejected()/closeSpeech()/closeSquelchSegment(), the
     * moment that window actually closes.
     */
    private fun tallyActiveWindow(decision: VadDecision) {
        val aboutToOpen = state == State.IDLE &&
            (squelchOpen == true || (squelchOpen == null && decision == VadDecision.SPEECH))
        if (state != State.IDLE || aboutToOpen) {
            activeFrameCount++
            if (decision == VadDecision.SPEECH) activeSpeechFrameCount++
        }
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
            emitRejected(endSample = frameStart, closeReason = SegmentCloseReason.SILENCE)
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
            closeSpeech(vadEnd = end, end = end, closeReason = SegmentCloseReason.MAX_DURATION)
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
            flushBuffered(hangoverBuf, postRollSamples.toLong())
            closeSpeech(
                vadEnd = hangoverStartSample,
                end = hangoverStartSample + postRollSamples,
                closeReason = SegmentCloseReason.SILENCE,
            )
        }
    }

    private fun bufferedSamples(buf: List<FloatArray>): Long = buf.sumOf { it.size.toLong() }

    private fun flushBuffered(buf: MutableList<FloatArray>, limitSamples: Long) {
        val w = writer ?: return
        var remaining = limitSamples
        for (f in buf) {
            if (remaining <= 0) break
            if (f.size <= remaining) {
                w.append(f)
                remaining -= f.size
            } else {
                w.append(f.copyOf(remaining.toInt()))
                remaining = 0
            }
        }
        buf.clear()
    }

    private fun closeSpeech(vadEnd: Long, end: Long, closeReason: SegmentCloseReason) {
        val w = writer ?: return
        val forced = closeReason == SegmentCloseReason.MAX_DURATION
        // FR-OBS-1 (Q20): snapshot this segment's own tally before resetting it below -- whatever
        // comes next (IDLE, or a fresh forced-split segment starting at `end`) must start from 0,
        // never carry over frames that belonged to the segment just closed.
        val frameCount = activeFrameCount
        val speechFrameCount = activeSpeechFrameCount
        activeFrameCount = 0
        activeSpeechFrameCount = 0
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
                closeReason = closeReason,
                vadFrameCount = frameCount,
                vadSpeechFrameCount = speechFrameCount,
                rigSquelchFusionApplied = false, // FR-SEG-5: the pure-VAD path never fuses.
            ),
        )
        writer = null
        if (!forced) state = State.IDLE
    }

    private fun emitRejected(endSample: Long, closeReason: SegmentCloseReason) {
        val id = SegmentId(nextId++)
        val start = maxOf(originSample, triggerSample - preRollSamples)
        val w = sink.open(id, start)
        w.append(preRoll.snapshot(atMost = (triggerSample - start).toInt()))
        for (f in tentativeFrames) w.append(f)
        // FR-OBS-1 (Q20): see closeSpeech()'s identical comment -- this rejected candidate's own
        // tally, reset to 0 the instant it closes.
        val frameCount = activeFrameCount
        val speechFrameCount = activeSpeechFrameCount
        activeFrameCount = 0
        activeSpeechFrameCount = 0
        w.close(
            SegmentRecord(
                id = id,
                startSample = start,
                endSample = endSample,
                vadStartSample = triggerSample,
                vadEndSample = endSample,
                sampleCount = 0,
                outcome = SegmentOutcome.REJECTED_TOO_SHORT,
                closeReason = closeReason,
                vadFrameCount = frameCount,
                vadSpeechFrameCount = speechFrameCount,
                rigSquelchFusionApplied = false, // FR-SEG-5: the pure-VAD path never fuses.
            ),
        )
        tentativeFrames.clear()
        state = State.IDLE
    }

    // --- FR-SEG-5 fusion: squelch-gated path -----------------------------------------------

    private fun beginSquelchSegment(frame: FloatArray, frameStart: Long) {
        segId = SegmentId(nextId++)
        segVadStartSample = frameStart
        segStartSample = maxOf(originSample, frameStart - preRollSamples)
        val w = sink.open(segId, segStartSample)
        writer = w
        w.append(preRoll.snapshot(atMost = (frameStart - segStartSample).toInt()))
        w.append(frame)
        openedBySquelch = true
        closedBySquelch = false
        state = State.SQUELCH_OPEN
    }

    private fun squelchOpenFrame(frame: FloatArray, frameStart: Long) {
        if (squelchOpen == false) {
            // Squelch just closed -- start the post-roll window kept around the edge (FR-SEG-8),
            // the same generosity FR-CAP-4/FR-SEG-8 already give a VAD hangover close, applied
            // here to a squelch close instead. This frame's own audio is buffered, not yet
            // written -- squelchPostRollFrame()/finish() decide how much of it survives.
            closedBySquelch = true
            squelchCloseVadEnd = frameStart
            postRollCloseReason = SegmentCloseReason.SQUELCH_CLOSE
            squelchPostRollBuf.clear()
            squelchPostRollBuf.add(frame.copyOf())
            state = State.SQUELCH_POSTROLL
            return
        }
        val w = writer ?: return
        w.append(frame)
        val end = frameStart + frameSamples
        if (end - segStartSample >= maxSegmentSamples) {
            // FR-SEG-3: the stuck-carrier safety net applies just as much to a squelch-gated
            // interval as to a VAD one -- and it is also, correctly, how a rig that goes silent
            // mid-over (no more transitions ever arrive) eventually closes: not a fusion decision
            // (closedBySquelch = false), an honest administrative cut.
            closeSquelchSegment(
                vadEnd = end,
                end = end,
                closeReason = SegmentCloseReason.MAX_DURATION,
                closedBySquelch = false,
            )
            segId = SegmentId(nextId++)
            segStartSample = end
            segVadStartSample = end
            writer = sink.open(segId, segStartSample)
            openedBySquelch = false
            closedBySquelch = false
            state = State.SQUELCH_OPEN
        }
    }

    private fun squelchPostRollFrame(frame: FloatArray, frameStart: Long) {
        if (squelchOpen == true) {
            // Flap: squelch reopened before the post-roll window finished flushing -- the
            // buffered tail is genuine mid-transmission audio after all, so it is written in
            // full and the SAME segment resumes, mirroring hangover()'s own "speech resumed"
            // reunification (a brief squelch chatter must never fragment one real transmission
            // into several).
            val w = writer
            if (w != null) for (f in squelchPostRollBuf) w.append(f)
            squelchPostRollBuf.clear()
            closedBySquelch = false
            state = State.SQUELCH_OPEN
            squelchOpenFrame(frame, frameStart)
            return
        }
        squelchPostRollBuf.add(frame.copyOf())
        val end = frameStart + frameSamples
        val hitMax = end - segStartSample >= maxSegmentSamples
        val buffered = bufferedSamples(squelchPostRollBuf)
        if (buffered >= postRollSamples || hitMax) {
            val flushLimit = minOf(postRollSamples.toLong(), buffered)
            flushBuffered(squelchPostRollBuf, flushLimit)
            // R-1062 follow-up: postRollCloseReason carries whichever entry point this window
            // actually started from (a genuine close, or a loss of authority) -- MAX_DURATION
            // still overrides either, exactly as it already did for a genuine close.
            val closeReason = if (hitMax) SegmentCloseReason.MAX_DURATION else postRollCloseReason
            val stillClosedBySquelch = closedBySquelch && !hitMax
            closeSquelchSegment(
                vadEnd = squelchCloseVadEnd,
                end = squelchCloseVadEnd + flushLimit,
                closeReason = closeReason,
                closedBySquelch = stillClosedBySquelch,
            )
            if (hitMax) {
                // Vanishingly unlikely in practice (postRollMs << maxSegmentMs) but handled
                // honestly rather than assumed impossible: the forced-split continuation exactly
                // mirrors squelchOpenFrame()'s own.
                segId = SegmentId(nextId++)
                segStartSample = end
                segVadStartSample = end
                writer = sink.open(segId, segStartSample)
                openedBySquelch = false
                closedBySquelch = false
                state = State.SQUELCH_OPEN
            }
        }
    }

    private fun closeSquelchSegment(
        vadEnd: Long,
        end: Long,
        closeReason: SegmentCloseReason,
        closedBySquelch: Boolean,
    ) {
        val w = writer ?: return
        val forced = closeReason == SegmentCloseReason.MAX_DURATION
        val frameCount = activeFrameCount
        val speechFrameCount = activeSpeechFrameCount
        activeFrameCount = 0
        activeSpeechFrameCount = 0
        // FR-SEG-5: VAD's only remaining job inside a squelch-gated interval -- whether it heard
        // any speech at all. An interval with none is retained and reported, never dropped
        // (constitution III, FR-SEG-6's same discipline applied to this new outcome).
        val outcome = if (speechFrameCount > 0) SegmentOutcome.SPEECH else SegmentOutcome.REJECTED_NO_SPEECH
        // FR-SEG-10: true only when BOTH edges were squelch-decided -- see this class's own kdoc
        // for why a forced split or an end-of-stream cut must never claim a fusion decision it
        // only half made.
        val fusionApplied = openedBySquelch && closedBySquelch
        w.close(
            SegmentRecord(
                id = segId,
                startSample = segStartSample,
                endSample = end,
                vadStartSample = segVadStartSample,
                vadEndSample = vadEnd,
                sampleCount = 0,
                outcome = outcome,
                forcedSplit = forced,
                closeReason = closeReason,
                vadFrameCount = frameCount,
                vadSpeechFrameCount = speechFrameCount,
                rigSquelchFusionApplied = fusionApplied,
            ),
        )
        writer = null
        if (!forced) state = State.IDLE
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
