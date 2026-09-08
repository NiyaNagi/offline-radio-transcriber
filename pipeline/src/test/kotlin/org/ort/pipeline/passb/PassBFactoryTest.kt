package org.ort.pipeline.passb

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.asrapi.fake.FakeAsrEngine
import org.ort.core.AssetRef
import org.ort.data.OrtDatabase
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * audit F-013: [PassBFactory] stamped every fingerprint's `configHash` with the literal
 * `"v0-smoke"` and `provider` with the literal `"cpu"`, regardless of what actually ran
 * (constitution III: "every pass is a pure function of (audio, lexicon snapshot, model set,
 * config) and records the fingerprint of what produced it"; constitution VI: "provider is part
 * of provenance"; FR-REP-1). These pin the fix: two different configurations MUST hash
 * differently, the same configuration MUST hash identically across builds, the fingerprint's
 * `provider` MUST equal what the caller says the engine runs on, and the constant literal MUST
 * never appear again.
 *
 * Robolectric only (JVM) -- [PassBFactory.create] needs a real [OrtDatabase]/`filesDir`; nothing
 * here is device-verified.
 */
@RunWith(RobolectricTestRunner::class)
public class PassBFactoryTest {

    private lateinit var db: OrtDatabase
    private lateinit var filesDir: File
    private val modelRef = AssetRef("fake-asr-model", "1")

    @Before
    public fun openDatabase() {
        db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        filesDir = ApplicationProvider.getApplicationContext<android.content.Context>().filesDir
    }

    private fun engine() = FakeAsrEngine(FakeAsrEngine.Behaviour.Returns(FakeAsrEngine.defaultResult()))

    @Test
    @Requirement("FR-REP-1")
    public fun `FR_REP_1 two different configs yield two different configHashes`() {
        val passLow = PassBFactory.create(filesDir, db, engine(), modelRef, provider = "cpu", confirmThreshold = -1f)
        val passHigh = PassBFactory.create(filesDir, db, engine(), modelRef, provider = "cpu", confirmThreshold = 0.5f)

        assertNotEquals(
            "a different confirmThreshold must produce a different configHash",
            passLow.fingerprint.configHash,
            passHigh.fingerprint.configHash,
        )
    }

    @Test
    @Requirement("FR-REP-1")
    public fun `FR_REP_1 the same inputs yield the same configHash across two builds`() {
        val first = PassBFactory.create(filesDir, db, engine(), modelRef, provider = "cpu")
        val second = PassBFactory.create(filesDir, db, engine(), modelRef, provider = "cpu")

        assertEquals(first.fingerprint.configHash, second.fingerprint.configHash)
    }

    @Test
    @Requirement("FR-REP-1")
    public fun `FR_REP_1 the fingerprint provider equals what the caller reports the engine runs on`() {
        val real = PassBFactory.create(filesDir, db, engine(), modelRef, provider = "cpu")
        assertEquals("cpu", real.fingerprint.provider)

        val unavailable = PassBFactory.create(
            filesDir,
            db,
            UnavailableAsrEngine("no model installed"),
            modelRef,
            provider = "none",
        )
        assertEquals("none", unavailable.fingerprint.provider)
    }

    @Test
    @Requirement("FR-REP-1")
    public fun `FR_REP_1 no fingerprint ever equals the old v0-smoke placeholder`() {
        val pass = PassBFactory.create(filesDir, db, engine(), modelRef, provider = "cpu")

        assertNotEquals("v0-smoke", pass.fingerprint.configHash)
    }
}
