package org.ort.app.fieldreport.upload

import org.junit.Assert.assertTrue
import org.junit.Test
import org.ort.app.fieldreport.bundle.FieldReportBundleEntry
import org.ort.app.fieldreport.bundle.FieldReportUngatedFileSpec

/**
 * AC-147: "the field-report token never appears in a bundle or a frame." This package never
 * handles the FR-OBS-12 token at all — that value lives entirely in `:net` (WPR3's own module,
 * out of reach for this round) — so the structural guarantee this test proves is narrower but
 * load-bearing: none of this round's own public field-report types (what a bundle entry is, what
 * an upload request carries, what a destination names) declare a property shaped like a token,
 * secret or credential in the first place. A type that had no such field could not leak one even
 * if a future change accidentally wired a token value into it — the same reflection-based
 * discipline `RecorderEventVocabularyTest` (`AC_141_...`) already uses for its own closed
 * vocabulary, applied here to this round's own upload-facing types.
 */
class FieldReportNoTokenFieldTest {

    // Plain `java.lang.reflect`, not `kotlin-reflect`'s `KClass` API — this module has no
    // `kotlin-reflect` dependency to add, the same constraint `RecorderEventVocabularyTest`'s own
    // doc comment documents for the identical reason. For a Kotlin data class, `Class.getDeclaredFields`
    // sees exactly the same backing-field shape `kotlin-reflect` would report.
    private val suspiciousNames = listOf("token", "secret", "credential", "password", "apikey")

    private fun assertNoSuspiciousField(javaClass: Class<*>) {
        val names = javaClass.declaredFields.map { it.name }
        val offending = names.filter { name -> suspiciousNames.any { name.lowercase().contains(it) } }
        assertTrue(
            "expected no token/secret-shaped field on ${javaClass.simpleName}, found: $offending",
            offending.isEmpty(),
        )
    }

    @Test
    fun `AC_147 FieldReportUploadRequest carries no token-shaped field`() {
        assertNoSuspiciousField(FieldReportUploadRequest::class.java)
    }

    @Test
    fun `AC_147 FieldReportDestination carries no token-shaped field`() {
        assertNoSuspiciousField(FieldReportDestination::class.java)
    }

    @Test
    fun `AC_147 FieldReportBundleEntry carries no token-shaped field`() {
        assertNoSuspiciousField(FieldReportBundleEntry::class.java)
    }

    @Test
    fun `AC_147 FieldReportUngatedFileSpec carries no token-shaped field`() {
        assertNoSuspiciousField(FieldReportUngatedFileSpec::class.java)
    }
}
