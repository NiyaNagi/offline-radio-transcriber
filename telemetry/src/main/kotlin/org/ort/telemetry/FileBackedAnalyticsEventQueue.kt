package org.ort.telemetry

import java.io.File

/**
 * The real on-device [AnalyticsEventQueue] (technical design §2's `:telemetry [AND]` — this is
 * why the module needs Android's filesystem at all rather than being pure JVM logic). Backed by a
 * single NDJSON file (`analytics-queue.ndjson`) under [directory], one line per event — the same
 * wire shape [AnalyticsEventCodec] round-trips for the upload request and `tools/analytics`'
 * reference ingest server, so nothing here is a bespoke on-disk format. Bounding and drop-oldest
 * are delegated to an in-memory [InMemoryAnalyticsEventQueue] rebuilt from the file at
 * construction time; every mutating call rewrites the file afterwards. A malformed or partial
 * line (a process killed mid-`append`) is skipped rather than failing the whole queue to load —
 * losing one straggler event is acceptable, refusing to start the queue at all is not
 * (constitution IV's "never blocks" extended to this channel).
 */
public class FileBackedAnalyticsEventQueue(directory: File, private val maxCount: Int, private val maxBytes: Long) :
    AnalyticsEventQueue {

    private val file: File = File(directory, "analytics-queue.ndjson")
    private val delegate: InMemoryAnalyticsEventQueue

    init {
        directory.mkdirs()
        delegate = InMemoryAnalyticsEventQueue(maxCount, maxBytes)
        if (file.exists()) {
            file.readLines().forEach { line ->
                if (line.isNotBlank()) {
                    runCatching { AnalyticsEventCodec.decode(line) }.onSuccess { delegate.enqueue(it) }
                }
            }
        }
    }

    @Synchronized
    private fun persist() {
        file.writeText(delegate.peekAll().joinToString(separator = "\n") { AnalyticsEventCodec.encode(it) })
    }

    @Synchronized
    override fun enqueue(event: AnalyticsEvent): EnqueueOutcome {
        val outcome = delegate.enqueue(event)
        persist()
        return outcome
    }

    @Synchronized
    override fun drain(max: Int): List<AnalyticsEvent> {
        val out = delegate.drain(max)
        persist()
        return out
    }

    @Synchronized
    override fun peekAll(): List<AnalyticsEvent> = delegate.peekAll()

    @Synchronized
    override fun removeAll(predicate: (AnalyticsEvent) -> Boolean) {
        delegate.removeAll(predicate)
        persist()
    }

    @Synchronized
    override fun size(): Int = delegate.size()

    @Synchronized
    override fun byteSize(): Long = delegate.byteSize()

    @Synchronized
    override fun droppedCount(): Long = delegate.droppedCount()
}
