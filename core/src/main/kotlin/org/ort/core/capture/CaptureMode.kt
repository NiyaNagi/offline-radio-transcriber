package org.ort.core.capture

/**
 * The capture mode: a **closed set**, chosen at onboarding (functional spec §7.1a FR-CAP-8, D33).
 * The audio route (where the samples come from) and the rig-control transport (where frequency,
 * mode and squelch come from) are independent axes; a mode is a named, familiar *pairing* of the
 * two defaults — see [CaptureModePresets] — never a new coupling between them (FR-CAP-9).
 *
 * [operatorLabel] is the exact operator-facing copy from the design (`Setup-Mode.dc.html`,
 * `Settings-Mode.dc.html`): a UI SHALL NOT invent its own wording for this closed set.
 */
public enum class CaptureMode(public val operatorLabel: String) {
    /**
     * A handheld held near the phone, or testing the app with no adapter attached. First-class
     * and fully supported (FR-CAP-10) — not a fallback, not a test affordance.
     */
    LOCAL_MICROPHONE("Local microphone"),

    /** The reference setup: a cabled rig with a CAT port, over a USB audio adapter. */
    USB_RADIO("USB-connected radio"),

    /** A rig whose data and/or audio arrive wirelessly (D34). */
    BLUETOOTH_RADIO("Bluetooth-connected radio"),
}
