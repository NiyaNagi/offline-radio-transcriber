package org.ort.pipeline.export

/**
 * Register R-1009 (WPX), FR-EXP-1..5. One over (transmission), reduced to exactly the fields the
 * four export formats need — a format-agnostic row built by `:app`'s `ExportCoordinator` from the
 * real `:data` entities (`TransmissionEntity`, `TranscriptEntity`, `StationEntity`) and never a
 * direct pass-through of a Room entity, so a writer in this package never needs `:data` on its
 * classpath and a new persisted column cannot silently start flowing into an exported file.
 *
 * [attribution] is the one required field with no default — every writer must be handed one, and
 * every writer must branch on it via [ExportAttribution]'s own exhaustive `when` (constitution I:
 * "an attribution without its confidence state is a bug, at the data layer, not just the UI" — the
 * export layer is the data layer's boundary with the outside world, so the same rule applies here
 * with the same force).
 */
public data class ExportOverRecord(
    public val transmissionId: String,
    public val sessionId: String,
    public val startedAtUtcMillis: Long,
    public val endedAtUtcMillis: Long?,
    public val frequencyHz: Long?,
    public val mode: String?,
    public val channelName: String?,
    public val attribution: ExportAttribution,
    public val transcriptText: String?,
    public val transcriptModelId: String?,
    public val transcriptModelVersion: String?,
    /** [org.ort.data.entity.StationEntity.potaRefs] for the attributed station — empty when the
     * attribution names no station, or the station carries none. FR-EXP-3's own filter reads this
     * to decide whether a row is "POTA-relevant activity" at all. */
    public val potaRefs: List<String> = emptyList(),
)
