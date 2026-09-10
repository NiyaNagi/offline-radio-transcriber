package org.ort.pipeline.rig

import android.content.SharedPreferences
import org.ort.core.capture.CaptureMode
import org.ort.pipeline.capture.CaptureState
import org.ort.rig.NullRigModule
import org.ort.rig.RigTransportKind

/**
 * WPC2 (FR-CAP-12, AC-131): where the capture mode/rig choice for the **next** session start is
 * read from, and where settings writes a change to it. WPD's `SetupStore` and WPE's Settings-Mode
 * screen (both `:app`, outside this package's ownership) are adapted to this interface — it is not
 * adapted to either of them — so [org.ort.pipeline.capture.RealCaptureService] has exactly one
 * contract to read at session start regardless of which screen last wrote to it.
 *
 * **A mode change never lands mid-session** (FR-CAP-12): [update] itself checks
 * [CaptureState.isCapturing], so the freeze rule cannot be forgotten by a caller — a write made
 * while capture is running never touches [current]; it is recorded as [pendingConfiguration]
 * instead, and [activateForNewSession] is the *only* place a pending write is ever promoted, once,
 * at the moment a new session is about to start (AC-131: "start with mode A, change to B
 * mid-session, the running session's row still reads A, stop, start again, the new session's row
 * reads B").
 */
public interface CaptureConfigurationStore {

    /** The configuration in force right now — what the last [activateForNewSession] call (or an
     * [update] made while nothing was capturing) returned. Never itself changed by [update] while
     * capture is running. */
    public fun current(): CaptureConfiguration

    /** Non-null exactly when [update] recorded a change while capture was running that has not yet
     * taken effect. CF11's amber banner (E2-F02) reads this directly — it is not re-derived from
     * [current] plus some other signal. */
    public fun pendingConfiguration(): CaptureConfiguration?

    /**
     * Writes a new configuration. If capture is not running ([CaptureState.isCapturing] is
     * `false`), [current] reflects it immediately and any stale [pendingConfiguration] is cleared.
     * If capture **is** running, [current] is left exactly as it was for the session already in
     * progress, and [configuration] becomes the new [pendingConfiguration] — applied only by the
     * next [activateForNewSession] call (FR-CAP-12, AC-131).
     */
    public fun update(configuration: CaptureConfiguration)

    /**
     * Called once, by capture, at the very start of every session — before anything else about the
     * session is decided. Promotes [pendingConfiguration] to [current] if one exists (clearing it
     * in the same step), then returns [current]. The caller freezes the returned value for the
     * whole lifetime of the session it is starting; this store is not consulted again until the
     * next session's own call to this method (AC-131).
     */
    public fun activateForNewSession(): CaptureConfiguration
}

/**
 * The behavioural fake (constitution II) — a plain in-memory [CaptureConfigurationStore] for tests
 * that need one without a real `Context`/`SharedPreferences`. [isCapturing] is injectable so a test
 * can drive the freeze rule directly rather than through the real, process-wide
 * [CaptureState] singleton.
 */
public class InMemoryCaptureConfigurationStore(
    initial: CaptureConfiguration = CaptureConfiguration.DEFAULT,
    private val isCapturing: () -> Boolean = { CaptureState.isCapturing },
) : CaptureConfigurationStore {

    @Volatile
    private var currentConfig: CaptureConfiguration = initial

    @Volatile
    private var pending: CaptureConfiguration? = null

    override fun current(): CaptureConfiguration = currentConfig

    override fun pendingConfiguration(): CaptureConfiguration? = pending

    override fun update(configuration: CaptureConfiguration) {
        if (isCapturing()) {
            pending = configuration
        } else {
            currentConfig = configuration
            pending = null
        }
    }

    override fun activateForNewSession(): CaptureConfiguration {
        pending?.let {
            currentConfig = it
            pending = null
        }
        return currentConfig
    }
}

/**
 * The real, `SharedPreferences`-backed [CaptureConfigurationStore]. Stores the active and the
 * pending configuration under separate key prefixes so a process restart mid-"pending" (the app
 * killed after a settings change but before the next session starts) still resolves correctly on
 * the next [activateForNewSession] call — nothing here depends on in-memory state surviving.
 */
public class SharedPreferencesCaptureConfigurationStore(
    private val prefs: SharedPreferences,
    private val isCapturing: () -> Boolean = { CaptureState.isCapturing },
) : CaptureConfigurationStore {

    override fun current(): CaptureConfiguration = readFrom(PREFIX_CURRENT) ?: CaptureConfiguration.DEFAULT

    override fun pendingConfiguration(): CaptureConfiguration? =
        if (prefs.getBoolean(KEY_HAS_PENDING, false)) readFrom(PREFIX_PENDING) else null

    override fun update(configuration: CaptureConfiguration) {
        if (isCapturing()) {
            writeTo(PREFIX_PENDING, configuration)
            prefs.edit().putBoolean(KEY_HAS_PENDING, true).apply()
        } else {
            writeTo(PREFIX_CURRENT, configuration)
            prefs.edit().putBoolean(KEY_HAS_PENDING, false).apply()
        }
    }

    override fun activateForNewSession(): CaptureConfiguration {
        if (prefs.getBoolean(KEY_HAS_PENDING, false)) {
            val toActivate = readFrom(PREFIX_PENDING) ?: CaptureConfiguration.DEFAULT
            writeTo(PREFIX_CURRENT, toActivate)
            prefs.edit().putBoolean(KEY_HAS_PENDING, false).apply()
        }
        return current()
    }

    private fun readFrom(prefix: String): CaptureConfiguration? {
        val modeName = prefs.getString(key(prefix, KEY_MODE), null) ?: return null
        val mode = runCatching { CaptureMode.valueOf(modeName) }.getOrNull() ?: return null
        val selectedInputId = prefs.getString(key(prefix, KEY_SELECTED_INPUT_ID), null)
        val rigId = prefs.getString(key(prefix, KEY_RIG_ID), null) ?: NullRigModule.ID
        val rigTransportKind = prefs.getString(key(prefix, KEY_RIG_TRANSPORT_KIND), null)
            ?.let { name -> runCatching { RigTransportKind.valueOf(name) }.getOrNull() }
        val rigParams = decodeParams(prefs.getString(key(prefix, KEY_RIG_PARAMS), null))
        return CaptureConfiguration(mode, selectedInputId, rigId, rigTransportKind, rigParams)
    }

    private fun writeTo(prefix: String, config: CaptureConfiguration) {
        prefs.edit()
            .putString(key(prefix, KEY_MODE), config.mode.name)
            .putString(key(prefix, KEY_SELECTED_INPUT_ID), config.selectedInputId)
            .putString(key(prefix, KEY_RIG_ID), config.rigId)
            .putString(key(prefix, KEY_RIG_TRANSPORT_KIND), config.rigTransportKind?.name)
            .putString(key(prefix, KEY_RIG_PARAMS), encodeParams(config.rigParams))
            .apply()
    }

    private fun key(prefix: String, name: String): String = "$prefix.$name"

    /** A minimal, dependency-free encoding — this module adds no serialization library dependency
     * for what is, in practice, almost always an empty map (baud/parity overrides are rare). A rig
     * config key is always a plain identifier (`baud`, `dataBits`, ...); `=` and `;` are reserved
     * as the value/entry separators on that basis. */
    private fun encodeParams(params: Map<String, String>): String =
        params.entries.joinToString(separator = ENTRY_SEPARATOR) { (k, v) -> "$k$KEY_VALUE_SEPARATOR$v" }

    private fun decodeParams(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return raw.split(ENTRY_SEPARATOR).mapNotNull { entry ->
            val idx = entry.indexOf(KEY_VALUE_SEPARATOR)
            if (idx < 0) null else entry.substring(0, idx) to entry.substring(idx + KEY_VALUE_SEPARATOR.length)
        }.toMap()
    }

    public companion object {
        public const val PREFS_NAME: String = "org.ort.pipeline.capture_configuration"

        private const val PREFIX_CURRENT = "current"
        private const val PREFIX_PENDING = "pending"
        private const val KEY_HAS_PENDING = "has_pending"
        private const val KEY_MODE = "mode"
        private const val KEY_SELECTED_INPUT_ID = "selected_input_id"
        private const val KEY_RIG_ID = "rig_id"
        private const val KEY_RIG_TRANSPORT_KIND = "rig_transport_kind"
        private const val KEY_RIG_PARAMS = "rig_params"
        private const val ENTRY_SEPARATOR = ";"
        private const val KEY_VALUE_SEPARATOR = "="
    }
}
