package org.ort.pipeline.capture

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioDeviceKind
import org.ort.capture.android.fake.FakeAudioIo
import org.ort.core.capture.CaptureMode
import org.ort.data.OrtDatabase
import org.ort.pipeline.passb.AsrEngineAvailability
import org.ort.pipeline.rig.CaptureConfiguration
import org.ort.pipeline.rig.FrequencyProvenance
import org.ort.pipeline.rig.InMemoryCaptureConfigurationStore
import org.ort.pipeline.shed.FakeShedSignals
import org.ort.rig.NullRigModule
import org.ort.testing.Requirement
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Register R-1030 (halt) and R-1031 (spec): two of the three facts the operator's own on-device
 * dump proved were false. S10b's "Continue without connecting — the frequency is logged by hand
 * until you link the radio" caption had no wiring behind it at all
 * ([org.ort.pipeline.rig.RigSupervisor.setManualFrequencyOverrideHz] had exactly one caller in the
 * whole repository, and it was a test) — every real over carried `frequencyHz: null`,
 * `frequencyProvenance: "unknown"` regardless of what the operator typed. Every real
 * [org.ort.data.entity.SessionEntity] was also stamped the v0 wiring's own literal `"smoke-test"`
 * forever. Follows [RealCaptureServiceCaptureModeTest]'s own pattern (real `ServiceController`, no
 * ASR needed — both facts are written before Pass B ever runs).
 */
@RunWith(RobolectricTestRunner::class)
public class RealCaptureServiceManualFrequencyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun waitUntil(timeoutMillis: Long, intervalMillis: Long = 100, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(intervalMillis)
        }
        assertTrue("condition not met within ${timeoutMillis}ms", predicate())
    }

    @Test
    @Requirement("FR-CAP-13", "FR-RIG-8", "FR-RIG-9", "R-1030")
    public fun `R_1030 a manual frequency configured with no rig reaches the persisted transmission as MANUAL`() {
        val db = OrtDatabase.create(context, inMemory = true)
        val device = AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "Fake USB adapter")
        val fakeIo = FakeAudioIo(deviceSampleRate = 16_000, devices = listOf(device))
        fakeIo.forceRoutedDevice(device)
        // >= 250ms loud, then >= 600ms silence to close the segment via hangover -- the exact
        // margin RealCaptureServiceTest's own AC_31 case uses.
        repeat(5) { fakeIo.enqueueFrames(ShortArray(1_600) { 6_000 }) }
        repeat(12) { fakeIo.enqueueFrames(ShortArray(1_600) { 0 }) }

        // The exact S10b "no radio" case (register R-1030's own reproduction): no rig id, no
        // transport, only the hand-entered frequency.
        val config = CaptureConfiguration(
            mode = CaptureMode.LOCAL_MICROPHONE,
            selectedInputId = device.id,
            rigId = NullRigModule.ID,
            rigTransportKind = null,
            manualFrequencyHz = 146_520_000L,
        )
        val store = InMemoryCaptureConfigurationStore(initial = config, isCapturing = { CaptureState.isCapturing })
        val sessionId = "TEST-R-1030"

        val controller = Robolectric.buildService(RealCaptureService::class.java).create()
        val service = controller.get()
        service.dependencies = RealCaptureService.Dependencies(
            database = { db },
            audioIo = { _ -> fakeIo to device },
            asrEngine = { AsrEngineAvailability.Unavailable("no model in this test") },
            shedSignals = { _, _, _ -> FakeShedSignals() },
            captureConfigurationStore = { store },
        )

        try {
            val startIntent = Intent(context, RealCaptureService::class.java)
                .putExtra(RealCaptureService.EXTRA_SESSION_ID, sessionId)
            controller.withIntent(startIntent).startCommand(0, 0)

            val transmissionId = "$sessionId-0"
            waitUntil(10_000) { runBlocking { db.transmissionDao().getById(transmissionId) } != null }
            val transmission = runBlocking { db.transmissionDao().getById(transmissionId) }!!

            assertEquals(
                "the operator's own entry, not null (register R-1030's own reproduction)",
                146_520_000L,
                transmission.frequencyHz,
            )
            assertEquals(FrequencyProvenance.MANUAL, transmission.frequencyProvenance)
        } finally {
            controller.destroy()
        }
    }

    @Test
    @Requirement("R-1031")
    public fun `R_1031 a real session records the app's real version, never the smoke-test literal`() {
        val db = OrtDatabase.create(context, inMemory = true)
        val device = AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "Fake USB adapter")
        val fakeIo = FakeAudioIo(deviceSampleRate = 16_000, devices = listOf(device))
        fakeIo.forceRoutedDevice(device)
        val store = InMemoryCaptureConfigurationStore(
            initial = CaptureConfiguration.DEFAULT.copy(selectedInputId = device.id),
        )
        val sessionId = "TEST-R-1031"

        val controller = Robolectric.buildService(RealCaptureService::class.java).create()
        val service = controller.get()
        service.dependencies = RealCaptureService.Dependencies(
            database = { db },
            audioIo = { _ -> fakeIo to device },
            asrEngine = { AsrEngineAvailability.Unavailable("no model in this test") },
            shedSignals = { _, _, _ -> FakeShedSignals() },
            captureConfigurationStore = { store },
        )

        try {
            val startIntent = Intent(context, RealCaptureService::class.java)
                .putExtra(RealCaptureService.EXTRA_SESSION_ID, sessionId)
            controller.withIntent(startIntent).startCommand(0, 0)

            waitUntil(10_000) { runBlocking { db.sessionDao().getById(sessionId) } != null }
            val session = runBlocking { db.sessionDao().getById(sessionId) }!!

            val expectedVersion = realAppVersion(context)
            assertNotEquals("smoke-test", session.appVersion)
            assertEquals(expectedVersion, session.appVersion)
            assertNotNull(session.appVersion)
        } finally {
            controller.destroy()
        }
    }

    /** A [Context] whose package name genuinely does not resolve — Robolectric's own
     * [android.content.pm.PackageManager] throws [android.content.pm.PackageManager.NameNotFoundException]
     * for it exactly as a real device's would, so this exercises [realAppVersion]'s failure branch
     * without mocking anything (constitution II: "prefer a capability probe to exception
     * forensics" — this is a real failure, not a parsed message). */
    private class UnresolvablePackageContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = "org.ort.this.package.does.not.exist"
    }

    @Test
    @Requirement("R-1031")
    public fun `R_1031 realAppVersion reads the real packageManager versionName`() {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals(info.versionName ?: "unknown", realAppVersion(context))
    }

    @Test
    @Requirement("R-1031")
    public fun `R_1031 an unresolvable package reads unknown, never a fabricated version`() {
        assertEquals("unknown", realAppVersion(UnresolvablePackageContext(context)))
    }
}
