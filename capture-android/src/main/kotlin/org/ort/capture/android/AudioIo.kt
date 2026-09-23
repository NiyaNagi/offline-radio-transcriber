package org.ort.capture.android

/** Asynchronous notifications an [AudioIo] can raise between reads. */
public sealed interface AudioIoEvent {
    /** The OS reported a route change (`AudioDeviceCallback`/`AudioRecordingConfiguration`). */
    public data object RouteChanged : AudioIoEvent

    /** Audio focus was lost, or the device errored (an incoming call, a USB unplug, ...). */
    public data class Interrupted(val cause: String) : AudioIoEvent
}

/**
 * The seam between capture policy ([AudioRecordSource]) and the operating system
 * (`android.media.AudioRecord` / `AudioManager` in the real implementation, technical design
 * §5.1–§5.2). Kept minimal and synchronous so [AudioRecordSource]'s policy — route verification,
 * interruption handling, the backoff ladder — is testable against
 * [org.ort.capture.android.fake.FakeAudioIo] without any device or Robolectric shadow
 * (constitution II: every model-bearing interface ships its behavioural fake).
 */
public interface AudioIo {

    /** The native sample rate the hardware is actually running at. */
    public val deviceSampleRate: Int

    /**
     * Which [CaptureAudioSource] the most recent successful [open] actually obtained (technical
     * design §5.1, register R-1169) — `null` before any open has succeeded, and never a guessed
     * default. It is a *recorded fact about this capture*, not a setting: before it existed there
     * was no way to tell from a capture whether the OEM had applied its own gain and noise
     * suppression to it. Implementations keep it readable after [close], because the one caller
     * that reports it (`:app`'s `RealRouteCheck`) closes the device before it emits its result.
     */
    public val audioSource: CaptureAudioSource?

    public fun availableDevices(): List<AudioDeviceDescriptor>

    /** Requests the OS route audio from [device]. Mirrors `AudioRecord.setPreferredDevice`. */
    public fun select(device: AudioDeviceDescriptor)

    /** Opens and starts the hardware. `false` if the device could not be opened at all. */
    public fun open(): Boolean

    /** The device the OS actually routed to right now, or `null` if nothing is open. */
    public fun routedDevice(): AudioDeviceDescriptor?

    /** Reads one block of raw device-rate PCM16 mono into [buffer]; returns frames read, or -1 on error. */
    public fun read(buffer: ShortArray): Int

    public fun close()

    /** Registers the (single) listener for [AudioIoEvent]s. */
    public fun setEventListener(listener: (AudioIoEvent) -> Unit)
}
