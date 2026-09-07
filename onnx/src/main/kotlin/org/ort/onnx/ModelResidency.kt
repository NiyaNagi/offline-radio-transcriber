package org.ort.onnx

import org.ort.core.AssetRef
import org.ort.core.Outcome

/** The three residency classes of technical design §4.3. */
public enum class ResidencyClass {
    /** Loaded for the session's lifetime; never evicted (Silero VAD). */
    PINNED,

    /** LRU with a floor of one; loaded on first use, evicted only under memory pressure or a tier change. */
    HOT,

    /** Loaded per batch of work and released immediately after (Pass E embedder, enhancer, LLM). */
    COLD,
}

/** Why a load or eviction decision came out the way it did — surfaced for F13 and for tests. */
public sealed interface ResidencyEvent {
    public data class Loaded(val assetRef: AssetRef, val residencyClass: ResidencyClass) : ResidencyEvent
    public data class Evicted(val assetRef: AssetRef, val residencyClass: ResidencyClass, val reason: String) :
        ResidencyEvent
    public data class BudgetExceeded(val requestedBytes: Long, val budgetBytes: Long) : ResidencyEvent
    public data class LoadFailed(val assetRef: AssetRef, val reason: String) : ResidencyEvent
}

/**
 * Owns which models are resident and enforces the tier memory budget (technical design §4.3).
 * `PINNED` models are loaded once and never evicted; `HOT` models are LRU-tracked with a floor
 * of one resident model; `COLD` models are loaded for [withCold] and released immediately after,
 * regardless of memory pressure. [onTrimMemoryCritical] evicts every `COLD` session immediately,
 * mirroring `onTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL)`.
 *
 * A single JVM-level lock serialises residency changes: the design's "single `infer-heavy` slot"
 * (§4.3) means peak activation memory is one `HOT` model's, not the sum, and that guarantee is
 * only real if two callers cannot both believe they hold the slot.
 */
public class ModelResidencyManager(private val factory: OnnxSessionFactory, private var budgetBytes: Long) {
    private val lock = Any()
    private val pinned = LinkedHashMap<AssetRef, OnnxSession>()
    private val hot = LinkedHashMap<AssetRef, OnnxSession>() // insertion order; eviction picks LRU by re-insertion
    private val events = mutableListOf<ResidencyEvent>()

    public val history: List<ResidencyEvent> get() = events.toList()

    /** Sum of every currently resident model's declared footprint (§4.3). */
    public fun residentBytes(): Long = synchronized(lock) {
        (pinned.values + hot.values).sumOf { it.descriptor.memoryFootprintBytes }
    }

    public fun setBudget(bytes: Long) {
        synchronized(lock) { budgetBytes = bytes }
    }

    /** Load-once-for-session-lifetime; never evicted by [evictUnderPressure]. */
    public fun loadPinned(descriptor: ModelDescriptor): Outcome<OnnxSession> = synchronized(lock) {
        require(descriptor.residencyClass == ResidencyClass.PINNED) {
            "loadPinned called with a non-PINNED descriptor: ${descriptor.residencyClass}"
        }
        pinned[descriptor.assetRef]?.let { return@synchronized Outcome.Ok(it) }
        if (!fitsBudget(descriptor)) {
            events += ResidencyEvent.BudgetExceeded(descriptor.memoryFootprintBytes, budgetBytes)
            return@synchronized Outcome.Err(
                "loading ${descriptor.assetRef} as PINNED would exceed the tier memory budget",
            )
        }
        loadInto(pinned, descriptor)
    }

    /**
     * Loaded on first use, kept resident until eviction. A floor of one `HOT` model is always
     * kept — [evictUnderPressure] never empties [hot] entirely while any is loaded.
     */
    public fun acquireHot(descriptor: ModelDescriptor): Outcome<OnnxSession> = synchronized(lock) {
        require(descriptor.residencyClass == ResidencyClass.HOT) {
            "acquireHot called with a non-HOT descriptor: ${descriptor.residencyClass}"
        }
        hot[descriptor.assetRef]?.let {
            // touch: move to most-recently-used position
            hot.remove(descriptor.assetRef)
            hot[descriptor.assetRef] = it
            return@synchronized Outcome.Ok(it)
        }
        if (!fitsBudget(descriptor)) {
            evictOneHot(reason = "budget pressure admitting ${descriptor.assetRef}")
            if (!fitsBudget(descriptor)) {
                events += ResidencyEvent.BudgetExceeded(descriptor.memoryFootprintBytes, budgetBytes)
                return@synchronized Outcome.Err(
                    "loading ${descriptor.assetRef} as HOT would exceed the tier memory budget",
                )
            }
        }
        loadInto(hot, descriptor)
    }

    /** Runs [block] with a freshly loaded `COLD` session, then releases it regardless of outcome. */
    public fun <T> withCold(descriptor: ModelDescriptor, block: (OnnxSession) -> T): Outcome<T> {
        require(descriptor.residencyClass == ResidencyClass.COLD) {
            "withCold called with a non-COLD descriptor: ${descriptor.residencyClass}"
        }
        val loaded = factory.load(descriptor)
        val session = loaded.getOrNull() ?: return Outcome.Err("failed to load COLD model ${descriptor.assetRef}")
        return try {
            Outcome.Ok(block(session))
        } catch (t: Throwable) {
            Outcome.Err("COLD model ${descriptor.assetRef} failed during use", t)
        } finally {
            session.close()
        }
    }

    /** Evicts `HOT` models (never `PINNED`) under memory pressure, oldest-used first, floor of one. */
    public fun evictUnderPressure(targetFreeBytes: Long) {
        synchronized(lock) {
            var freed = 0L
            while (freed < targetFreeBytes && hot.size > 1) {
                freed += evictOneHot(reason = "memory pressure")
            }
        }
    }

    /**
     * `onTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL)` — every `COLD` session is released
     * immediately. `COLD` sessions never linger past [withCold], so this is a no-op by
     * construction; it exists so callers have a named hook that matches the design vocabulary.
     */
    public fun onTrimMemoryCritical() {
        // Documented, not dead code: a future resident COLD cache (if ever added) must wire
        // eviction through here.
    }

    private fun fitsBudget(descriptor: ModelDescriptor): Boolean =
        residentBytesLocked() + descriptor.memoryFootprintBytes <= budgetBytes

    private fun residentBytesLocked(): Long = (pinned.values + hot.values).sumOf { it.descriptor.memoryFootprintBytes }

    private fun loadInto(
        store: LinkedHashMap<AssetRef, OnnxSession>,
        descriptor: ModelDescriptor,
    ): Outcome<OnnxSession> {
        val result = factory.load(descriptor)
        return when (result) {
            is Outcome.Ok -> {
                store[descriptor.assetRef] = result.value
                events += ResidencyEvent.Loaded(descriptor.assetRef, descriptor.residencyClass)
                result
            }
            is Outcome.Err -> {
                events += ResidencyEvent.LoadFailed(descriptor.assetRef, result.reason)
                result
            }
        }
    }

    private fun evictOneHot(reason: String): Long {
        val oldest = hot.entries.firstOrNull() ?: return 0L
        hot.remove(oldest.key)
        val bytes = oldest.value.descriptor.memoryFootprintBytes
        oldest.value.close()
        events += ResidencyEvent.Evicted(oldest.key, ResidencyClass.HOT, reason)
        return bytes
    }
}
