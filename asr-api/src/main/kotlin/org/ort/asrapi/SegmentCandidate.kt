package org.ort.asrapi

/**
 * The pre-decode facts about a closed segment that the cheap hallucination controls need
 * (technical design §8.2, rules 1-2). These facts are produced by `:segment` (P4); this module
 * only consumes them, so `:asr-api` never gains a dependency on `:segment` (ModuleGraph).
 */
public data class SegmentCandidate(
    val durationMs: Int,
    /** Whether the VAD judged this segment to contain speech at all (rule 2, `vad_no_speech`). */
    val vadDetectedSpeech: Boolean,
)
