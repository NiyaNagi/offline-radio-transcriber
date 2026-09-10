package org.ort.capture.android

import org.ort.core.capture.BluetoothAudioProfile

/** What kind of hardware a [AudioDeviceDescriptor] names — the distinction [RouteVerifier] cares about. */
public enum class AudioDeviceKind { BUILT_IN_MIC, USB_DEVICE, BLUETOOTH, WIRED_HEADSET, UNKNOWN }

/**
 * A capture-relevant audio input device, enumerated from `AudioManager.getDevices` in the real
 * implementation (technical design §5.2). [id] is stable for the lifetime of the connection —
 * `AudioDeviceInfo.getId()` on a real device, an arbitrary stable string on [org.ort.capture.android.fake.FakeAudioIo].
 *
 * [bluetoothProfile] (FR-CAP-11, D34): the negotiated HFP codec, populated only once a
 * [AudioDeviceKind.BLUETOOTH] route is actually routed to — never guessed for any other kind, and
 * `null` on a Bluetooth device before activation completes (constitution I: uncertainty is
 * content, not a default value).
 */
public data class AudioDeviceDescriptor(
    val id: String,
    val kind: AudioDeviceKind,
    val label: String,
    val bluetoothProfile: BluetoothAudioProfile? = null,
)
