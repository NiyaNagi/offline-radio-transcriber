package org.ort.pipeline.digest

/**
 * The minimal read surface `:app` uses for DG05 (FR-DIG-6, FR-DIG-11) — a thin, read-only
 * projection over [ProseSummaryStore] so `:app` depends on a narrow shape rather than the store's
 * write methods. Session grouping is deliberately not this API's job: a screen rendering a
 * session already knows which thread ids belong to it from the existing thread/session joins in
 * `:data`, so this only ever resolves thread id -> summary.
 */
public interface ProseDigestReadApi {
    public suspend fun summaryForThread(threadId: String): ProseSummary?
    public suspend fun summariesForThreads(threadIds: Collection<String>): List<ProseSummary>
}

/** The only implementation today — a direct delegate to [store]. */
public class ProseSummaryStoreReadApi(private val store: ProseSummaryStore) : ProseDigestReadApi {
    override suspend fun summaryForThread(threadId: String): ProseSummary? = store.forThread(threadId)

    override suspend fun summariesForThreads(threadIds: Collection<String>): List<ProseSummary> =
        store.forThreads(threadIds)
}
