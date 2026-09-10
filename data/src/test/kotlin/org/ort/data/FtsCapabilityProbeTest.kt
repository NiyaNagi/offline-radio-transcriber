package org.ort.data

import androidx.room.useWriterConnection
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * This task (register R-204 follow-up, FR-UI-3): [OrtDatabase.isFts5Supported] replaced
 * `createFtsIndex`'s old `catch (e: android.database.SQLException) { e.message?.contains(...) }`
 * pattern — proven, by a three-instrumented-CI-run investigation (commit 5a9f53a), to read a
 * message whose *text* differs by platform for the identical failure — with a positive capability
 * probe (`PRAGMA compile_options` for `ENABLE_FTS5`) decided once and cached. This class is the
 * two things that decision needs proven, on both sides:
 *
 * - **fts5 present** (every real build reachable from this module's test suite —
 *   `BundledSQLiteDriver` always ships fts5 compiled in): the probe says so, and the index/search
 *   path built on it works — already covered end to end by [FtsIndexRepairTest] and
 *   [SearchDaoFullTextTest]; [FR_UI_3_the_probe_itself_reports_fts5_present_on_the_real_driver]
 *   and [FR_UI_3_hasTextSearchIndex_is_true_once_the_database_is_open] add direct coverage of the
 *   probe and its `:app`-facing [hasTextSearchIndex] seam specifically, not just their downstream
 *   effects.
 * - **fts5 absent**: no SQLite build reachable from this test suite genuinely lacks fts5, so this
 *   is exercised through [OrtDatabase.fts5SupportOverrideForTest] — the seam this task introduced
 *   for exactly this reason (see that field's own doc comment). What
 *   [FR_UI_3_a_build_without_fts5_opens_honestly_without_the_index_instead_of_throwing] proves is
 *   real given that seam: [OrtDatabase.create] does not throw when fts5 is absent, ordinary
 *   (non-text) database operations still work, and [hasTextSearchIndex] honestly reports `false`
 *   rather than the index silently existing anyway. It does **not** prove that the real
 *   `PRAGMA compile_options` query reads `ENABLE_FTS5` as absent on a genuinely fts5-less SQLite
 *   build — no such build is available to this suite to check that against.
 */
@RunWith(RobolectricTestRunner::class)
public class FtsCapabilityProbeTest {

    /**
     * Belt-and-braces: [OrtDatabase.fts5SupportOverrideForTest] is a companion-object `var`,
     * shared by every test in this JVM fork. Each test that sets it also resets it itself in a
     * `finally`, but this guards against a test that fails before reaching that reset from
     * poisoning every test that runs after it in the same fork.
     */
    @After
    public fun clearOverride() {
        OrtDatabase.fts5SupportOverrideForTest = null
    }

    @Test
    @Requirement("FR-UI-3", "R-204")
    public fun FR_UI_3_the_probe_itself_reports_fts5_present_on_the_real_driver(): Unit = runTest {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        try {
            val supported = db.useWriterConnection { connection -> OrtDatabase.isFts5Supported(connection) }
            assertTrue("BundledSQLiteDriver always ships fts5 compiled in", supported)
        } finally {
            db.close()
        }
    }

    @Test
    @Requirement("FR-UI-3", "R-204")
    public fun FR_UI_3_hasTextSearchIndex_is_true_once_the_database_is_open(): Unit = runTest {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        try {
            assertTrue(db.hasTextSearchIndex())
        } finally {
            db.close()
        }
    }

    @Test
    @Requirement("FR-UI-3", "R-204")
    public fun FR_UI_3_a_build_without_fts5_opens_honestly_without_the_index_instead_of_throwing(): Unit = runTest {
        OrtDatabase.fts5SupportOverrideForTest = false
        try {
            // The load-bearing assertion: create() must not throw just because this build lacks
            // fts5 — a genuine missing-fts5 condition degrades, it does not fail to open.
            val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
            try {
                assertFalse(
                    "the index must not exist when the probe says fts5 is unavailable",
                    db.hasTextSearchIndex(),
                )
                // And the rest of the database is genuinely usable — this is a degrade, not a
                // partially-broken open. An ordinary write/read against an unrelated table proves
                // the rest of applyHandWrittenSchema (the partial unique indexes) still ran too.
                db.sessionDao().insert(TestFixtures.session())
                db.transmissionDao().insert(TestFixtures.transmission("TX1", samplePosition = 1L))
                val filtersOnly = db.searchDao().search(text = null)
                assertEquals(listOf("TX1"), filtersOnly.map { it.id })
            } finally {
                db.close()
            }
        } finally {
            OrtDatabase.fts5SupportOverrideForTest = null
        }
    }
}
