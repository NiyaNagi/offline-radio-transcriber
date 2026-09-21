package org.ort.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.failures.DatabaseOpenFailure
import org.ort.data.OrtDatabase
import org.robolectric.RobolectricTestRunner

/**
 * Register R-1120 (halt), FR-STO-7, constitution III: [DatabaseStartupWiring] is the safety net
 * behind [OrtDatabase]'s own hand-written-schema dedup guard (see that guard's own kdoc for the
 * actual fix) — this proves the net itself, decoupled from any real SQLite violation, using the
 * [DatabaseStartupWiring.openOrRecordFailure] `opener` seam. Before this task, a throw out of
 * [OrtDatabase.create] propagated straight out of whichever `:app` call site hit it first,
 * crashing the process outright, on every subsequent launch, with no in-app route out — the
 * defect this test proves closed at this one controlled call site
 * ([org.ort.app.OrtApplication.onCreate]'s own).
 */
@RunWith(RobolectricTestRunner::class)
public class DatabaseStartupWiringTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    public fun clearFailureState() {
        DatabaseOpenFailure.clear()
    }

    @Test
    public fun a_successful_open_returns_the_database_and_clears_any_stale_failure() {
        // A previous, unrelated attempt left a failure recorded — a successful open now must not
        // leave that stale signal latched forever (DatabaseOpenFailure's own kdoc).
        DatabaseOpenFailure.record("stale failure from an earlier attempt")
        val fakeDb = OrtDatabase.create(context, inMemory = true)

        val result = DatabaseStartupWiring.openOrRecordFailure(context) { fakeDb }

        assertEquals(fakeDb, result)
        assertNull(DatabaseOpenFailure.reason)
    }

    @Test
    public fun a_throwing_opener_is_caught_and_recorded_instead_of_crashing_the_caller() {
        val result = DatabaseStartupWiring.openOrRecordFailure(context) {
            throw IllegalStateException("UNIQUE constraint failed: transcript.transmissionId")
        }

        // R-1120: the throw does not escape this call — the caller (OrtApplication.onCreate)
        // never sees it, which is the whole point of the safety net.
        assertNull(result)
        assertEquals("UNIQUE constraint failed: transcript.transmissionId", DatabaseOpenFailure.reason)
    }

    @Test
    public fun a_throwable_with_no_message_records_its_class_name_never_a_blank_reason() {
        val result = DatabaseStartupWiring.openOrRecordFailure(context) {
            throw IllegalStateException()
        }

        assertNull(result)
        // Constitution I: never a blank/invented reason — the real class name stands in when the
        // throwable itself carried no message.
        assertEquals("IllegalStateException", DatabaseOpenFailure.reason)
    }
}
