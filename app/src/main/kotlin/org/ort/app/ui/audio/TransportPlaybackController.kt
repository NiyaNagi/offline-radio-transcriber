package org.ort.app.ui.audio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * C10 (`design/canvas/Transport-Bar.dc.html`): playback state hoisted above any one screen — "the
 * bar owns playback" (this reverses R-1006's original stop-on-leave,
 * `TransmissionDetailScreen.kt`'s own former `DisposableEffect(detail.id) { onDispose {
 * player.stop() } }`, which lived at the per-screen layer).
 *
 * **AC-168 (build-plan P26) restores stop-on-leave — at the nav-host/transport-bar layer this
 * time, not here and not the per-screen layer.** This class still never decides *when* to stop on
 * its own; that decision lives in `OrtNavHost.kt`'s `NavHostBody` (see
 * [org.ort.app.ui.navigation.shouldStopPlaybackOnTransmissionLeave]'s own doc comment for exactly
 * which navigations now call [stop] and why), which calls the same [stop] every other caller
 * already does. What survives unconditionally is narrower than the sentence above once suggested:
 * a poll tick, a pause/resume, backgrounding the app without navigating within it — never a
 * genuine leave of the transmission's own detail screen.
 *

 * There is exactly one [TransmissionAudioPlayer] instance for the whole app lifetime
 * (`OrtNavHost.kt`'s own doc comment); this wraps it *in place*, delegating every
 * [TransmissionAudioPlayer] method so every existing call site that already types its `player`
 * parameter as the plain interface (`TransmissionDetailScreen`/`TransmissionDetailContent`,
 * `NavHostBody`/`NavHostDispatch`) keeps compiling and behaving unchanged — only `OrtNavHost`'s own
 * construction site changes what concrete instance flows through that same parameter. Every
 * interface call updates this controller's own observable state as a side effect, regardless of
 * which screen made the call, so the transport bar and whichever screen is on top never disagree
 * about what is playing (constitution: "never a stale state").
 *
 * [android.compose.runtime.mutableStateOf]-backed fields are read by Compose (the bar, and
 * `TransmissionDetailScreen`'s own `PlaybackSection`, which seeds its local state from this
 * controller when it is the one in play — see that file's own doc comment) without needing a
 * `StateFlow`/`Flow` bridge, matching every other plain-state pattern already used in this package.
 */
public class TransportPlaybackController(private val delegate: TransmissionAudioPlayer) : TransmissionAudioPlayer {

    /** The transmission currently loaded for playback, or `null` when nothing is (the transport
     * bar then shows its live mode, if any, per "playback over live, one mode at a time"). */
    public var loadedTransmissionId: String? by mutableStateOf(null)
        private set

    /** The callsign the bar shows beside the transport controls — set by whichever screen started
     * playback via [setNowPlayingMeta], since [play] itself only knows a transmission id. `null`
     * renders as "Unknown" on the bar rather than a blank space. */
    public var callsignLabel: String? by mutableStateOf(null)
        private set

    /** The loaded transmission's own duration, in seconds — also supplied via
     * [setNowPlayingMeta], since [TransmissionAudioPlayer] itself has no duration accessor. */
    public var durationSeconds: Double by mutableStateOf(0.0)
        private set

    public var isPlayingState: Boolean by mutableStateOf(false)
        private set

    public var positionFractionState: Float by mutableStateOf(0f)
        private set

    /** Whether a capture session is genuinely live right now — set by the nav host each poll tick
     * from the same real fact its drawer/live-bar already read (`ReaderPolling.effectiveSessionId`
     * + `CaptureState.isCapturing`), not this controller's own concern to determine. Drives the
     * bar's "the live dot stays" rule (State 5, `Transport-Bar.dc.html`) — constitution IV: capture
     * is never out of sight, even while the bar shows playback. */
    public var capturingNow: Boolean = false

    /** Set once [play] succeeds — the metadata [TransmissionAudioPlayer.play] itself has no way to
     * carry, since its own contract is `(transmissionId) -> PlaybackOutcome` alone. A no-op once
     * nothing is loaded (a race with a fast [clear]/[stop] from elsewhere) rather than resurrecting
     * a stale id. */
    public fun setNowPlayingMeta(transmissionId: String, callsignLabel: String?, durationSeconds: Double) {
        if (loadedTransmissionId != transmissionId) return
        this.callsignLabel = callsignLabel
        this.durationSeconds = durationSeconds
    }

    override suspend fun play(transmissionId: String): PlaybackOutcome {
        val outcome = delegate.play(transmissionId)
        if (outcome is PlaybackOutcome.Played) {
            loadedTransmissionId = transmissionId
            isPlayingState = true
            positionFractionState = 0f
            callsignLabel = null
            durationSeconds = 0.0
        }
        return outcome
    }

    /** The interface's own `stop()` — also reachable as [clear], the bar's own named action
     * (`×`), so a bar test can assert what it means to a reader without leaning on the plain
     * interface method's own, more generic, doc comment. */
    override fun stop() {
        delegate.stop()
        loadedTransmissionId = null
        callsignLabel = null
        durationSeconds = 0.0
        isPlayingState = false
        positionFractionState = 0f
    }

    /** C10's own name for [stop] from the bar's `×`: "× clears playback: back to live if
     * capturing, otherwise the bar hides" — the hide-vs-live decision itself is the nav host's,
     * made by recomputing its transport-bar state once [loadedTransmissionId] goes `null`. */
    public fun clear(): Unit = stop()

    override fun pause() {
        delegate.pause()
        isPlayingState = false
    }

    override fun resume() {
        delegate.resume()
        isPlayingState = true
    }

    override fun seekToFraction(fraction: Float) {
        delegate.seekToFraction(fraction)
        positionFractionState = fraction.coerceIn(0f, 1f)
    }

    override fun setRate(rate: PlaybackRate): Unit = delegate.setRate(rate)

    override fun positionFraction(): Float = delegate.positionFraction()

    override fun isPlaying(): Boolean = delegate.isPlaying()

    override suspend fun waveformSummary(transmissionId: String): WaveformSummary? =
        delegate.waveformSummary(transmissionId)

    /**
     * One poll tick, called from a loop hoisted to the nav host (not any one screen) so end-of-
     * track detection and position refresh happen whether or not the loaded transmission's own
     * detail screen is currently on screen — the exact reason `TransmissionDetailScreen`'s former
     * per-screen poll loop could not survive navigation intact. Mirrors that former loop's own
     * end-of-track handling: a real `AudioTrack`'s `playState` never flips on its own just because
     * a static buffer finished, so the recorded position reaching its own end is treated as
     * completion and clears playback outright ("a finished over clears itself").
     */
    public fun poll() {
        if (loadedTransmissionId == null || !isPlayingState) return
        val fraction = delegate.positionFraction()
        if (fraction >= 1f) {
            stop()
        } else {
            positionFractionState = fraction
            isPlayingState = delegate.isPlaying()
        }
    }
}
