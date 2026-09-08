package org.ort.app.ui.navigation

import android.content.Context
import android.os.StatFs
import java.io.File

/**
 * The drawer footer's storage figures (D26, canvas.json's `integrated` annotation: "the drawer
 * footer carries the storage budget... a thing you actually watch").
 *
 * Audit F-020: the previous shape reported whole-device [StatFs] used/total figures under a
 * header that read "Audio" — a device-wide number displayed as if it were per-category — against
 * a fixed "of 60 GB" denominator this build has never actually set. D26 specifies **independent,
 * user-set budgets** per retention category (FR-STO-3); no prompt has built that setting yet (no
 * budget table in `:data`, nothing tracks bytes per category), so [fromAudioDirectory] reports
 * only what is real today: [audioUsedBytes] is the measured sum of the retained transmission audio
 * files this device has actually written, under the same `audio/<sessionId>/<transmissionId>.flac`
 * layout `FlacStore`/`TransmissionEntity.audioPath()` use, and [freeBytes] is the real free space
 * on the volume the app's private storage lives on ([StatFs], unchanged from before). [hasBudget]
 * stays `false` — and the footer must show "no budget set" rather than any "of N GB" — until
 * FR-STO-3's budget setting exists to make that denominator real.
 */
public data class StorageFooterViewState(
    public val audioUsedBytes: Long,
    public val freeBytes: Long,
    /** `true` once FR-STO-3's per-category budget setting exists and a budget has actually been set. */
    public val hasBudget: Boolean = false,
) {
    public companion object {
        public fun fromAudioDirectory(context: Context): StorageFooterViewState {
            val audioDir = File(context.filesDir, "audio")
            val usedBytes = audioDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val stat = StatFs(context.filesDir.path)
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            return StorageFooterViewState(audioUsedBytes = usedBytes, freeBytes = freeBytes, hasBudget = false)
        }
    }
}

/** Formats bytes as decimal gigabytes, e.g. `38.2 GB` — matches the canvas's precision. */
public fun Long.toGigabyteLabel(): String {
    val gb = this / 1_000_000_000.0
    return "%.1f GB".format(gb)
}
