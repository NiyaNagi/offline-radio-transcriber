package org.ort.app.diagnostics

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.pipeline.capture.ThermalStatus
import org.robolectric.RobolectricTestRunner

/**
 * WP11e (register R-137, build-plan brief step 4): `device.json` carries model, Android version,
 * app version + `BuildConfig.GIT_SHORT_COMMIT`, ABI, RAM class and thermal headroom class — and,
 * as a structural guarantee (never proven only by review), no account, serial, IMEI or advertising
 * id, none of which this producer ever reads from any Android API in the first place.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceJsonProducerTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `FR_OBS_1 device json carries the required fields`() = runTest {
        val bytes = DeviceJsonProducer.produce(context)
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        assertTrue(json.has("model"))
        assertTrue(json.has("manufacturer"))
        assertTrue(json.has("androidVersion"))
        assertTrue(json.has("sdkInt"))
        assertTrue(json.has("abi"))
        assertTrue(json.has("appVersion"))
        assertTrue(json.has("gitShortCommit"))
        assertTrue(json.has("ramClass"))
        assertTrue(json.has("thermalHeadroomClass"))
    }

    @Test
    fun `AC_109 device json never carries a serial, IMEI, advertising id or account`() = runTest {
        val bytes = DeviceJsonProducer.produce(context)
        val text = String(bytes, Charsets.UTF_8).lowercase()
        assertFalse(text.contains("imei"))
        assertFalse(text.contains("serial"))
        assertFalse(text.contains("advertising"))
        assertFalse(text.contains("account"))
        assertFalse(text.contains("android_id"))
    }

    @Test
    fun `FR_OBS_1 the field set is exactly the closed set, no more`() = runTest {
        val bytes = DeviceJsonProducer.produce(context)
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        assertEquals(
            setOf(
                "model", "manufacturer", "androidVersion", "sdkInt", "abi",
                "appVersion", "gitShortCommit", "ramClass", "thermalHeadroomClass",
            ),
            json.keys().asSequence().toSet(),
        )
    }

    @Test
    fun `FR_OBS_1 ramClassLabel rounds to a coarse GB bucket, never a raw byte count`() {
        assertEquals("6 GB class", ramClassLabel(6L * 1024 * 1024 * 1024))
        assertEquals("unknown", ramClassLabel(0L))
        assertEquals("unknown", ramClassLabel(-1L))
    }

    @Test
    fun `FR_OBS_1 thermalHeadroomClass mirrors ThermalStatus's own tri-state`() {
        assertEquals("nominal", thermalHeadroomClass(ThermalStatus.State.Nominal(0, null)))
        assertEquals("warm", thermalHeadroomClass(ThermalStatus.State.Warm(2, null, sinceMillis = 1L)))
        assertEquals("hot", thermalHeadroomClass(ThermalStatus.State.Hot(4, null, sinceMillis = 1L)))
    }
}
