package org.ort.telemetry

/** What [AnalyticsEventQueue.enqueue] did — never an exception, never a block (FR-ANL-13,
 * constitution IV's "capture never blocks" extended to this channel). */
public enum class EnqueueOutcome { QUEUED, DROPPED_OLDEST }

/**
 * FR-ANL-13: the on-device analytics queue is bounded in count and bytes. When full, the oldest
 * queued event is dropped — never blocking, slowing or otherwise affecting whatever called
 * [enqueue] (FR-RUN-1's guarantee extended to this channel). Every implementation of this
 * interface MUST be O(1)-ish and perform no synchronous network or blocking disk I/O from
 * [enqueue] itself.
 */
public interface AnalyticsEventQueue {
    public fun enqueue(event: AnalyticsEvent): EnqueueOutcome

    /** Removes and returns up to [max] events, oldest first. */
    public fun drain(max: Int = Int.MAX_VALUE): List<AnalyticsEvent>

    /** Non-destructive — for inspection and tests. */
    public fun peekAll(): List<AnalyticsEvent>

    /** FR-ANL-9: purges every currently-queued event [predicate] matches, immediately — how
     * turning a tier off takes effect for events not yet sent. */
    public fun removeAll(predicate: (AnalyticsEvent) -> Boolean)

    public fun size(): Int
    public fun byteSize(): Long

    /** How many events have ever been dropped for the queue being full — FR-ANL-13's "record that
     * it happened" (AGENTS.md: "when it is full, drop oldest and record that it happened"). */
    public fun droppedCount(): Long
}

/**
 * The bounded, in-memory [AnalyticsEventQueue] (constitution II's behavioural fake doubles as the
 * production implementation's core data structure here — [FileBackedAnalyticsEventQueue] wraps
 * this one for on-device durability). Backed by an [ArrayDeque] so `enqueue`/drop-oldest are O(1)
 * amortised; [byteSize] is tracked incrementally rather than recomputed on every call.
 */
public class InMemoryAnalyticsEventQueue(private val maxCount: Int, private val maxBytes: Long) : AnalyticsEventQueue {

    private val events = ArrayDeque<AnalyticsEvent>()
    private var currentBytes = 0L
    private var dropped = 0L

    @Synchronized
    override fun enqueue(event: AnalyticsEvent): EnqueueOutcome {
        events.addLast(event)
        currentBytes += AnalyticsEventCodec.byteSize(event)
        var droppedAny = false
        while (events.size > maxCount || currentBytes > maxBytes) {
            val removed = events.removeFirstOrNull() ?: break
            currentBytes -= AnalyticsEventCodec.byteSize(removed)
            dropped++
            droppedAny = true
        }
        return if (droppedAny) EnqueueOutcome.DROPPED_OLDEST else EnqueueOutcome.QUEUED
    }

    @Synchronized
    override fun drain(max: Int): List<AnalyticsEvent> {
        val out = mutableListOf<AnalyticsEvent>()
        while (out.size < max) {
            val next = events.removeFirstOrNull() ?: break
            currentBytes -= AnalyticsEventCodec.byteSize(next)
            out += next
        }
        return out
    }

    @Synchronized
    override fun peekAll(): List<AnalyticsEvent> = events.toList()

    @Synchronized
    override fun removeAll(predicate: (AnalyticsEvent) -> Boolean) {
        val iterator = events.iterator()
        while (iterator.hasNext()) {
            val event = iterator.next()
            if (predicate(event)) {
                currentBytes -= AnalyticsEventCodec.byteSize(event)
                iterator.remove()
            }
        }
    }

    @Synchronized
    override fun size(): Int = events.size

    @Synchronized
    override fun byteSize(): Long = currentBytes

    @Synchronized
    override fun droppedCount(): Long = dropped
}
