package org.ort.capture.android

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build

/**
 * The real, device-touching [AudioIo] (technical design §5.1–§5.2): everything [AudioRecordSource]
 * is policy over, this is the one place that actually calls `android.media.AudioRecord`.
 *
 * **Not yet exercised by any Robolectric test** — this is the one seam the project's own test
 * strategy (test-plan §3) deliberately leaves to the physical device, same as `RouteVerifier`'s
 * real counterpart in every other Android SDK wrapper here. [AudioRecordSource]'s policy (route
 * verification, interruption handling, backoff) is what P8 tested exhaustively against
 * [org.ort.capture.android.fake.FakeAudioIo]; this class's only job is to satisfy the same
 * contract with real hardware underneath it.
 *
 * Route-change and interruption detection are intentionally minimal here (no
 * `AudioDeviceCallback`/`AudioManager.OnAudioFocusChangeListener` wiring yet) — a v0 sufficient
 * to prove real capture end-to-end, not the full FR-CAP-3/FR-RUN-11 device-side implementation.
 * `AudioRecordSource`'s policy layer (which *is* fully built) will correctly treat a read error
 * as an interruption either way (`n < 0` in [AudioRecordSource.start]), so recovery still works;
 * what's missing is proactive `RouteChanged` notification before a read actually fails.
 */
public class AndroidAudioIo(context: Context, private val sampleRateHz: Int = DEFAULT_SAMPLE_RATE) : AudioIo {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var record: AudioRecord? = null
    private var listener: ((AudioIoEvent) -> Unit)? = null
    private var selected: AudioDeviceDescriptor? = null

    override val deviceSampleRate: Int = sampleRateHz

    override fun availableDevices(): List<AudioDeviceDescriptor> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyList()
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).map { it.toDescriptor() }
    }

    override fun select(device: AudioDeviceDescriptor) {
        selected = device
    }

    override fun open(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return false

        @Suppress("MissingPermission") // caller (MainActivity) verifies RECORD_AUDIO before this is ever called
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * BUFFER_SIZE_MULTIPLIER,
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val target = selected?.let { desc -> matchingDeviceInfo(desc) }
            target?.let { audioRecord.setPreferredDevice(it) }
        }

        audioRecord.startRecording()
        if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            audioRecord.release()
            return false
        }
        record = audioRecord
        return true
    }

    override fun routedDevice(): AudioDeviceDescriptor? {
        val r = record ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return selected
        return r.routedDevice?.toDescriptor() ?: selected
    }

    override fun read(buffer: ShortArray): Int {
        val r = record ?: return -1
        return r.read(buffer, 0, buffer.size)
    }

    override fun close() {
        record?.let {
            try {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
            } finally {
                it.release()
            }
        }
        record = null
    }

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

    public companion object {
        public const val DEFAULT_SAMPLE_RATE: Int = 48_000
        private const val BUFFER_SIZE_MULTIPLIER: Int = 4

        /** The built-in mic, as a fallback default selection before the user picks a device. */
        public fun builtInMicDescriptor(): AudioDeviceDescriptor =
            AudioDeviceDescriptor(id = "builtin", kind = AudioDeviceKind.BUILT_IN_MIC, label = "Built-in microphone")
    }
}
