package org.ort.data.dao

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.Tier
import org.ort.data.OrtDatabase
import org.ort.data.TestFixtures
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * Register R-204's follow-up (schema v4, FR-REP-2, FR-REP-9): [TransmissionDao.setProcessedTier]
 * and [TransmissionDao.idsBelowProcessedTier] — the write/read pair
 * [org.ort.pipeline.reprocess.ReprocessRunner] and (once wired, WP11d) `ImprovePolling` need to
 * stop offering an already-reprocessed record as improvable. See
 * [org.ort.data.entity.TransmissionEntity.processedTier]'s own doc comment.
 */
@RunWith(RobolectricTestRunner::class)
public class TransmissionDaoTest {

    private lateinit var db: OrtDatabase

    @Before
    public fun openDatabase() {
        db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
    }

    @Test
    @Requirement("FR-REP-2", "FR-REP-9", "R-204")
    public fun R_204_a_fresh_transmission_carries_no_processed_tier(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session())
        db.transmissionDao().insert(TestFixtures.transmission("TX1"))

        assertNull(db.transmissionDao().getById("TX1")!!.processedTier)
    }

    @Test
    @Requirement("FR-REP-2", "FR-REP-9", "R-204")
    public fun R_204_setProcessedTier_stamps_the_tier_a_completed_run_actually_used(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session())
        db.transmissionDao().insert(TestFixtures.transmission("TX1"))

        db.transmissionDao().setProcessedTier("TX1", Tier.T2)

        assertEquals(Tier.T2, db.transmissionDao().getById("TX1")!!.processedTier)
    }

    @Test
    @Requirement("FR-REP-2", "FR-REP-9", "R-204")
    public fun R_204_idsBelowProcessedTier_includes_never_processed_and_below_target_never_at_or_above(): Unit =
        runTest {
            db.sessionDao().insert(TestFixtures.session())
            db.transmissionDao().insert(TestFixtures.transmission("TX-NEVER"))
            db.transmissionDao().insert(TestFixtures.transmission("TX-T1"))
            db.transmissionDao().insert(TestFixtures.transmission("TX-T2"))
            db.transmissionDao().insert(TestFixtures.transmission("TX-T3"))
            db.transmissionDao().setProcessedTier("TX-T1", Tier.T1)
            db.transmissionDao().setProcessedTier("TX-T2", Tier.T2)
            db.transmissionDao().setProcessedTier("TX-T3", Tier.T3)

            // A caller targeting T2 (achievable now) still wants records below it: never processed,
            // or last processed at T0/T1. T2 and T3 are already at or above the target.
            val stillImprovable = db.transmissionDao().idsBelowProcessedTier(listOf(Tier.T0, Tier.T1))

            assertEquals(setOf("TX-NEVER", "TX-T1"), stillImprovable.toSet())
        }

    @Test
    @Requirement("FR-REP-2", "FR-REP-9", "R-204")
    public fun R_204_setProcessedTier_overwrites_a_previous_value_rather_than_stacking(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session())
        db.transmissionDao().insert(TestFixtures.transmission("TX1"))

        db.transmissionDao().setProcessedTier("TX1", Tier.T1)
        db.transmissionDao().setProcessedTier("TX1", Tier.T3)

        assertEquals(Tier.T3, db.transmissionDao().getById("TX1")!!.processedTier)
    }

    // Register R-1055 (spec): listByIds/listByStationId — a curated Log filter's own cross-session
    // read, never scoped to one session the way listBySession is.

    @Test
    public fun R_1055_listByIds_finds_rows_across_different_sessions(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("S-LIVE"))
        db.sessionDao().insert(TestFixtures.session("S-EARLIER"))
        db.transmissionDao().insert(TestFixtures.transmission("EARLIER-1", sessionId = "S-EARLIER"))
        db.transmissionDao().insert(TestFixtures.transmission("EARLIER-2", sessionId = "S-EARLIER"))
        db.transmissionDao().insert(TestFixtures.transmission("LIVE-1", sessionId = "S-LIVE"))

        val found = db.transmissionDao().listByIds(listOf("EARLIER-1", "EARLIER-2"))

        assertEquals(setOf("EARLIER-1", "EARLIER-2"), found.map { it.id }.toSet())
    }

    @Test
    public fun R_1055_listByIds_silently_omits_an_id_with_no_row_never_throws(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session())
        db.transmissionDao().insert(TestFixtures.transmission("TX1"))

        val found = db.transmissionDao().listByIds(listOf("TX1", "GHOST"))

        assertEquals(listOf("TX1"), found.map { it.id })
    }

    @Test
    public fun R_1055_listByStationId_finds_a_stations_overs_across_different_sessions(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("S-LIVE"))
        db.sessionDao().insert(TestFixtures.session("S-EARLIER"))
        db.transmissionDao().insert(
            TestFixtures.transmission("EARLIER-1", sessionId = "S-EARLIER", stationId = "K7LWH"),
        )
        db.transmissionDao().insert(TestFixtures.transmission("LIVE-1", sessionId = "S-LIVE", stationId = "W7NPC"))

        val found = db.transmissionDao().listByStationId("K7LWH")

        assertEquals(listOf("EARLIER-1"), found.map { it.id })
    }

    // Register R-1032 (constitution VI: "no number without ... execution provider"):
    // TransmissionDao.setExecutionProvider -- see TransmissionEntity.executionProvider's own doc
    // comment for why this column existed but nothing in production ever wrote to it.

    @Test
    @Requirement("R-1032")
    public fun R_1032_a_fresh_transmission_carries_no_execution_provider(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session())
        db.transmissionDao().insert(TestFixtures.transmission("TX1"))

        assertNull(db.transmissionDao().getById("TX1")!!.executionProvider)
    }

    @Test
    @Requirement("R-1032")
    public fun R_1032_setExecutionProvider_stamps_the_real_provider_a_run_actually_used(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session())
        db.transmissionDao().insert(TestFixtures.transmission("TX1"))

        db.transmissionDao().setExecutionProvider("TX1", "cpu")

        assertEquals("cpu", db.transmissionDao().getById("TX1")!!.executionProvider)
    }

    @Test
    @Requirement("R-1032")
    public fun R_1032_setExecutionProvider_overwrites_a_previous_value_rather_than_stacking(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session())
        db.transmissionDao().insert(TestFixtures.transmission("TX1"))

        db.transmissionDao().setExecutionProvider("TX1", "cpu")
        db.transmissionDao().setExecutionProvider("TX1", "nnapi")

        assertEquals("nnapi", db.transmissionDao().getById("TX1")!!.executionProvider)
    }
}
