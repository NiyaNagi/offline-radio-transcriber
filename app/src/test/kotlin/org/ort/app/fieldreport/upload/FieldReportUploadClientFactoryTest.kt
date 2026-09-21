package org.ort.app.fieldreport.upload

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D49/D55: "the channel ships in every build now"; "a build with no configured destination must
 * refuse to upload rather than fall back to a default". [FieldReportUploadClientFactory.create]
 * itself touches the real, compile-time-fixed `BuildConfig` (a per-variant constant a unit test
 * cannot override), so this tests the pure decision [FieldReportUploadClientFactory.shouldCreate]
 * makes from it directly — every input `create()` could ever see in any build.
 */
class FieldReportUploadClientFactoryTest {

    @Test
    fun `D55 a build with both a token and a destination repository configured creates a real client`() {
        assertTrue(FieldReportUploadClientFactory.shouldCreate(token = "a-real-token", repository = "owner/repo"))
    }

    @Test
    fun `D49 a build with no destination repository configured refuses, rather than falling back to a default`() {
        assertFalse(FieldReportUploadClientFactory.shouldCreate(token = "a-real-token", repository = null))
    }

    @Test
    fun `D49 a blank destination repository is treated identically to no destination repository`() {
        assertFalse(FieldReportUploadClientFactory.shouldCreate(token = "a-real-token", repository = "   "))
    }

    @Test
    fun `FR_OBS_12 a build with no token configured is honestly not-configured`() {
        assertFalse(FieldReportUploadClientFactory.shouldCreate(token = null, repository = "owner/repo"))
    }

    @Test
    fun `FR_OBS_12 a blank token is treated identically to no token`() {
        assertFalse(FieldReportUploadClientFactory.shouldCreate(token = "   ", repository = "owner/repo"))
    }

    @Test
    fun `FR_OBS_12 an empty token is treated identically to no token`() {
        assertFalse(FieldReportUploadClientFactory.shouldCreate(token = "", repository = "owner/repo"))
    }

    @Test
    fun `neither configured is honestly not-configured`() {
        assertFalse(FieldReportUploadClientFactory.shouldCreate(token = null, repository = null))
    }
}
