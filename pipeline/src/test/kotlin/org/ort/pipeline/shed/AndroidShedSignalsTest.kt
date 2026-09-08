package org.ort.pipeline.shed

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.data.OrtDatabase
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowBatteryManager
import org.robolectric.shadows.ShadowStatFs
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AndroidShedSignalsTest {

    @Test
    @Requirement("FR-RUN-3", "FR-RUN-5")
    fun `FR_RUN_3 reads a real battery percentage and charging state from BatteryManager`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bm = context.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
        val shadow: ShadowBatteryManager = Shadows.shadowOf(bm)
        shadow.setIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY, 42)
        shadow.setIsCharging(true)

        val db = OrtDatabase.create(context, inMemory = true)
        val signals = AndroidShedSignals(context, db.workQueueDao(), context.filesDir)

        assertEquals(42, signals.batteryPercent())
        assertTrue(signals.isCharging())
    }

    @Test
    @Requirement("FR-RUN-3")
    fun `FR_RUN_3 an unreadable battery percentage reads as the conservative sentinel, not a healthy default`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Robolectric's default ShadowBatteryManager reports an unset capacity as -1, the same
        // "unsupported" value the real BatteryManager API documents -- nothing needs to be forced.
        val db = OrtDatabase.create(context, inMemory = true)
        val signals = AndroidShedSignals(context, db.workQueueDao(), context.filesDir)

        assertEquals(AndroidShedSignals.UNKNOWN_BATTERY_PERCENT, signals.batteryPercent())
    }

    @Test
    @Requirement("FR-STO-4", "FR-RUN-6")
    fun `FR_STO_4 reads a real free-storage figure from StatFs, not a fabricated healthy value`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = context.filesDir
        // ShadowStatFs reports 0 for any path it has no registered stats for (Robolectric's
        // StatFs never actually touches a filesystem) -- register real numbers so this asserts a
        // genuine StatFs read reaches AndroidShedSignals, not a coincidental zero.
        val blockCount = 1_000
        val freeBlocks = 500
        val availableBlocks = 400
        ShadowStatFs.registerStats(dir, blockCount, freeBlocks, availableBlocks)
        val db = OrtDatabase.create(context, inMemory = true)
        val signals = AndroidShedSignals(context, db.workQueueDao(), dir)

        assertEquals(availableBlocks.toLong() * ShadowStatFs.BLOCK_SIZE, signals.freeStorageBytes())
    }

    @Test
    @Requirement("FR-STO-4")
    fun `FR_STO_4 a path with no registered stats reads as the conservative sentinel, never a healthy default`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = OrtDatabase.create(context, inMemory = true)
        // No ShadowStatFs.registerStats call for this path -- the honest "cannot be read" case.
        val signals = AndroidShedSignals(context, db.workQueueDao(), File("/never/registered/with/shadow-stat-fs"))

        assertEquals(AndroidShedSignals.UNKNOWN_FREE_STORAGE_BYTES, signals.freeStorageBytes())
    }

    @Test
    @Requirement("FR-RUN-5", "FR-RUN-6")
    fun `FR_RUN_5 queueBacklog reflects the real work queue count only after refreshBacklog is called`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = OrtDatabase.create(context, inMemory = true)
        db.sessionDao().insert(org.ort.pipeline.PipelineTestFixtures.session())
        db.transmissionDao().insert(org.ort.pipeline.PipelineTestFixtures.transmission("T1"))
        org.ort.data.WorkQueue(db, org.ort.testing.TestClock()).enqueue("T1", org.ort.core.PassId.B_OFFLINE)

        val signals = AndroidShedSignals(context, db.workQueueDao(), context.filesDir)
        assertEquals("unrefreshed backlog must not fabricate the real count", 0, signals.queueBacklog())

        signals.refreshBacklog()
        assertEquals(1, signals.queueBacklog())
    }
}
