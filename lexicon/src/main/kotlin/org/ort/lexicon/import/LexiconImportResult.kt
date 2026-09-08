package org.ort.lexicon.import

/**
 * The outcome of one named check [LexiconImportValidator.validate] ran (R-154, `Fail-Lexicon.dc.html`
 * "What was checked"). [CheckStatus.NOT_REACHED] is a distinct third state from [CheckStatus.FAILED]
 * — constitution I's closed-set discipline applied to import validation: a check that never ran
 * because an earlier one already made its input untrustworthy is not the same fact as a check that
 * ran and failed, and a renderer must be able to tell them apart (the board draws an empty ring for
 * "not reached", a red cross for "failed").
 */
public enum class CheckStatus { PASSED, FAILED, NOT_REACHED }

/**
 * One row of "What was checked" — a name and its outcome, with a human [detail] that is never blank
 * regardless of [status] (FR-LEX-12/R-154: "every check reports pass or fail with a reason").
 */
public data class LexiconCheck(val name: String, val status: CheckStatus, val detail: String) {
    init {
        require(detail.isNotBlank()) { "a check's detail must never be blank — '$name' would report nothing" }
    }
}

/** What is (or, on [LexiconImportResult.Rejected], remains) the running session's active lexicon. */
public data class ActiveLexiconRecord(
    val assetId: String,
    val version: String,
    val recordCount: Int,
    val checksum: String? = null,
)

/**
 * What [LexiconImportValidator.validate] (and [LexiconImportInstaller.installValidated]) returns
 * (FR-LEX-30, FR-AST-2, F12): a closed set of exactly two outcomes, both of which always carry the
 * full [checks] list — an [Accepted] result is not "no checks were needed", it is "every check ran
 * and passed".
 */
public sealed interface LexiconImportResult {
    /** The imported file's own name, for display — never a full device path (board: `lexicon-2026.09.tsv.zst`). */
    public val fileName: String
    public val checks: List<LexiconCheck>

    /** Every check passed; [LexiconImportInstaller.installValidated] activates this as the new lexicon. */
    public data class Accepted(
        override val fileName: String,
        override val checks: List<LexiconCheck>,
        val assetId: String,
        val version: String,
        val recordCount: Int,
        val checksum: String,
    ) : LexiconImportResult

    /**
     * At least one check failed. [stillActive] is the lexicon that remains active — `null` only when
     * there genuinely was none before this import attempt (a first-ever install), never omitted for
     * any other reason (constitution I: an absent fact is reported as absent, not left out).
     */
    public data class Rejected(
        override val fileName: String,
        override val checks: List<LexiconCheck>,
        val reason: String,
        val stillActive: ActiveLexiconRecord?,
    ) : LexiconImportResult
}
