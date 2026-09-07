package org.ort.data

import org.ort.data.dao.TransmissionDao
import java.io.File

/**
 * Orphaned files (on disk, no row) and dangling rows (a row, no file) found in one pass, in
 * both directions (FR-AST-8 → AC-54, F19). **Deletes neither** — reconciliation reports, a
 * separate, deliberate operation deletes (constitution: never delete quietly).
 */
public data class ReconciliationReport(public val orphanedFiles: List<String>, public val danglingRows: List<String>)

/**
 * Walks [audioRoot] (technical design §12.2's `audio/<sessionId>/<transmissionId>.flac` layout,
 * derived, never stored — [org.ort.data.entity.TransmissionEntity.audioPath]) against every
 * transmission row and reports both directions of mismatch.
 */
public suspend fun reconcile(audioRoot: File, transmissionDao: TransmissionDao): ReconciliationReport {
    val filesOnDisk: Set<String> = audioRoot.walkTopDown()
        .filter { it.isFile }
        .map { it.relativeTo(audioRoot).invariantSeparatorsPath }
        .toSet()

    val expectedPaths: Map<String, String> = transmissionDao.listAll()
        .associate { row -> row.id to "${row.sessionId}/${row.id}.flac" }

    val orphaned = filesOnDisk.filterNot { it in expectedPaths.values }
    val dangling = expectedPaths.filterValues { it !in filesOnDisk }.keys

    return ReconciliationReport(orphanedFiles = orphaned.sorted(), danglingRows = dangling.sorted())
}
