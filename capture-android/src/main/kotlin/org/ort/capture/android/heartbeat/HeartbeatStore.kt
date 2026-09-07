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
        val lines = file.readLines()
        if (lines.size < 4) return null
        return HeartbeatRecord(lines[0], lines[1].toLong(), lines[2].toLong(), lines[3].toLong())
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
        val lines = file.readLines()
        if (lines.size < 5) return false
        return lines[4] != "true"
    }

    override fun clear() {
        file.delete()
    }
}
