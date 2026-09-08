package org.ort.app.structural

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * FR-STO-8 / NFR-6a / FR-PLT-5 — audit F-027.
 *
 * Robolectric builds `app`'s merged manifest the same way the Android build does, so reading
 * [android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP] off it here is a genuine check of the
 * manifest attribute `AndroidManifest.xml` declares (`android:allowBackup="false"`) — not a
 * hand-copy of the XML. It proves the app is not eligible for Auto Backup / `adb backup`; it
 * does NOT prove anything about a runtime backup attempt, and it does not check
 * `fullBackupContent`, which is meaningless once backup itself is off. JVM/Robolectric only, no
 * device.
 */
@RunWith(RobolectricTestRunner::class)
class AllowBackupTest {

    @Test
    fun `FR_STO_8_NFR_6a_FR_PLT_5 the merged manifest declares allowBackup false`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val flags = context.packageManager.getApplicationInfo(context.packageName, 0).flags
        val allowBackupFlag = android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP

        assertEquals(
            "expected android:allowBackup=\"false\" so app-private storage is excluded from cloud backup",
            0,
            flags and allowBackupFlag,
        )
    }
}
