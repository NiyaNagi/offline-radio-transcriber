package org.ort.lexicon.import

import org.ort.lexicon.ItuPrefixTable
import java.io.File

/**
 * A port onto wherever "the active lexicon" actually lives — `:lexicon` is a pure JVM module with no
 * Android or database dependency (constitution VII), so persistence is injected rather than owned
 * here. `:app` implements this against `:data`'s `LexiconVersionEntity`/`CatalogDao` (technical
 * design §12.1); a test implements it in memory.
 */
public interface ActiveLexiconStore {
    /** The lexicon currently in use by the running session, or `null` if none has ever been installed. */
    public fun current(): ActiveLexiconRecord?

    /**
     * Makes [record] the active lexicon. [LexiconImportInstaller.installValidated] calls this **only**
     * after every one of [LexiconImportValidator.validate]'s checks has passed — never on a partial
     * or failed validation (FR-LEX-30, FR-AST-2: "a failure leaves the previous version active").
     */
    public fun activate(record: ActiveLexiconRecord)
}

/**
 * FR-LEX-30 ("every asset import SHALL be transactional and validated ... before replacing the
 * previous version, with rollback on failure"), FR-AST-2 ("a failed verification SHALL leave the
 * previous version active"): the one entry point allowed to change which lexicon is active. It is a
 * thin, one-directional gate around [LexiconImportValidator.validate] — [store] is read for context
 * (what stays active, for [LexiconImportResult.Rejected] to report) and written to exactly once, only
 * on [LexiconImportResult.Accepted].
 */
public object LexiconImportInstaller {

    public fun installValidated(
        file: File,
        assetId: String,
        store: ActiveLexiconStore,
        itu: ItuPrefixTable = ItuPrefixTable.bundled(),
    ): LexiconImportResult {
        val currentActive = store.current()
        val result = LexiconImportValidator.validate(file, assetId, currentActive, itu)
        if (result is LexiconImportResult.Accepted) {
            store.activate(
                ActiveLexiconRecord(
                    assetId = result.assetId,
                    version = result.version,
                    recordCount = result.recordCount,
                    checksum = result.checksum,
                ),
            )
        }
        return result
    }
}
