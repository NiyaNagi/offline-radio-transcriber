package org.ort.app.diagnostics.localsave

import org.ort.app.fieldreport.bundle.FieldReportGatedCategory
import org.ort.app.fieldreport.bundle.FieldReportUngatedFileId

/**
 * WPDUMP. The operator, verbatim: *"i also cant send the voiceprint embeddings screen frames or
 * retained over audio in this build. all the field report data to be included in the debug dump.
 * just allow a single save dump with checkboxes for EVERYTHING that can be saved."*
 *
 * The closed set of every category a local save can carry — everything that exists anywhere in
 * this build, no more, no fewer, the same "these and no more" compile-time discipline
 * [org.ort.app.diagnostics.DiagnosticsBundleSpec] (that object's own doc comment, lines 10-13) and
 * [org.ort.app.fieldreport.bundle.FieldReportBundleSpec] already hold — a plain `enum class` plus a
 * `List` built once from it, never a `Set<String>` or other runtime-open shape a future change
 * could append to unnoticed.
 *
 * Eleven of the twelve entries are not a second, independently-authored idea of the same category:
 * [LocalSaveBundleSpec.ungatedEntries] and [LocalSaveBundleSpec.gatedEntries] resolve each one onto
 * the exact [FieldReportUngatedFileId]/[FieldReportGatedCategory] entry the field-report bundle
 * already defines — same `fileName`/`clause`/`producer` — so what a category *is* (its files, its
 * producer, its prose) can never drift between the local save and the upload path. That is "the
 * same category selection" at the level that actually matters: the two screens still keep
 * independent per-screen selection *state* (see [LocalSaveBundleBuilder]'s own doc comment for
 * exactly why that cannot also be unified without breaking FR-OBS-9).
 *
 * The twelfth, [DEBUG_DUMP_NDJSON], is genuinely local-only: the field-report channel has no use for
 * it at all (FR-OBS-8's ungated set is fixed by spec; FR-OBS-9 names exactly three gated
 * categories, never a fourth) — it exists only here, because WPX's own `DebugDumpBuilder` was never
 * reachable from any save action until this round wired it in.
 */
public enum class LocalSaveCategoryId {
    LIFECYCLE_LOG,
    CAPTURE_LOG,
    PIPELINE_LOG,
    RIG_LOG,
    ASSETS_JSON,
    DEVICE_JSON,
    COUNTS_JSON,
    SESSION_RECORDER_LOG,
    RETAINED_AUDIO,
    VOICEPRINT_EMBEDDINGS,
    SCREEN_FRAMES,
    DEBUG_DUMP_NDJSON,
}

/** [LocalSaveBundleBuilder]'s own resolved view of one category — real caption, real availability,
 * shared by [org.ort.app.diagnostics.localsave.LocalSavePreview] and the write path so a checkbox's
 * caption/availability can never drift from what a save actually does with it. */
public data class LocalSaveCategoryPlan(val id: LocalSaveCategoryId, val label: String, val caption: String)

public object LocalSaveBundleSpec {

    /** Maps each of this bundle's eight "ungated-shaped" ids onto the field-report bundle's own
     * entry for the identical logical file — see this file's own top doc comment for why this is a
     * mapping over reused entries, not a second, independently-authored list. `getValue` fails
     * loudly, at class-load, the moment [org.ort.app.fieldreport.bundle.FieldReportBundleSpec.ungatedFiles]
     * ever carries an id this map does not know about — the identical fail-closed guard
     * [org.ort.app.fieldreport.bundle.FieldReportBundleSpec] itself already uses one layer up. */
    private val UNGATED_TO_FIELD_REPORT_ID: Map<LocalSaveCategoryId, FieldReportUngatedFileId> = mapOf(
        LocalSaveCategoryId.LIFECYCLE_LOG to FieldReportUngatedFileId.LIFECYCLE_LOG,
        LocalSaveCategoryId.CAPTURE_LOG to FieldReportUngatedFileId.CAPTURE_LOG,
        LocalSaveCategoryId.PIPELINE_LOG to FieldReportUngatedFileId.PIPELINE_LOG,
        LocalSaveCategoryId.RIG_LOG to FieldReportUngatedFileId.RIG_LOG,
        LocalSaveCategoryId.ASSETS_JSON to FieldReportUngatedFileId.ASSETS_JSON,
        LocalSaveCategoryId.DEVICE_JSON to FieldReportUngatedFileId.DEVICE_JSON,
        LocalSaveCategoryId.COUNTS_JSON to FieldReportUngatedFileId.COUNTS_JSON,
        LocalSaveCategoryId.SESSION_RECORDER_LOG to FieldReportUngatedFileId.SESSION_RECORDER_LOG,
    )

    private val GATED_TO_FIELD_REPORT_CATEGORY: Map<LocalSaveCategoryId, FieldReportGatedCategory> = mapOf(
        LocalSaveCategoryId.RETAINED_AUDIO to FieldReportGatedCategory.RETAINED_AUDIO,
        LocalSaveCategoryId.VOICEPRINT_EMBEDDINGS to FieldReportGatedCategory.VOICEPRINT_EMBEDDINGS,
        LocalSaveCategoryId.SCREEN_FRAMES to FieldReportGatedCategory.SCREEN_FRAMES,
    )

    /** [LocalSaveCategoryId] resolved onto its exact [FieldReportUngatedFileId] twin — `null` for
     * [LocalSaveCategoryId.DEBUG_DUMP_NDJSON], the one entry with no field-report equivalent. */
    internal fun ungatedIdFor(id: LocalSaveCategoryId): FieldReportUngatedFileId? = UNGATED_TO_FIELD_REPORT_ID[id]

    /** [LocalSaveCategoryId] resolved onto its exact [FieldReportGatedCategory] twin — `null` for
     * every id that is not one of the three gated categories. */
    internal fun gatedCategoryFor(id: LocalSaveCategoryId): FieldReportGatedCategory? =
        GATED_TO_FIELD_REPORT_CATEGORY[id]

    /** Board order: the seven scrubbed files, the recorder log, then the three third-party-content
     * categories, then the debug dump — matching this round's own brief order verbatim. */
    public val order: List<LocalSaveCategoryId> = LocalSaveCategoryId.entries.toList()

    /**
     * D38's own shape, applied to a local save (this round's Constitution Check — see the builder's
     * report for the full one): the seven scrubbed files, the session-recorder log and the debug
     * dump default **on** — every one of them is safe by construction, structurally free of a
     * callsign, a station's user-supplied name or a voiceprint embedding (each producer's own doc
     * comment; [org.ort.app.export.DebugDumpBuilder]'s own doc comment states it never queries
     * `CatalogDao` at all). Retained audio, voiceprint embeddings and screen frames default **off**
     * — they carry third-party content (identifiable people's voices, or a screen that can show a
     * callsign a log line would have scrubbed) and the operator ticks them deliberately.
     */
    public val defaultSelected: Set<LocalSaveCategoryId> = setOf(
        LocalSaveCategoryId.LIFECYCLE_LOG,
        LocalSaveCategoryId.CAPTURE_LOG,
        LocalSaveCategoryId.PIPELINE_LOG,
        LocalSaveCategoryId.RIG_LOG,
        LocalSaveCategoryId.ASSETS_JSON,
        LocalSaveCategoryId.DEVICE_JSON,
        LocalSaveCategoryId.COUNTS_JSON,
        LocalSaveCategoryId.SESSION_RECORDER_LOG,
        LocalSaveCategoryId.DEBUG_DUMP_NDJSON,
    )

    /** The three categories that carry third-party content (constitution V, D38) — used only to
     * decide default-off above and to word each row's caption; never a second gate (the guard that
     * matters for an outbound upload, [org.ort.app.fieldreport.consent.FieldReportGuard], has no
     * local-save equivalent at all — see [LocalSaveBundleBuilder]'s own doc comment for why a local
     * save is never gated the way an upload is, FR-OBS-10). */
    public val thirdPartyContent: Set<LocalSaveCategoryId> = setOf(
        LocalSaveCategoryId.RETAINED_AUDIO,
        LocalSaveCategoryId.VOICEPRINT_EMBEDDINGS,
        LocalSaveCategoryId.SCREEN_FRAMES,
    )
}
