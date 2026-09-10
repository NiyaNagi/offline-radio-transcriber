package org.ort.pipeline.digest

import java.util.concurrent.ConcurrentHashMap

/**
 * Where generated [ProseSummary] rows live. One summary per thread — a fresh generation for a
 * thread replaces its previous summary rather than accumulating a history, matching FR-DIG-3's
 * "an addition to the deterministic digest", not a log of every draft ever produced.
 *
 * **Not `:data` in this round.** WPH owns `:data`'s `prose_summary` table only as migration 7→8,
 * and only after WPC1's v7 schema has merged (the lead's call, per the build package's file
 * ownership) — until then, [InMemoryProseSummaryStore] is the production stand-in wired wherever
 * a real store would go. Swapping it for a Room-backed implementation later needs no change to
 * [ProseDigestGenerator] or [ProseDigestReadApi], which both depend on this interface only.
 */
public interface ProseSummaryStore {
    public suspend fun store(summary: ProseSummary)
    public suspend fun forThread(threadId: String): ProseSummary?
    public suspend fun forThreads(threadIds: Collection<String>): List<ProseSummary>
    public suspend fun all(): List<ProseSummary>
}

/** The production stand-in until the `:data` v8 migration lands (see the interface's own doc comment). */
public class InMemoryProseSummaryStore : ProseSummaryStore {
    private val byThreadId = ConcurrentHashMap<String, ProseSummary>()

    override suspend fun store(summary: ProseSummary) {
        byThreadId[summary.threadId] = summary
    }

    override suspend fun forThread(threadId: String): ProseSummary? = byThreadId[threadId]

    override suspend fun forThreads(threadIds: Collection<String>): List<ProseSummary> =
        threadIds.mapNotNull { byThreadId[it] }

    override suspend fun all(): List<ProseSummary> = byThreadId.values.toList()
}

/**
 * The behavioural fake (constitution II) — can be told to fail a [store] call, so a caller that
 * would otherwise swallow a persistence failure shows up in a test rather than silently losing a
 * generated summary. [stored] records every summary that was actually persisted, in call order,
 * distinct from [ProseSummaryStore.all]'s de-duplicated-by-thread view.
 */
public class FakeProseSummaryStore(public var failStoreWith: Throwable? = null) : ProseSummaryStore {
    private val backing = InMemoryProseSummaryStore()

    public val stored: MutableList<ProseSummary> = mutableListOf()

    override suspend fun store(summary: ProseSummary) {
        failStoreWith?.let { throw it }
        stored += summary
        backing.store(summary)
    }

    override suspend fun forThread(threadId: String): ProseSummary? = backing.forThread(threadId)

    override suspend fun forThreads(threadIds: Collection<String>): List<ProseSummary> = backing.forThreads(threadIds)

    override suspend fun all(): List<ProseSummary> = backing.all()
}
