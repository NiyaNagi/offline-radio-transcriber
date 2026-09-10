package org.ort.app.ui.data

import android.content.Context
import org.ort.core.capture.AudioRouteKind
import org.ort.core.capture.BluetoothAudioProfile
import org.ort.core.capture.CaptureMode
import org.ort.core.capture.RigTransportKind
import org.ort.data.OrtDatabase
import org.ort.data.dao.SessionCaptureInfo

/**
 * FR-CAP-13's v7 columns (`SessionEntity.captureMode`/`audioRouteKind`/`audioRouteLabel`/
 * `bluetoothProfile`/`rigTransport`), typed against `:core`'s closed sets rather than the raw
 * strings `:data` stores them as — the one place every WPF surface (N04, N01b, DG04, the Log's
 * `bt audio` mark, F23) reads a session's mode and route, so all of them agree.
 *
 * **The WPC2 seam.** WPC2 is extending `InputStatus.State.Lost` with the route kind/profile and
 * `RigStatus` with the transport kind — once that lands, the *current, live* session's facts
 * should be read from those process-wide holders (already-open state), with this reader kept only
 * for a past session's row (`DG04` on an ended night). Until then, the session row is the only
 * record of truth this build has for either case (FR-CAP-13 says the row is written at session
 * start specifically so a reader never has to guess), so every call site here reads through this
 * one seam — swapping the live-session half to the WPC2 holders later is a change to this file and
 * its callers' plumbing, not to any of the formatting/gating logic built on top of [SessionRouteFacts].
 *
 * Every field is nullable — not because a v7-onward session may lack it, but because a pre-v7 row,
 * or a value this build does not recognise (a future enum case written by a newer build, read by
 * this one), must read as an honest "not tracked" rather than a fabricated guess (constitution I).
 */
public data class SessionRouteFacts(
    public val captureMode: CaptureMode?,
    public val audioRouteKind: AudioRouteKind?,
    public val audioRouteLabel: String?,
    public val bluetoothProfile: BluetoothAudioProfile?,
    public val rigTransport: RigTransportKind?,
) {
    /** FR-CAP-11/FR-CAP-13: the fact every Bluetooth-audio surface (N04, F23, the Log's `bt audio`
     * mark) gates on. */
    public val isBluetoothAudio: Boolean get() = audioRouteKind == AudioRouteKind.BLUETOOTH_SCO

    /** FR-CAP-3a/FR-CAP-10: the fact N01b's persistent room-audio disclosure gates on. */
    public val isLocalMicrophone: Boolean get() = captureMode == CaptureMode.LOCAL_MICROPHONE

    public companion object {
        /** The honest default: a pre-v7 session, a `null` session id, or a lookup failure — never
         * a fabricated mode or route. */
        public val NOT_TRACKED: SessionRouteFacts = SessionRouteFacts(
            captureMode = null,
            audioRouteKind = null,
            audioRouteLabel = null,
            bluetoothProfile = null,
            rigTransport = null,
        )

        /** [info] is `null` for a session id that does not exist at all; every individual column
         * on a real row can independently be `null` (pre-v7) or an unrecognised string (a future
         * enum case) — both parse to `null` here, never a guessed case (constitution I). */
        public fun from(info: SessionCaptureInfo?): SessionRouteFacts {
            if (info == null) return NOT_TRACKED
            return SessionRouteFacts(
                captureMode = info.captureMode?.let { name -> runCatching { CaptureMode.valueOf(name) }.getOrNull() },
                audioRouteKind = info.audioRouteKind
                    ?.let { name -> runCatching { AudioRouteKind.valueOf(name) }.getOrNull() },
                audioRouteLabel = info.audioRouteLabel,
                bluetoothProfile = info.bluetoothProfile
                    ?.let { name -> runCatching { BluetoothAudioProfile.valueOf(name) }.getOrNull() },
                rigTransport = info.rigTransport
                    ?.let { name -> runCatching { RigTransportKind.valueOf(name) }.getOrNull() },
            )
        }
    }
}

/** The seam itself — an interface so every caller in this package can be tested against
 * [FakeSessionRouteFactsReader] instead of a real [OrtDatabase] (constitution II). */
public interface SessionRouteFactsReader {
    public suspend fun forSession(sessionId: String?): SessionRouteFacts
}

/** The production reader — [SessionCaptureInfo] is already the minimal, audio-free read shape
 * `:data`'s own `SessionDao.getCaptureInfo` kdoc names for exactly this purpose (AC-129: "never
 * reads audio"). */
public class RoomSessionRouteFactsReader(private val context: Context) : SessionRouteFactsReader {
    override suspend fun forSession(sessionId: String?): SessionRouteFacts {
        if (sessionId == null) return SessionRouteFacts.NOT_TRACKED
        val db = OrtDatabase.create(context.applicationContext)
        return SessionRouteFacts.from(db.sessionDao().getCaptureInfo(sessionId))
    }
}

/** The behavioural fake (constitution II): a caller scripts exactly the facts a given session id
 * should read as; any other id — including `null` — reads the same honest [SessionRouteFacts.NOT_TRACKED]
 * default a real, untracked session would. */
public class FakeSessionRouteFactsReader : SessionRouteFactsReader {
    private val bySessionId = mutableMapOf<String, SessionRouteFacts>()

    public fun set(sessionId: String, facts: SessionRouteFacts) {
        bySessionId[sessionId] = facts
    }

    override suspend fun forSession(sessionId: String?): SessionRouteFacts =
        sessionId?.let { bySessionId[it] } ?: SessionRouteFacts.NOT_TRACKED
}
