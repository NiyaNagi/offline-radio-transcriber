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
import org.ort.core.capture.AudioRouteKind

/**
 * One row S04's list renders (`Setup-Input.dc.html`). [id] is [AudioDeviceDescriptor.id] verbatim
 * — the same id [org.ort.capture.android.RouteVerifier] compares against, so selecting a row and
 * later verifying it are guaranteed to mean the same device. [advisory] (FR-CAP-2b, D33/D34 —
 * previously `refused`, and before that `isBuiltInMic`) says what choosing this route costs, never
 * whether it is permitted; see [RouteAdvisory]. [icon] is `null` when no guide §7 icon exists yet for the
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
    public val advisory: RouteAdvisory?,
    public val typeLabel: String,
    public val icon: ImageVector? = null,
    /** D33/FR-CAP-9: the `:core` route kind this option resolves to — what
     * [org.ort.core.capture.CaptureModePresets.presetsFor] compares against to find "the first
     * route of the preset kind" for [ModeScreen]'s preset application (`SetupActivity.onChooseMode`). */
    public val routeKind: AudioRouteKind = AudioRouteKind.UNKNOWN,
)

/**
 * **FR-CAP-2b: what a route discloses, never whether it is allowed.** Every enumerated input is
 * selectable; an advisory says what the operator is taking on by choosing it.
 *
 * This replaces the earlier `refused: Boolean`, which was wrong in both directions at once — it
 * refused the built-in mic that FR-CAP-3a explicitly permits, and it waved through the Bluetooth
 * route that CON-CAP-1 then forbade. One flag could not express the difference between "this is a
 * fully supported mode with a consequence you must know about" (D33's local-microphone mode),
 * "this works but costs signal quality" (D34's Bluetooth audio) and "the app genuinely cannot
 * tell what this is". Collapsing all three into *refused* is what let a legitimate mode read to
 * the operator as a blocked one.
 *
 * `null` — the cabled routes — is the absence of anything to disclose, not a fourth kind.
 */
public enum class RouteAdvisory {
    /** The built-in mic: real audio, but the room's, not the radio's (FR-CAP-3a, FR-CAP-10). */
    ROOM_AUDIO,

    /** Bluetooth audio: permitted by D34, degraded by HFP/mSBC, marked on every session it
     * produces so its accuracy never merges into the wired path's (CON-CAP-1, FR-CAP-11). */
    BLUETOOTH_DEGRADED,

    /** The device type is not one this build recognises. The honest statement is that the app
     * cannot tell — never an assertion about what the device is not (FR-CAP-2b). */
    UNRECOGNISED_TYPE,

    /** A route the platform exposes that is not a capture source at all, such as the telephony
     * path. Declined for a stated, specific reason, as FR-CAP-2b requires. */
    NOT_A_CAPTURE_SOURCE,
}

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
                advisory = resolved.advisory,
                typeLabel = resolved.label,
                icon = resolved.icon,
                routeKind = resolved.routeKind,
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

    /**
     * FR-CAP-2b: the type, then the real native rate where one is reported, then the advisory —
     * in that order, so the row always leads with what the device *is*. An advisory is appended to
     * a full description rather than replacing it: the operator choosing the built-in mic still
     * wants to know its native rate, and the old code threw that away along with the choice.
     */
    private fun subtitleFor(resolved: ResolvedDeviceType, rates: IntArray?): String {
        val rateText = rates?.takeIf { it.isNotEmpty() }
            ?.let { hz -> "${formatKhz(hz.min())} kHz native · resampled to $OUTPUT_RATE_KHZ kHz" }
        val head = if (rateText != null) "${resolved.label} · $rateText" else resolved.label
        val note = when (resolved.advisory) {
            // FR-CAP-3a/FR-CAP-10: a legitimate, fully supported mode, stated as a consequence
            // rather than a warning — this is what the operator is choosing, not a reason not to.
            RouteAdvisory.ROOM_AUDIO ->
                "captures the room, not the radio — every session is marked as such"
            // CON-CAP-1 as amended by D34: offered, and never without its cost.
            RouteAdvisory.BLUETOOTH_DEGRADED ->
                "narrowband Bluetooth voice link — lower accuracy than a cable, and marked on every session"
            RouteAdvisory.UNRECOGNISED_TYPE ->
                "the app cannot identify this device type"
            RouteAdvisory.NOT_A_CAPTURE_SOURCE ->
                "the call audio path, not something capture can read"
            null -> null
        }
        return if (note != null) "$head — $note" else head
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
 * does not — a real, reported gap, never a substituted wrong icon), and what choosing it discloses
 * ([RouteAdvisory], `null` for a route with nothing to disclose). */
internal data class ResolvedDeviceType(
    val label: String,
    val icon: ImageVector?,
    val advisory: RouteAdvisory?,
    val routeKind: AudioRouteKind = AudioRouteKind.UNKNOWN,
)

/**
 * R-122 (validator finding, register R-120..R-125): names a device by its real Android type,
 * `built-in mic` / `telephony` / `USB audio` / `wired headset` / `Bluetooth` per the brief, and
 * attaches the [RouteAdvisory] each type carries.
 *
 * **The advisory column is the corrected half (FR-CAP-2b).** It previously held one boolean,
 * `isRadioCapable`, which was wrong for two of the six rows in opposite directions: the built-in
 * mic was marked incapable although FR-CAP-3a permits it by name, and Bluetooth was marked capable
 * although CON-CAP-1 then banned it outright. Both are single-token errors in a lookup table that
 * no rendering test could see, which is why the replacement encodes *what to say* rather than
 * *whether to allow* — there is no longer a value here that can silently block a supported mode.
 *
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
        AudioDeviceInfo.TYPE_BUILTIN_MIC ->
            ResolvedDeviceType(
                "Built-in microphone",
                OrtIcons.builtInMic,
                RouteAdvisory.ROOM_AUDIO,
                AudioRouteKind.BUILT_IN_MIC,
            )
        AudioDeviceInfo.TYPE_TELEPHONY ->
            ResolvedDeviceType("Telephony", OrtIcons.call, RouteAdvisory.NOT_A_CAPTURE_SOURCE, AudioRouteKind.UNKNOWN)
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY ->
            ResolvedDeviceType("USB audio", OrtIcons.usbAudio, null, AudioRouteKind.USB)
        AudioDeviceInfo.TYPE_WIRED_HEADSET ->
            ResolvedDeviceType("Wired headset", OrtIcons.headset, null, AudioRouteKind.WIRED_HEADSET)
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
            ResolvedDeviceType("Bluetooth", null, RouteAdvisory.BLUETOOTH_DEGRADED, AudioRouteKind.BLUETOOTH_SCO)
        else -> ResolvedDeviceType(
            "Unrecognised device type $type",
            genericDevice,
            RouteAdvisory.UNRECOGNISED_TYPE,
            AudioRouteKind.UNKNOWN,
        )
    }

    fun forKind(kind: AudioDeviceKind): ResolvedDeviceType = when (kind) {
        AudioDeviceKind.BUILT_IN_MIC ->
            ResolvedDeviceType(
                "Built-in microphone",
                OrtIcons.builtInMic,
                RouteAdvisory.ROOM_AUDIO,
                AudioRouteKind.BUILT_IN_MIC,
            )
        AudioDeviceKind.USB_DEVICE -> ResolvedDeviceType("USB audio", OrtIcons.usbAudio, null, AudioRouteKind.USB)
        AudioDeviceKind.WIRED_HEADSET ->
            ResolvedDeviceType("Wired headset", OrtIcons.headset, null, AudioRouteKind.WIRED_HEADSET)
        AudioDeviceKind.BLUETOOTH ->
            ResolvedDeviceType("Bluetooth", null, RouteAdvisory.BLUETOOTH_DEGRADED, AudioRouteKind.BLUETOOTH_SCO)
        AudioDeviceKind.UNKNOWN ->
            ResolvedDeviceType("Unknown", genericDevice, RouteAdvisory.UNRECOGNISED_TYPE, AudioRouteKind.UNKNOWN)
    }
}
