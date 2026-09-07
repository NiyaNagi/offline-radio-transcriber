package org.ort.segment.fake

import org.ort.segment.Vad
import org.ort.segment.VadDecision

/**
 * The behavioural fake for [Vad] (constitution II): replays a pre-programmed sequence of
 * decisions, one per [accept] call. Lets a test dictate an exact keying pattern — including
 * decisions no real VAD could be coaxed into on demand — deterministically.
 *
 * Calling [accept] past the end of [script] repeats the last decision, so a test can under-
 * specify a long tail of silence.
 */
public class ScriptedVad(private val script: List<VadDecision>) : Vad {

    init {
        require(script.isNotEmpty()) { "script must not be empty" }
    }

    private var index = 0

    override fun accept(frame: FloatArray): VadDecision {
        val decision = script[minOf(index, script.size - 1)]
        index++
        return decision
    }

    public companion object {
        /** SPEECH for [speechFrames] frames, then SILENCE forever. */
        public fun speechThenSilence(speechFrames: Int): ScriptedVad =
            ScriptedVad(List(speechFrames) { VadDecision.SPEECH } + VadDecision.SILENCE)

        /** Builds a script from millisecond-labelled regions at the standard 32 ms frame. */
        public fun fromRegions(totalMs: Int, speechRegionsMs: List<IntRange>, frameMs: Int = 32): ScriptedVad {
            val frames = (totalMs + frameMs - 1) / frameMs
            val script = (0 until frames).map { i ->
                val t = i * frameMs
                if (speechRegionsMs.any { t in it }) VadDecision.SPEECH else VadDecision.SILENCE
            }
            return ScriptedVad(script)
        }
    }
}
