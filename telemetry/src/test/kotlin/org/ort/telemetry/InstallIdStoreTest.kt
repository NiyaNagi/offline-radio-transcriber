package org.ort.telemetry

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.runner.RunWith
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

class InMemoryInstallIdStoreTest {

    @org.junit.jupiter.api.Test
    fun `currentId is stable across reads`() {
        val store = InMemoryInstallIdStore(seed = "fixed-id")
        assertEquals("fixed-id", store.currentId())
        assertEquals("fixed-id", store.currentId())
    }

    @org.junit.jupiter.api.Test
    @Requirement("AC-181", "FR-ANL-11")
    fun `AC_181_reset produces a different id and reports the previous one`() {
        val store = InMemoryInstallIdStore(seed = "old-id", nextId = { "new-id" })

        val reset = store.reset()

        assertEquals("old-id", reset.previousId)
        assertEquals("new-id", reset.newId)
        assertEquals("new-id", store.currentId())
        assertNotEquals(reset.previousId, reset.newId)
    }
}

@RunWith(RobolectricTestRunner::class)
class SharedPreferencesInstallIdStoreTest {

    private fun realPrefs() = ApplicationProvider.getApplicationContext<Application>()
        .getSharedPreferences(SharedPreferencesInstallIdStore.PREFS_NAME, Application.MODE_PRIVATE)
        .also { it.edit().clear().commit() }

    @Test
    fun `a fresh store mints and persists an id`() {
        val prefs = realPrefs()
        val id = SharedPreferencesInstallIdStore(prefs).currentId()

        val reopened = SharedPreferencesInstallIdStore(prefs)

        assert(reopened.currentId() == id)
    }

    @Test
    fun `reset persists the new id for the next instance`() {
        val prefs = realPrefs()
        val store = SharedPreferencesInstallIdStore(prefs)
        val original = store.currentId()

        val reset = store.reset()

        assert(reset.previousId == original)
        assert(SharedPreferencesInstallIdStore(prefs).currentId() == reset.newId)
    }
}
