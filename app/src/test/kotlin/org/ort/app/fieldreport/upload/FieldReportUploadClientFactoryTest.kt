package org.ort.app.fieldreport.upload

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-OBS-12: "present only in debug builds", "never a silent no-op". [FieldReportUploadClientFactory.create]
 * itself touches the real, compile-time-fixed `BuildConfig` (a per-variant constant a unit test
 * cannot override), so this tests the pure decision [FieldReportUploadClientFactory.shouldCreate]
 * makes from it directly — every input `create()` could ever see in either build type.
 */
class FieldReportUploadClientFactoryTest {

    @Test
    fun `FR_OBS_12 a debug build with a real token should create a real client`() {
        assertTrue(FieldReportUploadClientFactory.shouldCreate(debugBuild = true, token = "a-real-token"))
    }

    @Test
    fun `FR_OBS_12 a release build never creates a real client, even with a token present`() {
        assertFalse(FieldReportUploadClientFactory.shouldCreate(debugBuild = false, token = "a-real-token"))
    }

    @Test
    fun `FR_OBS_12 a debug build with no token configured is honestly not-configured`() {
        assertFalse(FieldReportUploadClientFactory.shouldCreate(debugBuild = true, token = null))
    }

    @Test
    fun `FR_OBS_12 a blank token is treated identically to no token`() {
        assertFalse(FieldReportUploadClientFactory.shouldCreate(debugBuild = true, token = "   "))
    }

    @Test
    fun `FR_OBS_12 an empty token is treated identically to no token`() {
        assertFalse(FieldReportUploadClientFactory.shouldCreate(debugBuild = true, token = ""))
    }
}
