package org.ort.app.ui.setup

import android.content.Context
import android.media.AudioManager
import android.os.Build
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioDeviceKind
import org.ort.capture.android.AudioIo

/** One row S04's list renders (`Setup-Input.dc.html`). [id] is [AudioDeviceDescriptor.id] verbatim
 * — the same id [org.ort.capture.android.RouteVerifier] compares against, so selecting a row and
 * later verifying it are guaranteed to mean the same device. */
public data class InputRouteOption(
    public val id: String,
    public val label: String,
    public val subtitle: String,
    public val isBuiltInMic: Boolean,
)

/**
 * S04 (`Setup-Input.dc.html`, R-081) — enumerates real capture routes through `:capture-android`'s
 * public [AudioIo] (read-only use; on `:app`'s compile classpath through `:pipeline`'s `api` edge,
 * per this package's own brief). [AudioDeviceDescriptor] itself carries no native sample rate —
 * only id/kind/label (`AudioDeviceDescriptor.kt`) — so the native-rate text in each subtitle is
 * read directly from `android.media.AudioDeviceInfo.getSampleRates()` (matched back to [io]'s own
 * descriptors by id), not invented: a device that reports no fixed rates (`getSampleRates()`
 * empty, meaning "any rate is accepted") is described by type alone, never with a fabricated
 * number (guide §9, constitution I).
 */
public class InputRouteEnumerator(private val context: Context, private val io: AudioIo) {

    public fun list(): List<InputRouteOption> {
        val nativeRates = nativeRatesById()
        return io.availableDevices().map { descriptor ->
            InputRouteOption(
                id = descriptor.id,
                label = descriptor.label,
                subtitle = subtitleFor(descriptor, nativeRates[descriptor.id]),
                isBuiltInMic = descriptor.kind == AudioDeviceKind.BUILT_IN_MIC,
            )
        }
    }

    private fun nativeRatesById(): Map<String, IntArray> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyMap()
        val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).associate { it.id.toString() to it.sampleRates }
    }

    private fun subtitleFor(descriptor: AudioDeviceDescriptor, rates: IntArray?): String {
        if (descriptor.kind == AudioDeviceKind.BUILT_IN_MIC) {
            return "Not a radio — capture will refuse this route"
        }
        val kindLabel = when (descriptor.kind) {
            AudioDeviceKind.USB_DEVICE -> "USB"
            AudioDeviceKind.WIRED_HEADSET -> "3.5 mm"
            AudioDeviceKind.BLUETOOTH -> "Bluetooth"
            AudioDeviceKind.BUILT_IN_MIC, AudioDeviceKind.UNKNOWN -> "Unknown"
        }
        val rateText = rates?.takeIf { it.isNotEmpty() }
            ?.let { hz -> "${formatKhz(hz.min())} kHz native · resampled to $OUTPUT_RATE_KHZ kHz" }
        return if (rateText != null) "$kindLabel · $rateText" else kindLabel
    }

    private fun formatKhz(hz: Int): String {
        val khz = hz / KHZ_DIVISOR
        return if (hz % KHZ_DIVISOR == 0) khz.toString() else "%.1f".format(hz / KHZ_DIVISOR.toDouble())
    }

    private companion object {
        const val OUTPUT_RATE_KHZ = 16
        const val KHZ_DIVISOR = 1_000
    }
}
