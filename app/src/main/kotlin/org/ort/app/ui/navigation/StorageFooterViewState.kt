package org.ort.app.ui.navigation

import android.content.Context
import android.os.StatFs
import java.io.File

/**
 * The drawer footer's storage figures (D26, canvas.json's `integrated` annotation: "the drawer
 * footer carries the storage budget... a thing you actually watch"). D26 itself specifies
 * **independent, user-set budgets** for gated transmission audio and the continuous archive —
 * that setting does not exist yet (no prompt has built it: `:data` carries no budget table, and
 * nothing tracks bytes per retention category). Rather than fabricate a number against a budget
 * that isn't real, [fromDeviceStorage] reports the plain, real quantity available to this
 * prompt — used vs. total space on the volume the app's private storage lives on — clearly
 * labelled as a placeholder for D26's real, per-category figure once retention (a later prompt)
 * exists to produce it.
 */
public data class StorageFooterViewState(
    public val usedBytes: Long,
    public val totalBytes: Long,
    /** `true` once this reflects D26's actual per-category budgets rather than raw device space. */
    public val isPlaceholder: Boolean,
) {
    public val usedFraction: Float
        get() = if (totalBytes <= 0L) 0f else (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)

    public companion object {
        public fun fromDeviceStorage(context: Context): StorageFooterViewState {
            val dir: File = context.filesDir
            val stat = StatFs(dir.path)
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            return StorageFooterViewState(
                usedBytes = (totalBytes - freeBytes).coerceAtLeast(0L),
                totalBytes = totalBytes,
                isPlaceholder = true,
            )
        }
    }
}

/** Formats bytes as whole gigabytes, e.g. `38 GB` — matches the canvas's precision, not a decimal. */
public fun Long.toGigabyteLabel(): String {
    val gb = this / 1_000_000_000.0
    return "%.1f GB".format(gb)
}
