package org.ort.app.ui.setup

import android.content.SharedPreferences
import androidx.core.content.edit
import org.ort.core.capture.CaptureMode
import org.ort.core.capture.RigTransportKind

/**
 * What the guided setup sequence has configured so far (R-080..R-084; D33/P19 WPD adds the
 * capture-mode axis) — one property per fact a step records, persisted across process death so
 * "resumed at the first unverified step" (`Flow-Setup.dc.html`) survives the app being killed
 * mid-sequence, not just an in-memory back-navigation.
 *
 * [micRequested] carries forward exactly the bookkeeping `MainActivity`'s pre-WP9 flow already
 * established (`shouldShowRequestPermissionRationale` alone cannot distinguish "never asked" from
 * "denied twice, permanently" — see `MicrophoneScreens.kt`'s doc comment for the full reasoning).
 *
 * **D33 additions (P19/WPD).** [captureMode] is the S00 axis FR-CAP-8 introduces; [radioChoice]
 * and [rigId] are two views of the *same* S09 choice kept side by side rather than merged — the
 * former is the pre-existing coarse bucket (`TH_D75A`/`OTHER_CAT_RIG`/`NONE`) every debug scenario
 * fixture (`app/src/debug/.../Scenarios.kt`, outside this package's ownership) and
 * [SetupStateMachine]'s `RADIO` gate already key off unchanged; the latter is the precise
 * `:rig` `RigCatalogueEntry.id` S09b/S12 need to actually address a specific descriptor (a build
 * with more than one "other CAT rig" installed cannot be told apart by [radioChoice] alone).
 * [modeOverriddenAudio]/[modeOverriddenRig] record whether the operator changed a preset axis away
 * from what [org.ort.core.capture.CaptureModePresets.presetsFor] proposed (FR-CAP-9) — read by S04/S09b
 * to hide their own preset chip once overridden, and by S12's Mode row to phrase the value line.
 */
@Suppress("TooManyFunctions")
public interface SetupStore {
    public var welcomeSeen: Boolean
    public var micRequested: Boolean
    public var notificationsSkipped: Boolean
    public var selectedInputId: String?
    public var selectedInputLabel: String?
    public var inputVerified: Boolean
    public var verifiedNativeRateHz: Int?
    public var verifiedResamplerIdentity: String?
    public var levelInBand: Boolean
    public var levelPeakDbfs: Double?
    public var overnightStepSeen: Boolean
    public var radioChoice: RadioChoice?
    public var manualFrequencyHz: Long?
    public var setupComplete: Boolean

    /** D33/FR-CAP-8 — the S00 choice. `null` until S00 is walked. */
    public var captureMode: CaptureMode?

    /** S02c's "Not now — use USB instead" (Bluetooth mode only) — see [SetupStateMachine]'s own
     * doc comment for why this sits beside the mode flip rather than replacing it. */
    public var bluetoothPermissionDeclined: Boolean

    /** The precise `:rig` `RigCatalogueEntry.id` chosen at S09 — see this interface's own doc
     * comment for why this is not simply folded into [radioChoice]. */
    public var rigId: String?

    /** The rig-control transport chosen at S09b (FR-RIG-13) — independent of [selectedInputId]'s
     * audio route by design; `null` until S09b is walked (or when [radioChoice] is
     * [RadioChoice.NONE], which never reaches S09b at all). */
    public var rigTransport: RigTransportKind?

    /** The paired-device address chosen at S10b (Bluetooth transport only). */
    public var rigBluetoothAddress: String?

    /** Whether S10b's open -> identify -> verify checklist has reached a state the operator was
     * allowed to continue from for [rigBluetoothAddress] — [RigLinkState.Verified], or R-1014's
     * [RigLinkState.VerifyTimedOut] (partial success; [RigLinkState.IdentifyTimedOut] never sets
     * this, since `Continue` stays disabled there) — the resumable half of "Bluetooth rig link
     * established" ([SetupStateMachine]'s `RIG_BLUETOOTH` gate clears once this is true). This flag
     * alone does not distinguish the two accepted cases — [SetupActivity]'s own in-memory
     * `rigLinkMissingCapabilities` (not persisted, R-1014's own doc comment explains why) is what a
     * live S11 visit reads to tell a full verification from a partial one. */
    public var rigBluetoothVerified: Boolean

    /** `true` once the operator has changed [selectedInputId] away from what
     * [org.ort.core.capture.CaptureModePresets.presetsFor] proposed for [captureMode] (FR-CAP-9). */
    public var modeOverriddenAudio: Boolean

    /** `true` once the operator has changed [rigTransport] away from what
     * [org.ort.core.capture.CaptureModePresets.presetsFor] proposed for [captureMode] (FR-CAP-9). */
    public var modeOverriddenRig: Boolean

    /** The immutable view [SetupStateMachine.stepFor] decides against. */
    public fun snapshot(): SetupSnapshot = SetupSnapshot(
        welcomeSeen = welcomeSeen,
        captureMode = captureMode,
        bluetoothPermissionDeclined = bluetoothPermissionDeclined,
        notificationsSkipped = notificationsSkipped,
        selectedInputId = selectedInputId,
        inputVerified = inputVerified,
        levelInBand = levelInBand,
        overnightStepSeen = overnightStepSeen,
        radioChoice = radioChoice,
        rigTransport = rigTransport,
        rigBluetoothVerified = rigBluetoothVerified,
        setupComplete = setupComplete,
    )

    /** Clears everything the input step decided — used when the operator picks a different route
     * from [SetupStep.ROUTE_MISMATCH] or re-verifies, so a stale verified/level state never
     * survives a changed selection (constitution IV: a route that is not the selected device
     * halts capture; the same must be true of a route the operator has since changed). */
    public fun clearInputVerification() {
        inputVerified = false
        verifiedNativeRateHz = null
        verifiedResamplerIdentity = null
        levelInBand = false
        levelPeakDbfs = null
    }
}

/** The real, `SharedPreferences`-backed [SetupStore] — the same preferences file
 * (`org.ort.app.setup`) and mic/notifications keys the pre-WP9 `MainActivity` used, so an
 * in-progress install upgrading into WP9's sequence does not repeat a step it already resolved. */
@Suppress("TooManyFunctions")
public class SharedPreferencesSetupStore(private val prefs: SharedPreferences) : SetupStore {

    override var welcomeSeen: Boolean by BooleanPref(KEY_WELCOME_SEEN, default = false)
    override var micRequested: Boolean by BooleanPref(KEY_MIC_REQUESTED, default = false)
    override var notificationsSkipped: Boolean by BooleanPref(KEY_NOTIFICATIONS_SKIPPED, default = false)
    override var selectedInputId: String? by StringPref(KEY_SELECTED_INPUT_ID)
    override var selectedInputLabel: String? by StringPref(KEY_SELECTED_INPUT_LABEL)
    override var inputVerified: Boolean by BooleanPref(KEY_INPUT_VERIFIED, default = false)
    override var verifiedNativeRateHz: Int? by IntPref(KEY_VERIFIED_NATIVE_RATE)
    override var verifiedResamplerIdentity: String? by StringPref(KEY_RESAMPLER_IDENTITY)
    override var levelInBand: Boolean by BooleanPref(KEY_LEVEL_IN_BAND, default = false)
    override var levelPeakDbfs: Double? by DoublePref(KEY_LEVEL_PEAK_DBFS)
    override var overnightStepSeen: Boolean by BooleanPref(KEY_OVERNIGHT_SEEN, default = false)
    override var radioChoice: RadioChoice? by EnumPref(KEY_RADIO_CHOICE, RadioChoice::valueOf)
    override var manualFrequencyHz: Long? by LongPref(KEY_MANUAL_FREQUENCY_HZ)
    override var setupComplete: Boolean by BooleanPref(KEY_SETUP_COMPLETE, default = false)
    override var captureMode: CaptureMode? by EnumPref(KEY_CAPTURE_MODE, CaptureMode::valueOf)
    override var bluetoothPermissionDeclined: Boolean by BooleanPref(KEY_BLUETOOTH_PERMISSION_DECLINED, default = false)
    override var rigId: String? by StringPref(KEY_RIG_ID)
    override var rigTransport: RigTransportKind? by EnumPref(KEY_RIG_TRANSPORT, RigTransportKind::valueOf)
    override var rigBluetoothAddress: String? by StringPref(KEY_RIG_BLUETOOTH_ADDRESS)
    override var rigBluetoothVerified: Boolean by BooleanPref(KEY_RIG_BLUETOOTH_VERIFIED, default = false)
    override var modeOverriddenAudio: Boolean by BooleanPref(KEY_MODE_OVERRIDDEN_AUDIO, default = false)
    override var modeOverriddenRig: Boolean by BooleanPref(KEY_MODE_OVERRIDDEN_RIG, default = false)

    private inner class BooleanPref(val key: String, val default: Boolean) :
        kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = prefs.getBoolean(key, default)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) {
            prefs.edit { putBoolean(key, value) }
        }
    }

    private inner class StringPref(val key: String) : kotlin.properties.ReadWriteProperty<Any?, String?> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = prefs.getString(key, null)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: String?) {
            prefs.edit { putString(key, value) }
        }
    }

    private inner class IntPref(val key: String) : kotlin.properties.ReadWriteProperty<Any?, Int?> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) =
            if (prefs.contains(key)) prefs.getInt(key, 0) else null
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Int?) {
            prefs.edit { if (value == null) remove(key) else putInt(key, value) }
        }
    }

    private inner class LongPref(val key: String) : kotlin.properties.ReadWriteProperty<Any?, Long?> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) =
            if (prefs.contains(key)) prefs.getLong(key, 0L) else null
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Long?) {
            prefs.edit { if (value == null) remove(key) else putLong(key, value) }
        }
    }

    private inner class DoublePref(val key: String) : kotlin.properties.ReadWriteProperty<Any?, Double?> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) =
            if (prefs.contains(key)) Double.fromBits(prefs.getLong(key, 0L)) else null
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Double?) {
            prefs.edit { if (value == null) remove(key) else putLong(key, value.toRawBits()) }
        }
    }

    private inner class EnumPref<E : Enum<E>>(val key: String, val valueOf: (String) -> E) :
        kotlin.properties.ReadWriteProperty<Any?, E?> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): E? =
            prefs.getString(key, null)?.let(valueOf)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: E?) {
            prefs.edit { if (value == null) remove(key) else putString(key, value.name) }
        }
    }

    public companion object {
        public const val PREFS_NAME: String = "org.ort.app.setup"
        public const val KEY_WELCOME_SEEN: String = "welcome_seen"
        public const val KEY_MIC_REQUESTED: String = "mic_requested"
        public const val KEY_NOTIFICATIONS_SKIPPED: String = "notifications_skipped"
        public const val KEY_SELECTED_INPUT_ID: String = "selected_input_id"
        public const val KEY_SELECTED_INPUT_LABEL: String = "selected_input_label"
        public const val KEY_INPUT_VERIFIED: String = "input_verified"
        public const val KEY_VERIFIED_NATIVE_RATE: String = "verified_native_rate"
        public const val KEY_RESAMPLER_IDENTITY: String = "verified_resampler_identity"
        public const val KEY_LEVEL_IN_BAND: String = "level_in_band"
        public const val KEY_LEVEL_PEAK_DBFS: String = "level_peak_dbfs"
        public const val KEY_OVERNIGHT_SEEN: String = "overnight_step_seen"
        public const val KEY_RADIO_CHOICE: String = "radio_choice"
        public const val KEY_MANUAL_FREQUENCY_HZ: String = "manual_frequency_hz"
        public const val KEY_SETUP_COMPLETE: String = "setup_complete"
        public const val KEY_CAPTURE_MODE: String = "capture_mode"
        public const val KEY_BLUETOOTH_PERMISSION_DECLINED: String = "bluetooth_permission_declined"
        public const val KEY_RIG_ID: String = "rig_id"
        public const val KEY_RIG_TRANSPORT: String = "rig_transport"
        public const val KEY_RIG_BLUETOOTH_ADDRESS: String = "rig_bluetooth_address"
        public const val KEY_RIG_BLUETOOTH_VERIFIED: String = "rig_bluetooth_verified"
        public const val KEY_MODE_OVERRIDDEN_AUDIO: String = "mode_overridden_audio"
        public const val KEY_MODE_OVERRIDDEN_RIG: String = "mode_overridden_rig"
    }
}

/** The behavioural fake (constitution II) — a plain in-memory [SetupStore] for tests that need one
 * without a real `Context`/`SharedPreferences` (screen-level Robolectric tests, [SetupActivity]
 * unit tests that stub the store directly rather than reading real preferences). One constructor
 * parameter per [SetupStore] property, each independently defaulted, is the point of this class —
 * suppressed rather than restructured into a builder for a fake this small. */
@Suppress("LongParameterList")
public class InMemorySetupStore(
    override var welcomeSeen: Boolean = false,
    override var micRequested: Boolean = false,
    override var notificationsSkipped: Boolean = false,
    override var selectedInputId: String? = null,
    override var selectedInputLabel: String? = null,
    override var inputVerified: Boolean = false,
    override var verifiedNativeRateHz: Int? = null,
    override var verifiedResamplerIdentity: String? = null,
    override var levelInBand: Boolean = false,
    override var levelPeakDbfs: Double? = null,
    override var overnightStepSeen: Boolean = false,
    override var radioChoice: RadioChoice? = null,
    override var manualFrequencyHz: Long? = null,
    override var setupComplete: Boolean = false,
    override var captureMode: CaptureMode? = null,
    override var bluetoothPermissionDeclined: Boolean = false,
    override var rigId: String? = null,
    override var rigTransport: RigTransportKind? = null,
    override var rigBluetoothAddress: String? = null,
    override var rigBluetoothVerified: Boolean = false,
    override var modeOverriddenAudio: Boolean = false,
    override var modeOverriddenRig: Boolean = false,
) : SetupStore
