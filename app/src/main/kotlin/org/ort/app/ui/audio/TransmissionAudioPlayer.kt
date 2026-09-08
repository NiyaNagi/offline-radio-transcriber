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
 * Plays back one transmission's retained audio alongside its transcript (FR-UI-5). `:app` cannot
 * depend on `:capture-android` directly (`ModuleGraph.allowed` — only `:pipeline`, `:data`, `:net`
 * and `:core` are permitted edges), so [RealTransmissionAudioPlayer] reuses `:pipeline`'s already
 * -public `FlacSegmentAudioProvider` rather than re-implementing the codec decode a second time.
 */
public interface TransmissionAudioPlayer {
    /** Starts playback of [transmissionId]'s retained audio, stopping whatever was already playing. */
    public suspend fun play(transmissionId: String): PlaybackOutcome

    public fun stop()
}
