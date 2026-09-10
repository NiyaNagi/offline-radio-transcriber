package org.ort.core.capture

/**
 * The rig-control transport a [CaptureMode] preset points at, mirroring `:rig`'s own transport
 * kinds without creating a dependency on `:rig` from `:core` (constitution VII — `:rig` depends on
 * `:core`, never the reverse). `null` in [CapturePreset.preferredRigTransportKind] means "no
 * transport preset" (local microphone mode: manual frequency, no rig).
 */
public enum class RigTransportKind { USB_SERIAL, BLUETOOTH_SPP }

/**
 * A capture mode's default pairing of the two independent axes (FR-CAP-8's table). Plain data —
 * nothing about this type enforces the pairing; see [CaptureModePresets.presetsFor]'s own doc
 * comment for why (FR-CAP-9).
 */
public data class CapturePreset(
    public val preferredRouteKind: AudioRouteKind,
    public val preferredRigTransportKind: RigTransportKind?,
)

/**
 * FR-CAP-8's table, as a pure function. FR-CAP-9: choosing a mode **presets** both axes and then
 * presents each for confirmation — both stay independently overridable, so every combination the
 * hardware admits stays reachable, including Bluetooth control with wired audio (the combination
 * that costs nothing, FR-RIG-14). Accordingly [presetsFor] returns a plain [CapturePreset] value:
 * nothing here selects a device, opens a transport, or refuses a different choice. A caller is
 * free to read it and then do something else entirely — that is the whole point, not an oversight.
 *
 * The Bluetooth-mode audio preset is deliberately a **cabled** route ([AudioRouteKind.WIRED_HEADSET]),
 * not [AudioRouteKind.BLUETOOTH_SCO] — per the S00/FL7 boards and FR-CAP-9's rationale: Bluetooth
 * mode is named for its *rig control* transport (SPP), and the audio route that costs nothing is
 * wired, so that is what onboarding steers the operator toward by default. The Bluetooth *audio*
 * route (D34) is reachable by override, exactly as CON-CAP-1's marking regime requires.
 */
public object CaptureModePresets {
    public fun presetsFor(mode: CaptureMode): CapturePreset = when (mode) {
        CaptureMode.LOCAL_MICROPHONE -> CapturePreset(
            preferredRouteKind = AudioRouteKind.BUILT_IN_MIC,
            preferredRigTransportKind = null,
        )

        CaptureMode.USB_RADIO -> CapturePreset(
            preferredRouteKind = AudioRouteKind.USB,
            preferredRigTransportKind = RigTransportKind.USB_SERIAL,
        )

        CaptureMode.BLUETOOTH_RADIO -> CapturePreset(
            preferredRouteKind = AudioRouteKind.WIRED_HEADSET,
            preferredRigTransportKind = RigTransportKind.BLUETOOTH_SPP,
        )
    }
}
