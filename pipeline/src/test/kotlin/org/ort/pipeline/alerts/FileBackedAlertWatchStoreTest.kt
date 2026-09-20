package org.ort.pipeline.alerts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Build-plan P31, AC-192: add/edit/delete a callsign, a keyword and a frequency watch, and the
 * master on/off switch — against the real file `:app`'s Settings screen and a future real
 * `:pipeline` wiring would both read (see [FileBackedAlertWatchStore]'s own doc comment).
 */
class FileBackedAlertWatchStoreTest {

    @TempDir
    lateinit var tempDir: File

    private fun store() = FileBackedAlertWatchStore(File(tempDir, "watches.txt"))

    @Test
    fun `a fresh, never-written store reads as enabled with no watches`() {
        val store = store()

        assertTrue(store.alertsEnabled)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `AC_192 add persists a callsign, a keyword and a frequency watch`() {
        val store = store()

        store.add(AlertWatch.Callsign(id = "w1", callsign = "K7ABC"))
        store.add(AlertWatch.Keyword(id = "w2", keyword = "skywarn"))
        store.add(AlertWatch.Frequency(id = "w3", frequencyHz = 146_520_000L))

        // A second store instance over the same file -- proves this round-trips through the file
        // itself, not just an in-memory field on the first instance.
        val reopened = store()
        assertEquals(3, reopened.list().size)
        assertEquals(setOf("w1", "w2", "w3"), reopened.list().map { it.id }.toSet())
    }

    @Test
    fun `AC_192 edit replaces the watch sharing the same id, keeping the id stable`() {
        val store = store()
        store.add(AlertWatch.Callsign(id = "w1", callsign = "K7ABC"))

        store.update(AlertWatch.Callsign(id = "w1", callsign = "K7XYZ", enabled = false))

        val reopened = store()
        val watch = reopened.list().single() as AlertWatch.Callsign
        assertEquals("w1", watch.id)
        assertEquals("K7XYZ", watch.callsign)
        assertFalse(watch.enabled)
    }

    @Test
    fun `AC_192 remove deletes exactly the named watch`() {
        val store = store()
        store.add(AlertWatch.Callsign(id = "w1", callsign = "K7ABC"))
        store.add(AlertWatch.Keyword(id = "w2", keyword = "skywarn"))

        store.remove("w1")

        val reopened = store()
        assertEquals(listOf("w2"), reopened.list().map { it.id })
    }

    @Test
    fun `FR_ALR_6 the master switch persists across reopen`() {
        val store = store()
        store.add(AlertWatch.Callsign(id = "w1", callsign = "K7ABC"))

        store.alertsEnabled = false

        val reopened = store()
        assertFalse(reopened.alertsEnabled)
        assertEquals(1, reopened.list().size, "turning alerts off must not delete any watch")
    }
}
