package org.ort.app.fieldreport.upload

import org.junit.Assert.assertTrue
import org.junit.Test
import org.ort.app.fieldreport.bundle.FieldReportBundleEntry
import org.ort.app.fieldreport.bundle.FieldReportUngatedFileSpec

/**
 * AC-147: "the field-report token never appears in a bundle or a frame." WPR3 moved the
 * upload-facing types (`FieldReportUploadRequest`, `FieldReportDestination`,
 * `FieldReportUploadResult`) to `:core` (`org.ort.core.fieldreport`) — their own no-token-field
 * reflection tests now live at `core/src/test/kotlin/org/ort/core/fieldreport/FieldReportNoTokenFieldTest.kt`.
 * This file keeps only the checks for the two types this round still owns (`fieldreport.bundle`,
 * off-limits to WPR3 — see that package's own files): the bundle-building types the token can never
 * legitimately reach in the first place.
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
    fun `AC_147 FieldReportBundleEntry carries no token-shaped field`() {
        assertNoSuspiciousField(FieldReportBundleEntry::class.java)
    }

    @Test
    fun `AC_147 FieldReportUngatedFileSpec carries no token-shaped field`() {
        assertNoSuspiciousField(FieldReportUngatedFileSpec::class.java)
    }
}
