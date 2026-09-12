package org.ort.app.fieldreport.recorder

import org.ort.app.ui.navigation.ReaderDestination
import org.ort.app.ui.setup.SetupStep

/**
 * FR-OBS-6's closed, enumerated event vocabulary for the debug-build session recorder — the
 * evidence channel built after the product owner's first on-device run failed every transcription
 * and the only thing that reached the workstation was a verbal description and one photograph
 * (spec §7.13b).
 *
 * **Structural privacy (AC-141), the same discipline `DiagnosticsLog` already holds**
 * (`pipeline/src/main/kotlin/org/ort/pipeline/diagnostics/DiagnosticsLog.kt:38-47` — read before
 * changing anything here). Every property on every variant below is one of: a closed enum defined
 * in this package or reused from an existing closed set ([ReaderDestination], [SetupStep]), a
 * numeric or boolean primitive, a `List` of one of those, or — on [AudioDevicesEnumerated] alone —
 * the two platform-reported fields FR-OBS-6 explicitly names (`productName`, `address` on
 * [AudioDeviceSnapshot]). **There is no `message: String`, `detail: String`, `note: String`,
 * `reason: String` or `cause: String` parameter anywhere in this file, on purpose**: a caller
 * physically cannot pass a transcript fragment or a callsign through this API, no matter what it
 * has in scope. [RecorderEventVocabularyTest] (`AC_141_...`) proves this by reflection over the
 * whole sealed hierarchy rather than by inspecting sample output — see that test's own doc comment
 * for exactly what that does and does not establish.
 *
 * Two variants intentionally do **not** carry the richer type that already exists elsewhere,
 * because that type carries free text:
 * - [CaptureStateChanged] carries [CaptureTransitionKind], never
 *   `org.ort.pipeline.capture.CaptureState.State` itself — that sealed interface's own
 *   `Failed.reason`/`Interrupted.cause` are exactly the kind of caller-composed prose this
 *   vocabulary must never carry a path to, even a path this file's own code chooses not to read.
 *   [toRecorderTransitionKind] is the one place that maps one to the other, discarding the string.
 * - [DestinationChanged] carries [RecorderDestination] (this destination's *kind* only), never a
 *   drill-in's transmission/station/frequency/thread id — the same "closed id from an existing
 *   set" reasoning `DiagnosticsLog`'s own doc comment gives for why *its* opaque session ids are
 *   fine, applied one step more conservatively here since a destination-change event has no
 *   operational need for the id at all.
 */
public sealed interface RecorderEvent {

    /** FR-OBS-6: "destination changes (screen and route transitions)". Fired once per logical
     * destination change the recorder observes — see [FieldReportRecorder.onDestinationChanged],
     * wired from `OrtNavHost.kt`'s own single hook. */
    public data class DestinationChanged(val destination: RecorderDestination) : RecorderEvent

    /** FR-OBS-6: "tapped control ids". [control] is a closed identifier for the control tapped,
     * never the label rendered on screen or anything else user-visible. Not wired to any call site
     * in this change — every screen that could tap one lives outside this package's ownership this
     * round (see this package's own report) — but the type is ready for whichever package owns
     * that screen to call [FieldReportRecorder.record] with it. */
    public data class ControlTapped(val control: ControlId) : RecorderEvent

    /** FR-OBS-6: "permission-request results". Not wired to a call site this round — see
     * [ControlTapped]'s own note; the same applies here (the setup flow that requests these
     * permissions lives entirely under `ui/setup`, owned by another builder this round). */
    public data class PermissionResultRecorded(val permission: RecordedPermission, val result: PermissionResult) :
        RecorderEvent

    /** FR-OBS-6: "capture-state ... transitions". See this interface's own doc comment for why
     * [kind] is [CaptureTransitionKind], not `CaptureState.State` itself. Not wired to a call site
     * this round — `RealCaptureService` and the status surfaces that observe `CaptureState` are
     * outside this package's ownership. */
    public data class CaptureStateChanged(val kind: CaptureTransitionKind) : RecorderEvent

    /** FR-OBS-6: "setup-step ... transitions". [step] is [SetupStep] itself, already a closed enum
     * with no free-text case, so no separate recorder-owned mirror type is needed the way
     * [RecorderDestination] mirrors [ReaderDestination] (that mirror exists only to add the four
     * drill-in kinds `ReaderDestination` has no member for). Not wired to a call site this round —
     * everything under `ui/setup` is owned by WPD2 this round; per this package's brief, the
     * coordinator routes this hook once WPD2's round lands. */
    public data class SetupStepChanged(val step: SetupStep) : RecorderEvent

    /**
     * FR-OBS-6: "the platform's own audio-device enumeration" (register R-1004: on the operator's
     * ColorOS device every input appeared twice, and nobody could tell what the platform actually
     * reported because nothing recorded it). One event per `AudioManager.getDevices()` call,
     * carrying every device exactly as the platform reported it — see [AudioDeviceSnapshot]'s own
     * doc comment for why `productName`/`address` are the one place this vocabulary carries a
     * platform `String`. Not wired to a call site this round — `AndroidAudioIo.kt` is
     * the `capture-android` module, owned by WPJ this round; per this package's brief, if WPJ has not
     * already landed an equivalent dump, the coordinator routes this call site once free.
     */
    public data class AudioDevicesEnumerated(val devices: List<AudioDeviceSnapshot>) : RecorderEvent
}

/**
 * [RecorderEvent.DestinationChanged]'s closed vocabulary of destination *kinds* — every
 * [ReaderDestination] (mapped by [forReaderDestination]) plus the four real drill-ins `OrtNavHost`
 * dispatches to, which have no [ReaderDestination] member of their own (`NavHostDispatch`'s own
 * `when` — transmission/station/frequency/thread detail are reached by a non-null id alongside
 * `ids.current`, not by `ids.current` changing).
 */
public enum class RecorderDestination {
    NOW,
    LOG,
    SEARCH,
    THREADS,
    STATIONS,
    FREQUENCIES,
    EARLIER_NIGHTS,
    CAPTURE,
    IMPROVE_RECORDS,
    SETTINGS,
    TRANSMISSION_DETAIL,
    STATION_DETAIL,
    FREQUENCY_DETAIL,
    THREAD_DETAIL,
    ;

    public companion object {
        public fun forReaderDestination(destination: ReaderDestination): RecorderDestination = when (destination) {
            ReaderDestination.NOW -> NOW
            ReaderDestination.LOG -> LOG
            ReaderDestination.SEARCH -> SEARCH
            ReaderDestination.THREADS -> THREADS
            ReaderDestination.STATIONS -> STATIONS
            ReaderDestination.FREQUENCIES -> FREQUENCIES
            ReaderDestination.EARLIER_NIGHTS -> EARLIER_NIGHTS
            ReaderDestination.CAPTURE -> CAPTURE
            ReaderDestination.IMPROVE_RECORDS -> IMPROVE_RECORDS
            ReaderDestination.SETTINGS -> SETTINGS
        }
    }
}
