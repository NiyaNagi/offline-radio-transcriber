package org.ort.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AC-93 / NFR-5 — the app installs and runs at the minimum supported API level.
 *
 * Runs on the API-26 emulator job (`.github/workflows/emulator.yml`). Instrumented tests are
 * deliberately not on the push CI (test-plan §8); this one is the automated half of AC-93,
 * paired with the merged-manifest `minSdkVersion` assertion in the `assemble` job.
 */
@RunWith(AndroidJUnit4::class)
class MinSdkLaunchTest {

    @Test
    fun AC_93_application_starts_on_min_sdk() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertTrue("expected the OrtApplication to be instantiated", app is OrtApplication)
        assertEquals("org.ort.app", app.packageName)
    }
}
