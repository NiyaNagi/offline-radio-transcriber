package org.ort.app.fieldreport.consent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC-145/FR-OBS-10: "against a destination reporting itself public, an upload with the over-audio,
 * voiceprint-embedding or screen-frame toggle on is refused unless the FR-OBS-10 Settings switch
 * has been explicitly turned off". [FieldReportGuard.gatedCategoriesAllowed] is that decision.
 */
class FieldReportGuardTest {

    @Test
    fun `AC_145 a public destination with the guard enabled refuses the gated categories`() {
        assertFalse(FieldReportGuard.gatedCategoriesAllowed(destinationIsPublic = true, publicGuardEnabled = true))
    }

    @Test
    fun `AC_145 a public destination with the guard explicitly turned off allows the gated categories`() {
        assertTrue(FieldReportGuard.gatedCategoriesAllowed(destinationIsPublic = true, publicGuardEnabled = false))
    }

    @Test
    fun `a private destination allows the gated categories regardless of the guard`() {
        assertTrue(FieldReportGuard.gatedCategoriesAllowed(destinationIsPublic = false, publicGuardEnabled = true))
        assertTrue(FieldReportGuard.gatedCategoriesAllowed(destinationIsPublic = false, publicGuardEnabled = false))
    }
}
