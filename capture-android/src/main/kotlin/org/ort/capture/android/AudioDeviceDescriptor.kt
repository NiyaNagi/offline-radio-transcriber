package org.ort.capture.android

/** What kind of hardware a [AudioDeviceDescriptor] names — the distinction [RouteVerifier] cares about. */
public enum class AudioDeviceKind { BUILT_IN_MIC, USB_DEVICE, BLUETOOTH, WIRED_HEADSET, UNKNOWN }

/**
 * A capture-relevant audio input device, enumerated from `AudioManager.getDevices` in the real
 * implementation (technical design §5.2). [id] is stable for the lifetime of the connection —
 * `AudioDeviceInfo.getId()` on a real device, an arbitrary stable string on [org.ort.capture.android.fake.FakeAudioIo].
 */
public data class AudioDeviceDescriptor(val id: String, val kind: AudioDeviceKind, val label: String)
