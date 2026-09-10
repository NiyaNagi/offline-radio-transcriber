package org.ort.pipeline.digest

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** D36, FR-DIG-3b: the real persisted store the CF04 toggle (WPE) will write through. */
@RunWith(RobolectricTestRunner::class)
class SharedPreferencesProseDigestSettingsStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `defaults to enabled per D36 and FR-DIG-3b`() {
        val store = SharedPreferencesProseDigestSettingsStore(context)

        assertTrue(store.isEnabled())
    }

    @Test
    fun `setEnabled persists across a fresh instance over the same context`() {
        SharedPreferencesProseDigestSettingsStore(context).setEnabled(false)

        val reopened = SharedPreferencesProseDigestSettingsStore(context)

        assertEquals(false, reopened.isEnabled())
    }

    @Test
    fun `ProseDigestSettings backed by the shared-preferences store round-trips a disable`() {
        val engine = org.ort.llm.FakeLlmEngine()
        ProseDigestSettings(SharedPreferencesProseDigestSettingsStore(context)).setEnabled(false, engine)

        val next = ProseDigestSettings(SharedPreferencesProseDigestSettingsStore(context))

        assertEquals(false, next.enabled.value)
    }
}
