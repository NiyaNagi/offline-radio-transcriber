package org.ort.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.ort.app.permissions.PermissionsState

/**
 * The pure decision half of [MainActivity.refreshScreen] (R-002, R-085, ui-conformance-plan WP1):
 * which of the three private setup screens to show given the real permission state, without any
 * `Context`/`Activity` — the same split [org.ort.app.permissions.PermissionsFlowTest] already
 * establishes for the pre-existing flow. [MainActivityTest] covers the Android-glue half (reading
 * real `Context` permission state, the Robolectric-driven "permitted → starts capture and finishes"
 * path).
 */
class SetupScreenSelectionTest {

    private fun state(recordAudioGranted: Boolean, notificationsGranted: Boolean) = PermissionsState(
        recordAudioGranted = recordAudioGranted,
        notificationsGranted = notificationsGranted,
        isIgnoringBatteryOptimizationsDiagnosticOnly = false,
    )

    @Test
    fun `R_002 microphone not granted and not permanently denied shows the request-microphone screen`() {
        val micNotGranted = state(recordAudioGranted = false, notificationsGranted = false)
        assertEquals(
            SetupScreen.REQUEST_MICROPHONE,
            setupScreenFor(micNotGranted, micPermanentlyDenied = false),
        )
    }

    @Test
    fun `R_085 microphone not granted and permanently denied shows the microphone-denied screen`() {
        val micNotGranted = state(recordAudioGranted = false, notificationsGranted = false)
        assertEquals(
            SetupScreen.MICROPHONE_DENIED,
            setupScreenFor(micNotGranted, micPermanentlyDenied = true),
        )
    }

    @Test
    fun `permanent denial is only consulted while the microphone is actually not granted`() {
        // A stale "permanently denied" reading must never override an actual grant.
        val micGrantedNotificationsNot = state(recordAudioGranted = true, notificationsGranted = false)
        assertEquals(
            SetupScreen.REQUEST_NOTIFICATIONS,
            setupScreenFor(micGrantedNotificationsNot, micPermanentlyDenied = true),
        )
    }

    @Test
    fun `R_002 microphone granted and notifications not granted shows the request-notifications screen`() {
        val micGrantedNotificationsNot = state(recordAudioGranted = true, notificationsGranted = false)
        assertEquals(
            SetupScreen.REQUEST_NOTIFICATIONS,
            setupScreenFor(micGrantedNotificationsNot, micPermanentlyDenied = false),
        )
    }

    @Test
    fun `both resolved returns null, meaning capture may start`() {
        val bothGranted = state(recordAudioGranted = true, notificationsGranted = true)
        assertNull(setupScreenFor(bothGranted, micPermanentlyDenied = false))
    }
}
