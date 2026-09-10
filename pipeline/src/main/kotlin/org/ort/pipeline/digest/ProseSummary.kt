package org.ort.pipeline.digest

/**
 * One LLM-generated topic summary for a thread (FR-DIG-3, FR-DIG-11 / T3). Always attributed to
 * the transmissions that produced it and the model that produced it — an attribution without its
 * source is exactly the kind of silent guess constitution I forbids, applied here to generated
 * prose rather than a callsign.
 *
 * Persistence today is [ProseSummaryStore]'s in-memory implementation; the real `prose_summary`
 * table lands as `:data` migration 7→8 once WPC1's v7 schema has merged (E2-A03) — this type is
 * the stable shape that migration will persist unchanged.
 */
public data class ProseSummary(
    public val threadId: String,
    public val text: String,
    public val sourceTransmissionIds: List<String>,
    public val generatedAtMillis: Long,
    public val modelId: String,
)
