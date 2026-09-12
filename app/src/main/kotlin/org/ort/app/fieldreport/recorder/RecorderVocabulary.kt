package org.ort.app.fieldreport.recorder

import org.ort.pipeline.capture.CaptureState

/**
 * FR-OBS-6's closed set of tappable-control identifiers. A **starter vocabulary**: every screen
 * that could fire [RecorderEvent.ControlTapped] this round lives outside this package's ownership
 * (`ui/setup`, `ui/audio`, `ui/screens`, `ui/components` and everything under each — see this package's own
 * report), so nothing in this change actually records one yet. Extend this enum, never widen the
 * event to take a `String`, when a call site is wired.
 */
public enum class ControlId {
    DRAWER_OPEN,
    SEARCH_ICON,
    LIVE_BAR,
    SETUP_CONTINUE,
    SETUP_BACK,
    SETUP_SKIP,
    PERMISSION_REQUEST,
    RETRY,
}

/** FR-OBS-6's closed set of permissions the setup flow requests — one per [ControlId]-adjacent
 * `ui/setup` screen that asks the platform for a permission (`SetupStep.MICROPHONE`/
 * `MICROPHONE_DENIED`, `NOTIFICATIONS`, `BLUETOOTH_PERMISSION`). */
public enum class RecordedPermission {
    RECORD_AUDIO,
    POST_NOTIFICATIONS,
    BLUETOOTH_CONNECT,
}

/** FR-OBS-6's closed set of permission-request outcomes. [DENIED_PERMANENTLY] is distinct from
 * [DENIED] because the setup flow's own recovery differs (a "not asking again" denial needs a
 * Settings deep link, a plain denial can re-prompt) — a real defect class the field report exists
 * to catch, per this package's brief. */
public enum class PermissionResult {
    GRANTED,
    DENIED,
    DENIED_PERMANENTLY,
}

/**
 * [RecorderEvent.CaptureStateChanged]'s closed vocabulary, mirroring
 * `org.ort.pipeline.capture.CaptureState.State`'s four variants **by kind only** — see
 * [RecorderEvent]'s own doc comment for why `Failed.reason`/`Interrupted.cause` never reach this
 * enum. [toRecorderTransitionKind] is the one, exhaustive mapping; it is a `when` over the sealed
 * interface's types, so a fifth `CaptureState.State` variant would fail to compile here rather
 * than silently falling through.
 */
public enum class CaptureTransitionKind {
    IDLE,
    CAPTURING,
    FAILED,
    INTERRUPTED,
}

/** Maps a real [CaptureState.State] to its [CaptureTransitionKind] — pattern-matches the sealed
 * type's own class, never reads [CaptureState.State.Failed.reason] or
 * [CaptureState.State.Interrupted.cause]. */
public fun CaptureState.State.toRecorderTransitionKind(): CaptureTransitionKind = when (this) {
    is CaptureState.State.Idle -> CaptureTransitionKind.IDLE
    is CaptureState.State.Capturing -> CaptureTransitionKind.CAPTURING
    is CaptureState.State.Failed -> CaptureTransitionKind.FAILED
    is CaptureState.State.Interrupted -> CaptureTransitionKind.INTERRUPTED
}

/**
 * [RecorderEvent.AudioDevicesEnumerated]'s one entry — every field `android.media.AudioDeviceInfo`
 * itself exposes for an input device, named identically to that class's own getters (register
 * R-1004: on the operator's ColorOS device every input appeared twice; this is what lets a future
 * bundle answer whether the platform actually reported the device twice, reported it once with two
 * different ids, or something else no one has guessed yet).
 *
 * [productName] and [address] are this whole vocabulary's **one deliberate exception** to "no
 * `String` field": both are platform-assigned device metadata (a manufacturer's product name, a
 * MAC/USB address), never operator- or caller-composed prose — the distinction FR-OBS-6's own text
 * draws explicitly ("a closed set of typed fields, not free text") and the reason this data class,
 * not a generic parameter, is what carries them: the field list is fixed at compile time by this
 * declaration, so a caller cannot add a new free-text field beside them without changing this file
 * (and its test) first. [RecorderEventVocabularyTest] asserts these two are the *only* `String`
 * properties anywhere in the vocabulary.
 */
public data class AudioDeviceSnapshot(
    val id: Int,
    val type: Int,
    val productName: String?,
    val address: String?,
    val channelCounts: List<Int>,
    val sampleRates: List<Int>,
    val isSource: Boolean,
)
