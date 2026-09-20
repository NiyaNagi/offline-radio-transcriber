package org.ort.pipeline.alerts

import java.io.File

/**
 * Build-plan P31 (FR-ALR-1, FR-ALR-6): where every watched callsign, keyword and frequency lives,
 * plus the one master [alertsEnabled] switch the operator can use to turn the whole feature off
 * (functional spec §7.19's own "settings screen to add, edit and remove watches, and to turn them
 * off entirely"). Deliberately its own small store, not a new `:data` table or a
 * `SettingsStore.kt` addition — the identical reasoning
 * `org.ort.app.fieldreport.settings.SharedPreferencesFieldReportSettingsStore`'s own doc comment
 * gives for keeping a feature's settings in its own package.
 */
public interface AlertWatchStore {

    /** FR-ALR-6: `false` stops every watch from firing, without deleting any of them — the
     * operator's own "turn alerts off entirely" switch. */
    public var alertsEnabled: Boolean

    public fun list(): List<AlertWatch>

    /** [AlertWatch.id] must be unique — a caller mints it (e.g. [org.ort.core.Ulid.generate])
     * before calling this. */
    public fun add(watch: AlertWatch)

    /** Replaces the watch sharing [watch]'s own [AlertWatch.id]; a no-op if none does. */
    public fun update(watch: AlertWatch)

    public fun remove(id: String)
}

/** The behavioural fake (constitution II) — every [AlertEvaluationCoordinatorTest]/[AlertMatcherTest]
 * uses this rather than a real file. */
public class InMemoryAlertWatchStore(
    initial: List<AlertWatch> = emptyList(),
    override var alertsEnabled: Boolean = true,
) : AlertWatchStore {

    private val watches = LinkedHashMap<String, AlertWatch>().apply {
        initial.forEach { put(it.id, it) }
    }

    override fun list(): List<AlertWatch> = watches.values.toList()

    override fun add(watch: AlertWatch) {
        watches[watch.id] = watch
    }

    override fun update(watch: AlertWatch) {
        if (watches.containsKey(watch.id)) watches[watch.id] = watch
    }

    override fun remove(id: String) {
        watches.remove(id)
    }
}

/**
 * The real, file-backed [AlertWatchStore] — a plain `File`, not `SharedPreferences`, deliberately:
 * `:pipeline` has no `Context` at the one production seam that needs to construct this
 * ([org.ort.pipeline.passb.PassBFactory] takes a `filesDir: File` already, for
 * [org.ort.pipeline.passb.FlacSegmentAudioProvider]'s identical reason), and `:app`'s own Settings
 * screen can point at the exact same path via `File(context.filesDir, RELATIVE_PATH)` — one real
 * file both sides read and write, never a second, independently-scoped copy that could drift.
 *
 * One line per watch, `|`-delimited (a caller — `:app`'s own Settings screen — rejects `|` and
 * newlines from watch text before ever constructing one), plus one leading `ALERTS_ENABLED=`
 * line — the same small, hand-rolled, dependency-free
 * encoding `org.ort.pipeline.rig.CaptureConfigurationStore`'s own `SharedPreferences` variant uses
 * for its own rig params map, adapted to a flat file since there is no `SharedPreferences` here.
 * Every read tolerates a missing or empty file (a fresh install) as "no watches, alerts enabled"
 * — never a crash over a file that simply has not been written yet.
 */
public class FileBackedAlertWatchStore(private val file: File) : AlertWatchStore {

    override var alertsEnabled: Boolean
        get() = readAll().first
        set(value) = writeAll(value, readAll().second)

    override fun list(): List<AlertWatch> = readAll().second

    override fun add(watch: AlertWatch) {
        val (enabled, watches) = readAll()
        writeAll(enabled, watches + watch)
    }

    override fun update(watch: AlertWatch) {
        val (enabled, watches) = readAll()
        writeAll(enabled, watches.map { if (it.id == watch.id) watch else it })
    }

    override fun remove(id: String) {
        val (enabled, watches) = readAll()
        writeAll(enabled, watches.filterNot { it.id == id })
    }

    private fun readAll(): Pair<Boolean, List<AlertWatch>> {
        if (!file.exists()) return true to emptyList()
        var enabled = true
        val watches = mutableListOf<AlertWatch>()
        file.readLines().forEach { line ->
            if (line.isBlank()) return@forEach
            if (line.startsWith(ENABLED_PREFIX)) {
                enabled = line.removePrefix(ENABLED_PREFIX).toBooleanStrictOrNull() ?: true
                return@forEach
            }
            decodeWatch(line)?.let { watches += it }
        }
        return enabled to watches
    }

    private fun writeAll(enabled: Boolean, watches: List<AlertWatch>) {
        file.parentFile?.mkdirs()
        file.writeText(
            buildString {
                append(ENABLED_PREFIX).append(enabled).append('\n')
                watches.forEach { append(encodeWatch(it)).append('\n') }
            },
        )
    }

    private fun encodeWatch(watch: AlertWatch): String = when (watch) {
        is AlertWatch.Callsign -> listOf("CALLSIGN", watch.id, watch.callsign, watch.enabled).joinToString(SEPARATOR)
        is AlertWatch.Keyword -> listOf("KEYWORD", watch.id, watch.keyword, watch.enabled).joinToString(SEPARATOR)
        is AlertWatch.Frequency ->
            listOf("FREQUENCY", watch.id, watch.frequencyHz, watch.enabled).joinToString(SEPARATOR)
    }

    private fun decodeWatch(line: String): AlertWatch? {
        val parts = line.split(SEPARATOR)
        if (parts.size != 4) return null
        val kind = parts[0]
        val id = parts[1]
        val value = parts[2]
        val enabled = parts[3].toBooleanStrictOrNull() ?: return null
        return when (kind) {
            "CALLSIGN" -> AlertWatch.Callsign(id, value, enabled)
            "KEYWORD" -> AlertWatch.Keyword(id, value, enabled)
            "FREQUENCY" -> value.toLongOrNull()?.let { AlertWatch.Frequency(id, it, enabled) }
            else -> null
        }
    }

    public companion object {
        /** The one shared relative path both `:app`'s Settings screen and a future real
         * `:pipeline` production wiring (see this unit's own report — not yet wired into
         * `PassBFactory`) must construct this store against, so they read and write the same file. */
        public const val RELATIVE_PATH: String = "alerts/watches.txt"
        private const val ENABLED_PREFIX = "ALERTS_ENABLED="
        private const val SEPARATOR = "|"
    }
}
