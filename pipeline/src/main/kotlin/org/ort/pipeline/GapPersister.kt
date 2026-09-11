package org.ort.pipeline

import org.ort.capture.android.GapRecord
import org.ort.core.Clock
import org.ort.core.Ulid
import org.ort.data.dao.CaptureGapDao
import org.ort.data.entity.CaptureGapCause
import org.ort.data.entity.CaptureGapEntity

/**
 * Maps `:capture-android`'s plain [GapRecord] (which cannot itself reference `:data` — module
 * graph, technical design §2) onto the persisted `CaptureGapEntity` (FR-RUN-12 → AC-48, AC-49).
 * Nothing is ever deleted here: every gap that reaches this class is written, never filtered.
 */
public class GapPersister(private val dao: CaptureGapDao, private val clock: Clock) {

    /**
     * WPC3 (FR-CAP-5, F23): [isBluetoothAudioRoute] tells [causeFor] whether the session's
     * currently-selected audio route is Bluetooth at the moment this gap is persisted — the caller
     * ([org.ort.pipeline.capture.RealCaptureService]'s own `gapRelay`) reads that live, not at
     * construction time, so a gap always reflects the route that actually dropped. `false` by
     * default so every other caller of this class (the F5 unclean-end gap, which names a previous
     * session, not a route) is unaffected.
     */
    public suspend fun persist(
        sessionId: String,
        gap: GapRecord,
        isBluetoothAudioRoute: Boolean = false,
    ): CaptureGapEntity {
        val entity = CaptureGapEntity(
            id = Ulid.generate(clock).toString(),
            sessionId = sessionId,
            startedAt = gap.startWallMillis,
            endedAt = gap.endWallMillis,
            cause = causeFor(gap.cause, isBluetoothAudioRoute),
            recoveredAutomatically = true,
        )
        dao.insert(entity)
        return entity
    }

    /**
     * register R-106 added [CaptureGapCause.CALL]/[CaptureGapCause.INPUT_LOST]/
     * [CaptureGapCause.OS_STOPPED]/[CaptureGapCause.ROUTE_LOST]. What each real code path can
     * actually produce, checked against the running app rather than guessed:
     *
     * - **[CaptureGapCause.OS_STOPPED]** — [RealCaptureService][org.ort.pipeline.capture.RealCaptureService]
     *   passes this literal cause string itself (see its own kdoc), for the one gap it constructs
     *   directly rather than deriving from a [org.ort.capture.android.GapRecord].
     * - **[CaptureGapCause.CALL]** — genuinely **not distinguishable from a generic focus loss
     *   today**. `AndroidAudioIo`'s own doc comment says so explicitly: "Route-change and
     *   interruption detection are intentionally minimal here (no
     *   `AudioManager.OnAudioFocusChangeListener` wiring yet)" — no real code path in this app
     *   emits a cause string naming a call at all yet. Even once that wiring exists, Android's
     *   `OnAudioFocusChangeListener` reports *that* focus was lost, never *why* — telling a call
     *   apart from another app requesting audio focus needs a second signal
     *   (`TelephonyManager`'s call state), which nothing in this codebase reads. The mapping below
     *   (`"call"` in the cause string → `CALL`) is kept ready for whichever of `AndroidAudioIo` or
     *   a future `TelephonyManager` cross-reference produces that string first — today only the
     *   debug scenario simulator (`gap-call`) writes a `CALL`-caused gap directly, bypassing this
     *   function entirely.
     * - **[CaptureGapCause.INPUT_LOST]** — the more precisely-named successor to
     *   [CaptureGapCause.DEVICE_LOST] for a bare "read error" or a "device" reference **on a
     *   non-Bluetooth route** — see [CaptureGapCause.BLUETOOTH_AUDIO_LOST] immediately below for
     *   the Bluetooth case of the exact same underlying signal. F-010's dropped-span encoding
     *   (`"dropped samples: ..."`) is left mapped to `DEVICE_LOST` unchanged — an established,
     *   tested behaviour ([GapPersisterTest]'s own F-010 case) — so only the two causes that mapped
     *   to it *without* a more specific match before now resolve to `INPUT_LOST`/
     *   `BLUETOOTH_AUDIO_LOST` instead.
     * - **[CaptureGapCause.BLUETOOTH_AUDIO_LOST]** (WPC3, FR-CAP-5, F23) — the *same* "device"/"read
     *   error" signal as [CaptureGapCause.INPUT_LOST] above, distinguished only by
     *   [isBluetoothAudioRoute] — [org.ort.pipeline.capture.RealCaptureService] passes that from
     *   the session's currently-selected [org.ort.capture.android.AudioDeviceDescriptor.kind],
     *   read live at persist time (not derived from the cause string itself, which carries no route
     *   information at all: `AudioRecordSource` emits the identical `"read error"` regardless of
     *   which route dropped).
     * - **[CaptureGapCause.ROUTE_LOST]** — **no code path reaches this today.**
     *   [org.ort.capture.android.AudioRecordSource] reports a route mismatch as
     *   [org.ort.captureapi.CaptureEvent.Failed] ("route mismatch: …"), which halts capture
     *   outright and is never routed through [org.ort.capture.android.GapTracker]/this class at
     *   all — correctly, per constitution IV: a route mismatch must halt, never resume on its own
     *   the way a gap does. The mapping below is reserved for if that ever changes; it cannot be
     *   exercised by the app as built.
     */
    private fun causeFor(cause: String, isBluetoothAudioRoute: Boolean): CaptureGapCause = when {
        cause.contains("os stopped", ignoreCase = true) -> CaptureGapCause.OS_STOPPED
        cause.contains("route mismatch", ignoreCase = true) -> CaptureGapCause.ROUTE_LOST
        cause.contains("route", ignoreCase = true) -> CaptureGapCause.ROUTE_CHANGE
        cause.contains("call", ignoreCase = true) -> CaptureGapCause.CALL
        cause.contains("focus", ignoreCase = true) -> CaptureGapCause.INTERRUPTION
        // audit F-028, unchanged: F-010's DroppedSpanCause encoding ("dropped samples: N samples
        // over Xms (stalled consumer or read shortfall)") is exactly the AudioRecord/consumer
        // shortfall DEVICE_LOST already means; :pipeline cannot reference capture-android's
        // internal DroppedSpanCause object, so this matches its stable "dropped samples:" prefix
        // directly instead. See this function's own kdoc for why this branch keeps DEVICE_LOST
        // rather than moving to INPUT_LOST -- GapPersisterTest's F-010 case depends on it.
        cause.startsWith("dropped samples:", ignoreCase = true) -> CaptureGapCause.DEVICE_LOST
        cause.contains("device", ignoreCase = true) || cause.contains("read error", ignoreCase = true) ->
            if (isBluetoothAudioRoute) CaptureGapCause.BLUETOOTH_AUDIO_LOST else CaptureGapCause.INPUT_LOST
        cause.contains("storage", ignoreCase = true) -> CaptureGapCause.STORAGE
        else -> CaptureGapCause.UNKNOWN
    }
}
