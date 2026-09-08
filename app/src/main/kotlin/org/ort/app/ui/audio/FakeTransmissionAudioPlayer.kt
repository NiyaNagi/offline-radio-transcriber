package org.ort.app.ui.audio

/**
 * The behavioural fake shipped alongside [TransmissionAudioPlayer] (constitution II: every
 * model/device-bearing interface ships its fake in the same change). Scriptable per transmission
 * id so a test can prove both the happy path and an honestly-reported failure, and it records
 * every call so a Compose test can assert *which* transmission was asked to play.
 *
 * ui-conformance WP6 (R-054) extended this fake alongside the interface: [rate]/[positionFraction]/
 * [isPlaying] are plain in-memory state, so a Compose test can drive `Detail-Playback.dc.html`'s
 * playing/scrub/speed states without a real `AudioTrack`. [seekCalls] records every
 * [seekToFraction] call, the same pattern [playCalls] already established, so a Compose test that
 * dispatches a real scrub gesture can assert the fraction actually reached this fake.
 */
public class FakeTransmissionAudioPlayer(private val script: Map<String, PlaybackOutcome> = emptyMap()) :
    TransmissionAudioPlayer {

    public val playCalls: MutableList<String> = mutableListOf()
    public val seekCalls: MutableList<Float> = mutableListOf()
    public var stopCallCount: Int = 0
        private set

    public var rate: PlaybackRate = PlaybackRate.NORMAL
        private set

    private var playing = false
    private var position = 0f

    override suspend fun play(transmissionId: String): PlaybackOutcome {
        playCalls += transmissionId
        val outcome = script[transmissionId] ?: PlaybackOutcome.Played
        if (outcome is PlaybackOutcome.Played) {
            playing = true
            position = 0f
        }
        return outcome
    }

    override fun stop() {
        stopCallCount++
        playing = false
        position = 0f
    }

    override fun pause() {
        playing = false
    }

    override fun resume() {
        playing = true
    }

    override fun seekToFraction(fraction: Float) {
        seekCalls += fraction.coerceIn(0f, 1f)
        position = fraction.coerceIn(0f, 1f)
    }

    override fun setRate(rate: PlaybackRate) {
        this.rate = rate
    }

    override fun positionFraction(): Float = position

    override fun isPlaying(): Boolean = playing
}
