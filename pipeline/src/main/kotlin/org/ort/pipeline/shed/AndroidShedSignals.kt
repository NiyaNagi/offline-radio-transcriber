package org.ort.pipeline.shed

import android.content.Context
import android.os.BatteryManager
import android.os.StatFs
import org.ort.data.dao.WorkQueueDao
import java.io.File

/**
 * The production [ShedSignals] (audit F-007). Before this class existed, nothing implemented the
 * interface outside tests — `ShedController` was never constructed in the running capture service,
 * so FR-RUN-3's shed order could never trigger and a full disk was discovered only when a write
 * threw.
 *
 * **Never fabricates a healthy reading.** Where a signal genuinely cannot be read, this returns a
 * documented sentinel chosen on the conservative side — the side that leans toward shedding or
 * stopping, never toward "everything is fine" (constitution I, IV):
 *
 * - [batteryPercent] returns [UNKNOWN_BATTERY_PERCENT] (a value below every plausible
 *   `criticalBatteryPercent`) when `BatteryManager` cannot report a capacity in `0..100`. Combined
 *   with [isCharging] defaulting to `false` when unknown, an unreadable battery reads as "critical
 *   and not charging" to [ShedController] — forcing the conservative defer-processing level, never
 *   silently reading as healthy.
 * - [freeStorageBytes] returns [UNKNOWN_FREE_STORAGE_BYTES] (`0L`) when `StatFs` cannot be read,
 *   which reads as certainly below any storage floor a caller checks against — forcing the loud
 *   stop path rather than silently continuing to write.
 *
 * [queueBacklog] is **not** a live read — Room DAOs are suspend functions and [ShedSignals] is a
 * plain synchronous interface (`ShedController.sample()` is called from a hot, non-suspending
 * loop). [refreshBacklog] must be called (from a coroutine) once per sample tick before
 * `sample()`; [queueBacklog] then returns the value it cached. An un-refreshed instance reports
 * `0`, which is the correct value before the first tick has ever run (no items enqueued yet), not
 * a fabricated "healthy" default for a signal that failed to read — see [refreshBacklog]'s KDoc for
 * what happens if the read itself fails.
 */
public class AndroidShedSignals(
    private val context: Context,
    private val workQueueDao: WorkQueueDao,
    private val filesDir: File,
) : ShedSignals {

    @Volatile
    private var cachedBacklog: Int = 0

    /**
     * Re-reads the queue backlog (total undrained `work_queue_item` rows: `READY` + `LEASED` +
     * `FAILED` — everything not yet [org.ort.data.WorkQueue.completePass]d) and caches it for the
     * next [queueBacklog] call. Call once per `ShedController.sample()` tick, before `sample()`.
     *
     * A failed read is left as **whatever was last successfully cached**, not reset to `0` —
     * resetting to `0` would silently read as "backlog cleared" to `ShedController`, which is the
     * opposite of the conservative direction this class otherwise takes. If the very first read
     * fails, `0` is what a genuinely empty queue also reports; there is no way to distinguish
     * "unread" from "empty" in an `Int`, which is the one honest gap in this signal.
     */
    public suspend fun refreshBacklog() {
        cachedBacklog = try {
            workQueueDao.count()
        } catch (e: Exception) {
            cachedBacklog
        }
    }

    override fun queueBacklog(): Int = cachedBacklog

    override fun batteryPercent(): Int {
        val bm = batteryManager() ?: return UNKNOWN_BATTERY_PERCENT
        val capacity = try {
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            return UNKNOWN_BATTERY_PERCENT
        }
        return if (capacity in 0..100) capacity else UNKNOWN_BATTERY_PERCENT
    }

    override fun isCharging(): Boolean {
        val bm = batteryManager() ?: return false
        return try {
            bm.isCharging
        } catch (e: Exception) {
            false
        }
    }

    override fun freeStorageBytes(): Long = try {
        StatFs(filesDir.absolutePath).availableBytes
    } catch (e: Exception) {
        UNKNOWN_FREE_STORAGE_BYTES
    }

    private fun batteryManager(): BatteryManager? = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    public companion object {
        /** Below every plausible `criticalBatteryPercent` — see the class KDoc. */
        public const val UNKNOWN_BATTERY_PERCENT: Int = -1

        /** Reads as certainly below any storage floor — see the class KDoc. */
        public const val UNKNOWN_FREE_STORAGE_BYTES: Long = 0L
    }
}
