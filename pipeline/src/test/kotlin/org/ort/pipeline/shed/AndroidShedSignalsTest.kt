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

    // R-1141 (FR-TIER-3): the Settings tier override lives in the same on-disk preferences file
    // `:app`'s SharedPreferencesSettingsStore writes (`:pipeline` cannot depend on `:app` --
    // module graph, see AndroidShedSignals.tierCapOrdinal()'s own kdoc for the shared-file
    // contract this mirrors). Writing the raw preference here, rather than constructing `:app`
    // types, is deliberate -- this test proves the two sides agree on the file/key/value shape
    // without creating the module edge the production code itself must not have.

    @Test
    @Requirement("FR-TIER-3")
    fun `FR_TIER_3 reads a real tier override written to the shared settings preferences file`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences(AndroidShedSignals.SETTINGS_PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().putString(AndroidShedSignals.KEY_TIER_OVERRIDE, "T1").commit()
        val db = OrtDatabase.create(context, inMemory = true)
        val signals = AndroidShedSignals(context, db.workQueueDao(), context.filesDir)

        assertEquals(1, signals.tierCapOrdinal())
    }

    @Test
    @Requirement("FR-TIER-3")
    fun `FR_TIER_3 no override written reads as null, never as ordinal 0`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = OrtDatabase.create(context, inMemory = true)
        val signals = AndroidShedSignals(context, db.workQueueDao(), context.filesDir)

        assertEquals(null, signals.tierCapOrdinal())
    }

    @Test
    @Requirement("FR-TIER-3")
    fun `FR_TIER_3 an unparseable stored override reads as null rather than a fabricated ordinal`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences(AndroidShedSignals.SETTINGS_PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().putString(AndroidShedSignals.KEY_TIER_OVERRIDE, "not-a-real-tier").commit()
        val db = OrtDatabase.create(context, inMemory = true)
        val signals = AndroidShedSignals(context, db.workQueueDao(), context.filesDir)

        assertEquals(null, signals.tierCapOrdinal())
    }

    @Test
    @Requirement("FR-TIER-3", "FR-TIER-4")
    fun `FR_TIER_3 the real preference reaches ShedController end to end, even with a healthy device`() {
        // The full real chain R-1141 wires: a real operator override, written the same way
        // SettingsStore.tierOverrideName is, read by a real AndroidShedSignals, floors a real
        // ShedController's level -- proving the two pieces this row owns actually agree, not just
        // each in isolation.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // A healthy, plausible battery reading -- see FR_RUN_3's own test above for why an unset
        // Robolectric default (-1, "unreadable") would otherwise read as critical and mask this
        // test's own point behind the battery-forced level 4 path.
        val bm = context.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
        val batteryShadow: ShadowBatteryManager = Shadows.shadowOf(bm)
        batteryShadow.setIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY, 100)
        batteryShadow.setIsCharging(true)
        context.getSharedPreferences(AndroidShedSignals.SETTINGS_PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().putString(AndroidShedSignals.KEY_TIER_OVERRIDE, "T0").commit()
        val db = OrtDatabase.create(context, inMemory = true)
        val signals = AndroidShedSignals(context, db.workQueueDao(), context.filesDir)
        val controller = ShedController(signals, org.ort.testing.TestClock())

        controller.sample()

        assertEquals(
            "held at T0 (ordinal 0) must floor a healthy, idle device (level 0 from signals alone) at level 3",
            3,
            controller.currentLevel,
        )
    }
}
