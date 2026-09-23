package org.ort.capture.android

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import org.ort.core.capture.BluetoothAudioProfile

/**
 * The real, device-touching [AudioIo] (technical design §5.1–§5.2): everything [AudioRecordSource]
 * is policy over, this is the one place that actually calls `android.media.AudioRecord`.
 *
 * Most of this class's behaviour is still the physical device's alone to prove — this is the one
 * seam the project's own test strategy (test-plan §3) deliberately leaves there, same as
 * `RouteVerifier`'s real counterpart in every other Android SDK wrapper here. [AudioRecordSource]'s
 * policy (route verification, interruption handling, backoff) is what P8 tested exhaustively
 * against [org.ort.capture.android.fake.FakeAudioIo]; this class's job is to satisfy the same
 * contract with real hardware underneath it. The Bluetooth SCO activation added for FR-CAP-11
 * (D34) is Robolectric-tested (`AndroidAudioIoBluetoothTest`) for the state transitions a shadow
 * `AudioManager` can observe; the negotiated codec it reports is a best-effort hint (see
 * [detectNegotiatedBluetoothProfile]) that only hardware row H5 actually proves.
 *
 * **P34, register R-1113/R-1114**: before this class registered a real `AudioDeviceCallback`
 * ([registerDeviceCallback]), [setEventListener]'s callback was stored and *never invoked* in
 * production — the only real interruption signal was a read returning `< 0` after the device was
 * already gone, and nothing ever re-verified the route after the first successful read. A route
 * that silently switched to the built-in microphone mid-session (the highest-consequence silent
 * failure in the system, constitution IV) produced no signal at all until the next
 * `AudioRecordSource` read happened to fail, which a route swap alone does not necessarily cause.
 * [registerDeviceCallback] closes the "device disappeared" half of that gap with a proactive,
 * OS-driven `Interrupted` (FR-RUN-11); the "OS silently rerouted while the device is still
 * present" half — the exact failure this register row names — is *not* something any Android
 * callback reliably reports for input routing, so it is closed instead by periodic re-verification
 * in [AudioRecordSource] itself, which polls [routedDevice] on a schedule regardless of whether the
 * OS ever says anything (see that class's own kdoc). [SampleRateNegotiator] closes FR-CAP-2/2a the
 * same session: [select] now negotiates [deviceSampleRate] against the selected device's own
 * `AudioDeviceInfo.getSampleRates()` instead of always opening at a fixed rate.
 */
public class AndroidAudioIo(
    context: Context,
    private val sampleRateHz: Int = DEFAULT_SAMPLE_RATE,
    /**
     * The live input gain, read once per [read] (register R-1168). A function, not a value, because
     * the operator can move the control while the device is already open and it must take effect on
     * the very next read — see [CaptureGain]'s own kdoc for why the real default is a process-wide
     * holder rather than a constructor argument `:app` could only set on the instance it built.
     */
    private val gain: () -> Float = { CaptureGain.linear },
    /**
     * The `PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED` capability probe (register R-1169), injectable
     * for exactly one reason: a Robolectric device always answers "no", so without this seam the
     * `UNPROCESSED` branch of technical design §5.1 could only ever be proven on hardware, and a
     * reverted selection would go on passing every test in this module. The real default below is
     * the real probe; nothing in production passes anything else.
     */
    private val unprocessedSupported: (() -> Boolean)? = null,
) : AudioIo {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var record: AudioRecord? = null
    private var listener: ((AudioIoEvent) -> Unit)? = null
    private var selected: AudioDeviceDescriptor? = null

    /**
     * Which [CaptureAudioSource] the most recent successful [open] actually obtained (register
     * R-1169) — `null` until one has succeeded, never a guessed default. Deliberately **survives**
     * [close]: `:app`'s `RealRouteCheck` closes the device before it emits its result, and a fact
     * about a capture that cannot be read after the capture is not recorded at all.
     */
    @Volatile
    private var obtainedAudioSource: CaptureAudioSource? = null

    override val audioSource: CaptureAudioSource? get() = obtainedAudioSource

    /** Whether *this instance* activated Bluetooth SCO — so [close] only ever undoes what it did. */
    private var bluetoothScoActivatedByThisInstance = false
    private var negotiatedBluetoothProfile: BluetoothAudioProfile? = null

    /**
     * FR-CAP-2, FR-CAP-2a (register R-1114): the rate [open] actually opens the hardware at —
     * [sampleRateHz] until [select] negotiates it down (or leaves it, see [SampleRateNegotiator])
     * against the selected device's own `AudioDeviceInfo.getSampleRates()`. Read by
     * [AudioRecordSource] through [deviceSampleRate] *before* [open] is ever called (its
     * constructor, not its `start()`), which is exactly why this is resolved at [select] time —
     * the one point in the real call sequence (`select()` then `AudioRecordSource(io, device)`,
     * see `RealCaptureService.startCapture()`) where the chosen device is known before that read.
     */
    private var resolvedSampleRateHz: Int = sampleRateHz

    /**
     * Detects the OS removing the currently-selected device out from under a running capture
     * (FR-RUN-11, register R-1113) — the one real, testable half of what this class's own doc
     * comment used to call "intentionally minimal": before this, [listener] was stored and never
     * invoked at all in production, so the only real interruption signal was a read returning
     * `< 0` after the device was already gone. `AudioDeviceCallback` is ordinary framework
     * dispatch code, not a native call Robolectric would have to fake convincingly — see
     * `AndroidAudioIoInterruptionTest` for what that lets this prove without a device.
     */
    private var deviceCallback: AudioDeviceCallback? = null

    override val deviceSampleRate: Int get() = resolvedSampleRateHz

    override fun availableDevices(): List<AudioDeviceDescriptor> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyList()
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).map { it.toDescriptor() }
    }

    override fun select(device: AudioDeviceDescriptor) {
        selected = device
        resolvedSampleRateHz = negotiateSampleRateFor(device)
    }

    /** See [resolvedSampleRateHz]'s own kdoc for why this runs at [select] time, not [open]'s. */
    private fun negotiateSampleRateFor(device: AudioDeviceDescriptor): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return sampleRateHz
        val info = matchingDeviceInfo(device) ?: return sampleRateHz
        // Platform-typed as non-null (`int[]`) but some real and test doubles alike return null
        // for a device with no reported profiles — treated the same as "no restriction reported"
        // (constitution I: absence of data is not evidence of a restriction).
        val supported: IntArray = info.sampleRates ?: EMPTY_SAMPLE_RATES
        return SampleRateNegotiator.negotiate(sampleRateHz, supported)
    }

    override fun open(): Boolean {
        val rate = resolvedSampleRateHz
        val minBuf = AudioRecord.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return false

        val audioRecord = openPreferredSource(rate, minBuf * BUFFER_SIZE_MULTIPLIER) ?: return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val target = selected?.let { desc -> matchingDeviceInfo(desc) }
            target?.let { audioRecord.setPreferredDevice(it) }
        }

        if (selected?.kind == AudioDeviceKind.BLUETOOTH) activateBluetoothSco()

        audioRecord.startRecording()
        if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            audioRecord.release()
            deactivateBluetoothSco()
            // It initialised but never recorded: nothing was obtained, so nothing is claimed.
            obtainedAudioSource = null
            return false
        }
        record = audioRecord
        registerDeviceCallback()
        return true
    }

    /**
     * Technical design §5.1, register R-1169: opens on `UNPROCESSED` where the device reports
     * supporting it, falling back to `VOICE_RECOGNITION` — and **records which one the open
     * actually got** ([obtainedAudioSource]), read back off the real `AudioRecord` rather than
     * assumed from what was asked for.
     *
     * The fallback is applied twice over, deliberately. Once on the capability answer, which is
     * the spec's own rule; and once more if a device that *claims* `UNPROCESSED` then fails to
     * initialise on it — constitution II prefers a capability probe to exception forensics, but a
     * probe that turns out to have lied must not take capture down with it, and this is the one
     * place where trying the other source costs nothing. `null` only when neither would open,
     * which the caller already treats as a genuine capture failure.
     */
    private fun openPreferredSource(rate: Int, bufferBytes: Int): AudioRecord? {
        obtainedAudioSource = null
        val supported = unprocessedSupported?.invoke() ?: supportsUnprocessedSource()
        val preferred = CaptureAudioSource.preferredFor(supported)
        val candidates = (listOf(preferred) + CaptureAudioSource.VOICE_RECOGNITION).distinct()
        for (candidate in candidates) {
            val opened = initialisedRecord(candidate, rate, bufferBytes) ?: continue
            obtainedAudioSource = CaptureAudioSource.fromAndroidSource(opened.audioSource) ?: candidate
            return opened
        }
        return null
    }

    /** One `AudioRecord` construction attempt — `null` for anything that did not initialise. */
    private fun initialisedRecord(source: CaptureAudioSource, rate: Int, bufferBytes: Int): AudioRecord? {
        @Suppress("MissingPermission") // caller (MainActivity) verifies RECORD_AUDIO before this is ever called
        val candidate = try {
            AudioRecord(
                source.androidSource,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (_: IllegalArgumentException) {
            // A source the platform rejects outright for this configuration — the same "could not
            // open on this one" outcome as a failed initialisation, handled identically.
            return null
        }
        if (candidate.state != AudioRecord.STATE_INITIALIZED) {
            candidate.release()
            return null
        }
        return candidate
    }

    /**
     * The capability probe technical design §5.1 names. `getProperty` answers `"true"`/`"false"` or
     * `null`; anything that is not an explicit `"true"` is treated as no support (constitution I:
     * absence of an answer is not evidence of a capability), and a stack that rejects the query
     * outright lands in the same place rather than taking capture down.
     */
    private fun supportsUnprocessedSource(): Boolean = try {
        audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
            .equals("true", ignoreCase = true)
    } catch (_: RuntimeException) {
        false
    }

    /**
     * FR-RUN-11 (register R-1113): the currently-selected device disappearing (a USB adapter
     * unplugged, a Bluetooth SCO link torn down at the OS level) is reported as
     * [AudioIoEvent.Interrupted] the moment the OS says so — proactively, not only once the next
     * [read] happens to return `< 0`. [AudioRecordSource]'s existing recovery/backoff and
     * [GapTracker] then behave exactly as they already do for any other interruption; this only
     * changes *when* the signal arrives, from "after the next failed read" to "as soon as the OS
     * knows." A device that is not the one this instance selected is deliberately ignored — that
     * is ordinary background device churn, not this capture's own interruption.
     */
    private fun registerDeviceCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val selectedId = selected?.id ?: return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                if (removedDevices.any { it.id.toString() == selectedId }) {
                    listener?.invoke(AudioIoEvent.Interrupted("device removed"))
                }
            }
        }
        deviceCallback = callback
        audioManager.registerAudioDeviceCallback(callback, null)
    }

    private fun unregisterDeviceCallback() {
        val callback = deviceCallback ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.unregisterAudioDeviceCallback(callback)
        }
        deviceCallback = null
    }

    override fun routedDevice(): AudioDeviceDescriptor? {
        val r = record ?: return null
        val base = if (Build.VERSION.SDK_INT <
            Build.VERSION_CODES.N
        ) {
            selected
        } else {
            (r.routedDevice?.toDescriptor() ?: selected)
        }
        return base?.withNegotiatedBluetoothProfile()
    }

    /**
     * Register R-1168: **the one and only gain seam.** The multiply is applied here, after the
     * device has delivered the block and before anyone sees it, because this is the only point both
     * the capture path ([AudioRecordSource]) and first-run setup's own level meter (`:app`'s
     * `RealLevelCheck`, which opens the device itself and never constructs an [AudioRecordSource])
     * actually cross. Applying it one layer up would leave the slider the operator is watching
     * during setup completely dead.
     *
     * It also means every downstream measurement — [LevelMeter.onFrame] here, `RealLevelCheck`'s
     * own peak there — measures **post-gain** audio, so gain-induced clipping is reported honestly
     * rather than hidden behind a meter reading the pre-gain block. See [CaptureGain.applyTo] for
     * why it saturates.
     */
    override fun read(buffer: ShortArray): Int {
        val r = record ?: return -1
        val n = r.read(buffer, 0, buffer.size)
        if (n > 0) CaptureGain.applyTo(buffer, n, gain())
        return n
    }

    override fun close() {
        unregisterDeviceCallback()
        record?.let {
            try {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
            } finally {
                it.release()
            }
        }
        record = null
        deactivateBluetoothSco()
    }

    /**
     * FR-CAP-11, D34: activates the Bluetooth SCO link for mic capture — `setCommunicationDevice`
     * on API ≥ 31 (the modern, non-deprecated path), `startBluetoothSco` + `setBluetoothScoOn`
     * below it. [RouteVerifier] treats the outcome exactly as any other selection (FR-CAP-3,
     * FR-CAP-3a): activation failing here does not fabricate success — the next `routedDevice()`
     * simply will not report the Bluetooth device, so verification halts precisely as a genuine
     * mismatch would.
     */
    private fun activateBluetoothSco() {
        val activated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = selected?.let { matchingDeviceInfo(it) }
            device != null && audioManager.setCommunicationDevice(device)
        } else {
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true
            true
        }
        bluetoothScoActivatedByThisInstance = activated
        negotiatedBluetoothProfile = if (activated) detectNegotiatedBluetoothProfile() else null
    }

    /** Undoes exactly what [activateBluetoothSco] did — a no-op if this instance never activated it. */
    private fun deactivateBluetoothSco() {
        if (!bluetoothScoActivatedByThisInstance) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
        }
        bluetoothScoActivatedByThisInstance = false
        negotiatedBluetoothProfile = null
    }

    /**
     * FR-CAP-11: best-effort only. No stable public Android API exposes which HFP codec was
     * actually negotiated; `bt_wbs` is a long-standing, undocumented `AudioManager` parameter
     * several stacks honour ("on"/"off" for wideband speech). Treated as a hint, never a fact
     * asserted past what it can support — anything else, including a stack that rejects the
     * parameter entirely, is reported as [BluetoothAudioProfile.UNKNOWN] rather than guessed
     * (constitution I). Hardware row H5 is what actually proves this on the reference device.
     */
    private fun detectNegotiatedBluetoothProfile(): BluetoothAudioProfile = try {
        when (audioManager.getParameters("bt_wbs")?.substringAfter('=')?.trim()?.lowercase()) {
            "on" -> BluetoothAudioProfile.HFP_MSBC
            "off" -> BluetoothAudioProfile.HFP_CVSD
            else -> BluetoothAudioProfile.UNKNOWN
        }
    } catch (_: RuntimeException) {
        BluetoothAudioProfile.UNKNOWN
    }

    private fun AudioDeviceDescriptor.withNegotiatedBluetoothProfile(): AudioDeviceDescriptor =
        if (kind == AudioDeviceKind.BLUETOOTH) copy(bluetoothProfile = negotiatedBluetoothProfile) else this

    override fun setEventListener(listener: (AudioIoEvent) -> Unit) {
        this.listener = listener
    }

    private fun matchingDeviceInfo(descriptor: AudioDeviceDescriptor): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.id.toString() == descriptor.id }
    }

    private fun AudioDeviceInfo.toDescriptor(): AudioDeviceDescriptor =
        AudioDeviceDescriptor(id = id.toString(), kind = type.toKind(), label = productName?.toString() ?: "device $id")

    private fun Int.toKind(): AudioDeviceKind = when (this) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> AudioDeviceKind.BUILT_IN_MIC
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> AudioDeviceKind.USB_DEVICE
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioDeviceKind.BLUETOOTH
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> AudioDeviceKind.WIRED_HEADSET
        else -> AudioDeviceKind.UNKNOWN
    }

    /**
     * The device to select when the user has not picked one: a **real** enumerated input,
     * preferring the built-in mic.
     *
     * Returns `null` when the OS enumerates no inputs at all, which the caller must treat as a
     * capture failure — not as licence to invent a descriptor. The first version of this class
     * did exactly that (a fabricated id `"builtin"`), and because [RouteVerifier] compares ids,
     * every real device reported a route mismatch on its first read and capture halted
     * immediately — AC-2 working precisely as designed, against a selection that could never
     * match anything. Found on a real phone; no fake could have surfaced it.
     */
    public fun defaultInputDevice(): AudioDeviceDescriptor? {
        val devices = availableDevices()
        return devices.firstOrNull { it.kind == AudioDeviceKind.BUILT_IN_MIC } ?: devices.firstOrNull()
    }

    public companion object {
        public const val DEFAULT_SAMPLE_RATE: Int = 48_000
        private const val BUFFER_SIZE_MULTIPLIER: Int = 4
        private val EMPTY_SAMPLE_RATES: IntArray = IntArray(0)
    }
}
