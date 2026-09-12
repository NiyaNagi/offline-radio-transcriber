package org.ort.core.fieldreport

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AC-147: "the field-report token never appears in a bundle or a frame." `:core`'s own field-report
 * types never carry the FR-OBS-12 token at all — that value lives entirely in `:net`'s
 * `RealFieldReportUploadClient` constructor, injected by `:app` from `BuildConfig.FIELD_REPORT_TOKEN`
 * — so the structural guarantee this test proves is narrower but load-bearing: none of `:core`'s
 * own public field-report types (what a destination names, what an upload request carries, what a
 * failure reports) declare a property shaped like a token, secret or credential in the first place.
 * A type that had no such field could not leak one even if a future change accidentally wired a
 * token value into it — the same reflection-based discipline this codebase already uses elsewhere
 * for a closed vocabulary (`RecorderEventVocabularyTest`, `AC_141_...`), applied here to this
 * round's own upload-facing types.
 */
class FieldReportNoTokenFieldTest {

    // Plain `java.lang.reflect`, not `kotlin-reflect`'s `KClass` API — `:core` has no
    // `kotlin-reflect` dependency to add (`core/build.gradle.kts`: "Only the Kotlin stdlib and
    // JUnit (test) belong here"). For a Kotlin data class, `Class.getDeclaredFields` sees exactly
    // the same backing-field shape `kotlin-reflect` would report.
    private val suspiciousNames = listOf("token", "secret", "credential", "password", "apikey")

    private fun assertNoSuspiciousField(javaClass: Class<*>) {
        val names = javaClass.declaredFields.map { it.name }
        val offending = names.filter { name -> suspiciousNames.any { name.lowercase().contains(it) } }
        assertTrue(
            offending.isEmpty(),
            "expected no token/secret-shaped field on ${javaClass.simpleName}, found: $offending",
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
    fun `AC_147 FieldReportUploadResult Failure carries no token-shaped field`() {
        assertNoSuspiciousField(FieldReportUploadResult.Failure::class.java)
    }

    @Test
    fun `AC_147 FieldReportUploadResult Success carries no token-shaped field`() {
        assertNoSuspiciousField(FieldReportUploadResult.Success::class.java)
    }
}
