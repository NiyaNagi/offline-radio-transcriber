package org.ort.app.ui.audio

/**
 * The behavioural fake shipped alongside [TransmissionAudioPlayer] (constitution II: every
 * model/device-bearing interface ships its fake in the same change). Scriptable per transmission
 * id so a test can prove both the happy path and an honestly-reported failure, and it records
 * every call so a Compose test can assert *which* transmission was asked to play.
 */
public class FakeTransmissionAudioPlayer(private val script: Map<String, PlaybackOutcome> = emptyMap()) :
    TransmissionAudioPlayer {

    public val playCalls: MutableList<String> = mutableListOf()
    public var stopCallCount: Int = 0
        private set

    override suspend fun play(transmissionId: String): PlaybackOutcome {
        playCalls += transmissionId
        return script[transmissionId] ?: PlaybackOutcome.Played
    }

    override fun stop() {
        stopCallCount++
    }
}
