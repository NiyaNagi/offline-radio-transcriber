package org.ort.core.capture

/**
 * The negotiated Bluetooth HFP codec (CON-CAP-1 as amended by D34, FR-CAP-11). Mic capture over
 * Bluetooth forces HFP — never A2DP, which is output-only — and HFP negotiates either the wideband
 * mSBC codec or, on older stacks/peers, narrowband CVSD. Recorded on every Bluetooth session so the
 * degradation is measurable rather than absorbed silently into an aggregate accuracy figure
 * (FR-TST-8).
 */
public enum class BluetoothAudioProfile {
    /** Wideband: SBC at 16 kHz mono, bitpool 26 (CON-CAP-1). */
    HFP_MSBC,

    /** Narrowband fallback — a further degradation below [HFP_MSBC]. */
    HFP_CVSD,

    /** Negotiated, but the stack did not report which codec — still disclosed, never guessed. */
    UNKNOWN,
}
