package org.ort.app.ui.setup

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.OrtIcons
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioDeviceKind
import org.ort.capture.android.AudioIo

/**
 * One row S04's list renders (`Setup-Input.dc.html`). [id] is [AudioDeviceDescriptor.id] verbatim
 * — the same id [org.ort.capture.android.RouteVerifier] compares against, so selecting a row and
 * later verifying it are guaranteed to mean the same device. [refused] (validator finding, register
 * R-120..R-125: was `isBuiltInMic`) is true for every source that is never a radio, not only the
 * built-in mic — see [DeviceTypeNaming]. [icon] is `null` when no guide §7 icon exists yet for the
 * resolved type (a real, reported gap — see [DeviceTypeNaming]'s doc comment — never a wrong icon
 * standing in for a missing one).
 *
 * [typeLabel] (R-222, validator pass 2): the same real, resolved type name [subtitle] is built
 * from, kept as its own field rather than only baked into the subtitle string — [SetupActivity]
 * carries it forward to S06's mismatch facts when the chosen route is the one that mismatched, so
 * the richer resolution this class already did at S04 is not lost by the time
 * `RouteCheckState.Mismatch` (which only carries the raw, coarser
 * [org.ort.capture.android.AudioDeviceDescriptor.kind]) reaches [RouteMismatchScreen].
 */
public data class InputRouteOption(
    public val id: String,
    public val label: String,
    public val subtitle: String,
    public val refused: Boolean,
    public val typeLabel: String,
    public val icon: ImageVector? = null,
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
        val androidTypes = typesById()
        return io.availableDevices().map { descriptor ->
            val resolved = androidTypes[descriptor.id]?.let { DeviceTypeNaming.forAndroidType(it) }
                ?: DeviceTypeNaming.forKind(descriptor.kind)
            InputRouteOption(
                id = descriptor.id,
                label = descriptor.label,
                subtitle = subtitleFor(resolved, nativeRates[descriptor.id]),
                refused = !resolved.isRadioCapable,
                typeLabel = resolved.label,
                icon = resolved.icon,
            )
        }
    }

    private fun nativeRatesById(): Map<String, IntArray> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyMap()
        return devices().associate { it.id.toString() to it.sampleRates }
    }

    /**
     * R-122 (validator finding): [AudioDeviceDescriptor.kind] alone is too coarse — capture-android's
     * [AudioDeviceKind] collapses every type it does not explicitly recognise (telephony among them,
     * confirmed against the validator's own emulator screenshot: three of four rows on a one-mic
     * device all read "Unknown") into [AudioDeviceKind.UNKNOWN]. Rather than widen [AudioDeviceKind]
     * itself — a `:capture-android` file, outside this row's owned files — the *real*
     * `android.media.AudioDeviceInfo.type` is read directly here, by id, the same best-effort,
     * falls-back-to-nothing pattern [nativeRatesById] already uses; [DeviceTypeNaming.forAndroidType]
     * then resolves it into a real name. Best-effort by design: [FakeAudioIo]'s synthetic device ids
     * never match a real `AudioManager` entry (there is none in a Robolectric unit test), so
     * [DeviceTypeNaming.forKind] is exactly what every existing test already exercises and continues
     * to pass unmodified.
     */
    private fun typesById(): Map<String, Int> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyMap()
        return devices().associate { it.id.toString() to it.type }
    }

    private fun devices(): List<AudioDeviceInfo> {
        val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
    }

    private fun subtitleFor(resolved: ResolvedDeviceType, rates: IntArray?): String {
        if (!resolved.isRadioCapable) {
            return "${resolved.label} — not a radio, capture will refuse this route"
        }
        val rateText = rates?.takeIf { it.isNotEmpty() }
            ?.let { hz -> "${formatKhz(hz.min())} kHz native · resampled to $OUTPUT_RATE_KHZ kHz" }
        return if (rateText != null) "${resolved.label} · $rateText" else resolved.label
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

/** What S04 (and, for the "chosen X · routed Y" line, [RouteMismatchScreen]) needs to describe one
 * device type honestly: a name, the guide §7 icon when one already exists for it (`null` when it
 * does not — a real, reported gap, never a substituted wrong icon), and whether it is plausibly a
 * radio at all. */
internal data class ResolvedDeviceType(val label: String, val icon: ImageVector?, val isRadioCapable: Boolean)

/**
 * R-122 (validator finding, register R-120..R-125): names a device by its real Android type,
 * `built-in mic` / `telephony` / `USB audio` / `wired headset` / `Bluetooth` per the brief, and
 * refuses every source that is not plausibly an external radio adapter — not only the built-in mic.
 * `WP2`'s [OrtIcons] has no Bluetooth icon yet (confirmed by reading `OrtIcons.kt` before writing
 * this) — [forAndroidType]/[forKind]'s `Bluetooth` case returns `icon = null` for it rather than
 * reusing an unrelated icon, a gap this package's own report names explicitly. R-221 (validator
 * pass 2) is the narrower case: an *unrecognised* type row had no icon at all, breaking the icon
 * column's alignment against every other row — [genericDevice] fixes that specific case (a real,
 * generic device outline, not a stand-in for any one type), built locally the same guide §7 way
 * `OrtIcons`' own private `buildIcon`/`strokePath` do (that object's helpers are `private`,
 * confirmed by reading `OrtIcons.kt` before writing this, so not reachable from this row's files).
 */
internal object DeviceTypeNaming {

    /** A plain rounded-rectangle-with-two-lines outline — guide §7's 24-unit viewBox, round
     * caps/joins, tinted by the caller, same as every `OrtIcons` entry — for a device type this
     * package genuinely could not identify. Never implies USB, wired, or any other specific kind. */
    val genericDevice: ImageVector by lazy {
        ImageVector.Builder(
            name = "genericDevice",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes("M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z"),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = addPathNodes("M8 9h8M8 13h5"),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    fun forAndroidType(type: Int): ResolvedDeviceType = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> ResolvedDeviceType("Built-in microphone", OrtIcons.builtInMic, false)
        AudioDeviceInfo.TYPE_TELEPHONY -> ResolvedDeviceType("Telephony", OrtIcons.call, false)
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY ->
            ResolvedDeviceType("USB audio", OrtIcons.usbAudio, true)
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> ResolvedDeviceType("Wired headset", OrtIcons.headset, true)
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> ResolvedDeviceType("Bluetooth", null, true)
        else -> ResolvedDeviceType("Unrecognised device type $type", genericDevice, false)
    }

    fun forKind(kind: AudioDeviceKind): ResolvedDeviceType = when (kind) {
        AudioDeviceKind.BUILT_IN_MIC -> ResolvedDeviceType("Built-in microphone", OrtIcons.builtInMic, false)
        AudioDeviceKind.USB_DEVICE -> ResolvedDeviceType("USB audio", OrtIcons.usbAudio, true)
        AudioDeviceKind.WIRED_HEADSET -> ResolvedDeviceType("Wired headset", OrtIcons.headset, true)
        AudioDeviceKind.BLUETOOTH -> ResolvedDeviceType("Bluetooth", null, true)
        AudioDeviceKind.UNKNOWN -> ResolvedDeviceType("Unknown", genericDevice, false)
    }
}
