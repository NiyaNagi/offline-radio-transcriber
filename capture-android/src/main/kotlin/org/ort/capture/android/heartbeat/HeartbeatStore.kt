package org.ort.capture.android.heartbeat

/** One 30-second liveness beat (technical design §5.6). */
public data class HeartbeatRecord(
    val sessionId: String,
    val monotonicNanos: Long,
    val wallMillis: Long,
    val samplePosition: Long,
)

/**
 * technical design §5.6: liveness is proven only by an actually-observed heartbeat, never by
 * `isIgnoringBatteryOptimizations()`, which lies on the reference device (NFR-8 → AC-65).
 * `:capture-android` cannot depend on `:data` (module graph), so this is a small dedicated
 * store rather than a Room table; `:pipeline` reads it back for the app's status surface and
 * unclean-end banner (AC-5).
 */
public interface HeartbeatStore {
    public fun write(record: HeartbeatRecord)
    public fun last(): HeartbeatRecord?

    /** Call exactly once, on a deliberate stop — marks the current session's end as clean. */
    public fun markCleanShutdown(sessionId: String)

    /** `true` when a heartbeat exists whose session never reached [markCleanShutdown] (AC-5). */
    public fun hadUncleanEnd(): Boolean

    public fun clear()
}

/** File-backed [HeartbeatStore]: one small text file, rewritten on every beat. */
public class FileHeartbeatStore(private val file: java.io.File) : HeartbeatStore {

    override fun write(record: HeartbeatRecord) {
        file.parentFile?.mkdirs()
        file.writeText(
            listOf(record.sessionId, record.monotonicNanos, record.wallMillis, record.samplePosition, "false")
                .joinToString("\n"),
        )
    }

    override fun last(): HeartbeatRecord? {
        if (!file.exists()) return null
        return try {
            val lines = file.readLines()
            if (lines.size < 4) return null
            HeartbeatRecord(lines[0], lines[1].toLong(), lines[2].toLong(), lines[3].toLong())
        } catch (e: java.io.FileNotFoundException) {
            // AC-5/FR-PLT-1: a TOCTOU race with exists() above -- a scenario reset, clear(), or a
            // real device's pm-clear can delete the file between the check and the read (another
            // coroutine polling this same store, technical design §5.6). A heartbeat that vanished
            // mid-read is honestly "no heartbeat", never an uncaught crash into the UI.
            null
        } catch (e: NumberFormatException) {
            // A write() torn mid-line (a real crash/kill mid-write) leaves a line that is neither
            // a clean value nor a clean absence -- the same "no heartbeat" answer, not a crash.
            null
        }
    }

    override fun markCleanShutdown(sessionId: String) {
        val current = last() ?: return
        if (current.sessionId != sessionId) return
        file.writeText(
            listOf(current.sessionId, current.monotonicNanos, current.wallMillis, current.samplePosition, "true")
                .joinToString("\n"),
        )
    }

    override fun hadUncleanEnd(): Boolean {
        if (!file.exists()) return false
        return try {
            val lines = file.readLines()
            if (lines.size < 5) return false
            lines[4] != "true"
        } catch (e: java.io.FileNotFoundException) {
            // AC-5/FR-PLT-1: see last()'s own kdoc for the same TOCTOU race -- a vanished
            // heartbeat is never reported as an unclean end.
            false
        }
    }

    override fun clear() {
        file.delete()
    }
}
