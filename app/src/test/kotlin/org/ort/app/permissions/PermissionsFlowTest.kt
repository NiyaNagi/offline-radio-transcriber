package org.ort.app.permissions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement

class PermissionsFlowTest {

    @Test
    @Requirement("NFR-8")
    fun `record audio is requested first`() {
        val state = PermissionsState(false, false, false)
        assertEquals(PermissionStep.RECORD_AUDIO, PermissionsFlow.nextStep(state))
    }

    @Test
    fun `notifications are requested once audio is granted`() {
        val state = PermissionsState(
            recordAudioGranted = true,
            notificationsGranted = false,
            isIgnoringBatteryOptimizationsDiagnosticOnly = false,
        )
        assertEquals(PermissionStep.NOTIFICATIONS, PermissionsFlow.nextStep(state))
    }

    @Test
    @Requirement("AC-65", "NFR-8")
    fun `AC_65 battery exemption is requested last and never gates capture readiness`() {
        val state = PermissionsState(
            recordAudioGranted = true,
            notificationsGranted = true,
            isIgnoringBatteryOptimizationsDiagnosticOnly = false,
        )
        assertEquals(PermissionStep.BATTERY_EXEMPTION, PermissionsFlow.nextStep(state))
        // The diagnostic-only flag being false must not block capture readiness (AC-65's principle
        // applied here: the battery API is never treated as a gate on what the app actually does).
        assertTrue(PermissionsFlow.captureIsPermitted(state))
    }

    @Test
    fun `capture is not permitted until audio and notifications are both granted`() {
        assertFalse(PermissionsFlow.captureIsPermitted(PermissionsState(false, true, true)))
        assertFalse(PermissionsFlow.captureIsPermitted(PermissionsState(true, false, true)))
        assertTrue(PermissionsFlow.captureIsPermitted(PermissionsState(true, true, false)))
    }

    @Test
    fun `every step granted means DONE`() {
        assertEquals(PermissionStep.DONE, PermissionsFlow.nextStep(PermissionsState(true, true, true)))
    }
}
