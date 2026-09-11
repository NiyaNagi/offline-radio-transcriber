package org.ort.app.ui.data

import android.content.Context
import org.ort.app.ui.setup.RigPickerCatalogue
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
    /** E2-A07 (schema v10) — see [org.ort.data.entity.SessionEntity.rigDescriptorId]'s own kdoc. */
    public val rigDescriptorId: String? = null,
    /** E2-A07 (schema v10) — see [org.ort.data.entity.SessionEntity.audioRouteVerified]'s own kdoc. */
    public val audioRouteVerified: Boolean? = null,
    /** E2-A07 (schema v10) — see [org.ort.data.entity.SessionEntity.audioNativeRateHz]'s own kdoc. */
    public val audioNativeRateHz: Int? = null,
) {
    /** FR-CAP-11/FR-CAP-13: the fact every Bluetooth-audio surface (N04, F23, the Log's `bt audio`
     * mark) gates on. */
    public val isBluetoothAudio: Boolean get() = audioRouteKind == AudioRouteKind.BLUETOOTH_SCO

    /** FR-CAP-3a/FR-CAP-10: the fact N01b's persistent room-audio disclosure gates on. */
    public val isLocalMicrophone: Boolean get() = captureMode == CaptureMode.LOCAL_MICROPHONE

    /**
     * E2-A07/R-920 (register, polish): `"TH-D75A · Bluetooth SPP"` when [rigDescriptorId] resolves
     * against the bundled/imported catalogue [RigPickerCatalogue] builds (the same lookup S09/S09b
     * use, so a session's own record names its rig exactly as onboarding would), the transport's
     * own label alone when the id is `null` or does not resolve (an id from a descriptor since
     * removed, or a pre-v10 row), and `null` when neither is known at all. [stripRigManufacturerPrefix]
     * drops the catalogue entry's own leading manufacturer word — the same shared rule R-845/R-916
     * apply on CF06/N04, so this row never disagrees with either about the rig's own name.
     */
    public fun rigLabel(): String? {
        val transportLabel = rigTransport?.let {
            RigPickerCatalogue.transportLabel(RigPickerCatalogue.fromPresetKind(it))
        }
        val descriptorName = rigDescriptorId?.let { id ->
            RigPickerCatalogue.build().entries().firstOrNull { it.id == id }?.displayName
        }?.let { stripRigManufacturerPrefix(it) }
        return when {
            descriptorName != null && transportLabel != null -> "$descriptorName · $transportLabel"
            descriptorName != null -> descriptorName
            else -> transportLabel
        }
    }

    /**
     * E2-A07: the Input row's own composed line — `"<baseLabel> · verified · 48 kHz · radio audio"`
     * — from [baseLabel] (the route's own device label/kind; this class does not own that part) plus
     * whichever of [audioRouteVerified]/[audioNativeRateHz]/room-or-radio are actually known, joined
     * with `" · "` and never leaving a dangling separator for an omitted, unknown clause
     * (constitution I — a fact this build does not have is left out, never guessed).
     */
    public fun inputRowLabel(baseLabel: String): String {
        val verifiedClause = audioRouteVerified?.let { if (it) "verified" else "not verified" }
        val rateClause = audioNativeRateHz?.let(::kHzLabel)
        val radioOrRoomClause = captureMode?.let {
            if (it == CaptureMode.LOCAL_MICROPHONE) "room audio" else "radio audio"
        }
        return listOfNotNull(baseLabel.ifBlank { null }, verifiedClause, rateClause, radioOrRoomClause)
            .joinToString(" · ")
    }

    public companion object {
        /** The honest default: a pre-v7 session, a `null` session id, or a lookup failure — never
         * a fabricated mode or route. */
        public val NOT_TRACKED: SessionRouteFacts = SessionRouteFacts(
            captureMode = null,
            audioRouteKind = null,
            audioRouteLabel = null,
            bluetoothProfile = null,
            rigTransport = null,
            rigDescriptorId = null,
            audioRouteVerified = null,
            audioNativeRateHz = null,
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
                rigDescriptorId = info.rigDescriptorId,
                audioRouteVerified = info.audioRouteVerified,
                audioNativeRateHz = info.audioNativeRateHz,
            )
        }

        /** E2-A07: `"48 kHz"`/`"44.1 kHz"` — whole numbers unadorned, one decimal only when the
         * rate genuinely is not a round number of kHz (e.g. 44_100), never a bare Hz figure the
         * operator would have to convert themselves. */
        private fun kHzLabel(hz: Int): String {
            val kHz = hz / 1_000.0
            return if (kHz == kHz.toLong().toDouble()) "${kHz.toLong()} kHz" else "%.1f kHz".format(kHz)
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
