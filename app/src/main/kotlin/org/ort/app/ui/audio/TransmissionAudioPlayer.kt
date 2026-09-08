package org.ort.app.ui.audio

/**
 * What one attempt to play a transmission's retained audio produced (build-plan P14, FR-UI-5).
 * There is deliberately no silent third option — a caller that cannot get audio is told why
 * rather than getting nothing back (constitution I's "uncertainty is content" applied to
 * playback, not just attribution).
 */
public sealed interface PlaybackOutcome {
    public data object Played : PlaybackOutcome
    public data class Unavailable(val reason: String) : PlaybackOutcome
}

/**
 * ui-conformance WP6 (R-054), `Detail-Playback.dc.html`: the three speeds the waveform card's chip
 * row offers, each preserving pitch (time-stretched, not pitched down) — "slower speeds are for
 * pulling a callsign out of a weak signal", per the artboard's own note.
 */
public enum class PlaybackRate(public val multiplier: Float, public val label: String) {
    NORMAL(1.0f, "1×"),
    THREE_QUARTER(0.75f, "0.75×"),
    HALF(0.5f, "0.5×"),
}

/**
 * Plays back one transmission's retained audio alongside its transcript (FR-UI-5). `:app` cannot
 * depend on `:capture-android` directly (`ModuleGraph.allowed` — only `:pipeline`, `:data`, `:net`
 * and `:core` are permitted edges), so [RealTransmissionAudioPlayer] reuses `:pipeline`'s already
 * -public `FlacSegmentAudioProvider` rather than re-implementing the codec decode a second time.
 *
 * ui-conformance WP6 (R-054) extended this interface for `Detail-Playback.dc.html`'s idle/playing
 * (position, scrub, speed)/no-audio/unavailable states — [pause]/[resume]/[seekToFraction]/
 * [setRate]/[positionFraction]/[isPlaying] — beyond P14's original play-once/stop.
 */
public interface TransmissionAudioPlayer {
    /** Starts playback of [transmissionId]'s retained audio, stopping whatever was already playing. */
    public suspend fun play(transmissionId: String): PlaybackOutcome

    public fun stop()

    /** Pauses without losing position — [resume] continues from where playback left off. */
    public fun pause()

    /** Resumes playback paused by [pause]. A no-op if nothing is currently loaded. */
    public fun resume()

    /** Moves playback to [fraction] of the transmission's duration, clamped to `0f..1f`. */
    public fun seekToFraction(fraction: Float)

    /** Changes playback speed, preserving pitch. Takes effect immediately if already playing. */
    public fun setRate(rate: PlaybackRate)

    /** The current position as a fraction of duration, `0f..1f`. `0f` when nothing has played yet. */
    public fun positionFraction(): Float

    /** True exactly while audio is actively playing (not paused, not stopped, not merely loaded). */
    public fun isPlaying(): Boolean

    /**
     * R-181, `Detail-Playback.dc.html`: a real [WaveformSummary] of [transmissionId]'s retained
     * audio, computed off the calling thread over the same decoded PCM [play] uses, and cached per
     * transmission id so revisiting an over never re-decodes it. `null` for a transmission with no
     * retained audio, or one whose retained audio fails to decode — never a fabricated shape
     * ([WaveformSummaryComputer]'s own doc comment names exactly what "real" means here).
     */
    public suspend fun waveformSummary(transmissionId: String): WaveformSummary?
}
