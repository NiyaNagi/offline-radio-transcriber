package org.ort.core.capture

/**
 * The audio route kind (functional spec FR-CAP-2, FR-CAP-8) — a `:core` mirror of the Android-side
 * `AudioDeviceKind` (`:capture-android`) that the mode/preset/session layers can reason about
 * without any Android dependency (constitution VII). `WIRED_HEADSET` covers both a wired headset
 * and a USB-C/analog cable carrying line audio from a radio — the S00/FL7 "cabled" route.
 */
public enum class AudioRouteKind {
    BUILT_IN_MIC,
    USB,
    WIRED_HEADSET,
    BLUETOOTH_SCO,
    UNKNOWN,
}
