package org.ort.capture.android

/**
 * FR-CAP-2, FR-CAP-2a (register R-1114): decides the sample rate [AndroidAudioIo] actually opens
 * the selected device at, from what the OS reports that device supports
 * (`AudioDeviceInfo.getSampleRates()`).
 *
 * Before this, [AndroidAudioIo] always opened at a single fixed rate (48 000 Hz) regardless of the
 * selected device, so a USB adapter that only offers 44.1 kHz failed to open outright instead of
 * being negotiated and resampled — the exact opposite of what AGENTS.md already advertised
 * ("capture negotiates the device's native rate and resamples deterministically"). Most USB audio
 * adapters do not offer 16 kHz either, so a fixed *low* default would have failed just as often in
 * the other direction.
 *
 * Deliberately a pure function with no Android dependency: this is the actual decision logic, and
 * it is fully covered on a plain JVM regardless of what Robolectric can or cannot construct for
 * the real `android.media.AudioDeviceInfo` side of the wiring (see `AndroidAudioIoSampleRateTest`
 * for the boundary of what that half proves, and constitution II on preferring a pure decision
 * over exception forensics wherever one is possible).
 */
public object SampleRateNegotiator {

    /**
     * [supported] is `AudioDeviceInfo.getSampleRates()` for the selected device: an **empty**
     * array is Android's own way of saying the device places no restriction on sample rate (a
     * real device that resamples internally, or one the OS did not report rates for), in which
     * case [preferred] is used unchanged. Otherwise, [preferred] itself is kept when the device
     * actually offers it; failing that, the **highest** rate the device does offer is chosen —
     * deterministic regardless of the order the OS enumerates them in, and always resamplable to
     * whatever the pipeline's own output rate is via `PolyphaseResampler`, which
     * [AudioRecordSource] already applies whenever `deviceSampleRate != outputRate`.
     */
    public fun negotiate(preferred: Int, supported: IntArray): Int {
        if (supported.isEmpty()) return preferred
        if (preferred in supported) return preferred
        return supported.max()
    }
}
