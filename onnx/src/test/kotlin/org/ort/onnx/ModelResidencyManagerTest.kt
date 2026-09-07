package org.ort.onnx

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.core.AssetRef
import org.ort.onnx.fake.FakeOnnxSessionFactory
import org.ort.testing.Requirement

class ModelResidencyManagerTest {

    private fun descriptor(
        id: String,
        residency: ResidencyClass,
        bytes: Long = 100L,
        family: ModelFamily = ModelFamily.ASR_ENCODER_DECODER,
    ) = ModelDescriptor(
        assetRef = AssetRef(id, "1"),
        family = family,
        sizeClass = ModelSizeClass.SMALL,
        quantization = "int8",
        providerBinaries = setOf("cpu"),
        isFineTuned = false,
        licence = "Apache-2.0",
        memoryFootprintBytes = bytes,
        residencyClass = residency,
    )

    @Test
    @Requirement("technical-design-4.3")
    fun `PINNED model is never evicted under pressure`() {
        val factory = FakeOnnxSessionFactory()
        val mgr = ModelResidencyManager(factory, budgetBytes = 1_000)
        val vad = descriptor("silero-vad", ResidencyClass.PINNED)
        val session = mgr.loadPinned(vad).getOrNull()!!

        mgr.evictUnderPressure(targetFreeBytes = 1_000)

        assertTrue(!session.isClosed, "a PINNED session must survive eviction pressure")
        assertEquals(1, factory.loadedAssetIds.count { it == "silero-vad" })
    }

    @Test
    @Requirement("technical-design-4.3")
    fun `HOT models evict oldest-first but a floor of one stays resident`() {
        val factory = FakeOnnxSessionFactory()
        val mgr = ModelResidencyManager(factory, budgetBytes = 10_000)
        val a = descriptor("pass-b-small", ResidencyClass.HOT, bytes = 100)
        val b = descriptor("pass-c-spotter", ResidencyClass.HOT, bytes = 100)

        val sessionA = mgr.acquireHot(a).getOrNull()!!
        mgr.acquireHot(b).getOrNull()!!

        mgr.evictUnderPressure(targetFreeBytes = 10_000) // would evict everything if there were no floor

        assertTrue(sessionA.isClosed, "the least-recently-used HOT model is evicted under pressure")
        assertEquals(1, mgr.history.count { it is ResidencyEvent.Evicted })
    }

    @Test
    @Requirement("technical-design-4.3")
    fun `loading a HOT model beyond the tier budget evicts an existing HOT model to make room`() {
        val factory = FakeOnnxSessionFactory()
        val mgr = ModelResidencyManager(factory, budgetBytes = 150)
        val a = descriptor("pass-b", ResidencyClass.HOT, bytes = 100)
        val b = descriptor("pass-c", ResidencyClass.HOT, bytes = 100)

        val sessionA = mgr.acquireHot(a).getOrNull()!!
        val result = mgr.acquireHot(b)

        assertTrue(result.getOrNull() != null, "b should load after evicting a to fit the budget")
        assertTrue(sessionA.isClosed)
        assertTrue(mgr.residentBytes() <= 150)
    }

    @Test
    @Requirement("technical-design-4.3")
    fun `a load that would exceed budget with no evictable HOT model is refused, not silently oversized`() {
        val factory = FakeOnnxSessionFactory()
        val mgr = ModelResidencyManager(factory, budgetBytes = 50)
        val tooBig = descriptor("pass-b-large", ResidencyClass.HOT, bytes = 100)

        val result = mgr.acquireHot(tooBig)

        assertTrue(result is org.ort.core.Outcome.Err)
        assertTrue(mgr.history.any { it is ResidencyEvent.BudgetExceeded })
    }

    @Test
    @Requirement("technical-design-4.3")
    fun `COLD model is released immediately after use regardless of block outcome`() {
        val factory = FakeOnnxSessionFactory()
        val mgr = ModelResidencyManager(factory, budgetBytes = 1_000)
        val embedder = descriptor("speaker-embedder", ResidencyClass.COLD, family = ModelFamily.SPEAKER_EMBEDDER)

        var sessionSeenOpen = false
        var capturedSession: OnnxSession? = null
        mgr.withCold(embedder) { session ->
            sessionSeenOpen = !session.isClosed
            capturedSession = session
            "ok"
        }

        assertTrue(sessionSeenOpen)
        assertTrue(capturedSession!!.isClosed, "a COLD session must be closed the instant the block returns")
        assertEquals(0L, mgr.residentBytes(), "COLD models never count toward resident budget after use")
    }

    @Test
    @Requirement("technical-design-4.3")
    fun `a COLD model is released even when the block throws`() {
        val factory = FakeOnnxSessionFactory()
        val mgr = ModelResidencyManager(factory, budgetBytes = 1_000)
        val embedder = descriptor("speaker-embedder", ResidencyClass.COLD, family = ModelFamily.SPEAKER_EMBEDDER)
        var captured: OnnxSession? = null

        val result = mgr.withCold(embedder) { session ->
            captured = session
            error("boom")
        }

        assertTrue(result is org.ort.core.Outcome.Err)
        assertTrue(captured!!.isClosed)
    }

    @Test
    fun `a model that crashes its probe run is not activated (F13, FR-AST-2)`() {
        val factory = FakeOnnxSessionFactory()
        val crashy = descriptor("sideloaded-experimental", ResidencyClass.HOT)
        factory.scripts[crashy.assetRef.assetId] = FakeOnnxSessionFactory.Behaviour.CrashesOnRun
        val loaded = factory.load(crashy).getOrNull()!!

        val probe = factory.probeRun(loaded, fixture = FloatArray(16))

        assertTrue(probe is org.ort.core.Outcome.Err, "a crash during the probe run must not be swallowed as success")
    }

    @Test
    fun `a model that fails to load surfaces Err rather than throwing`() {
        val factory = FakeOnnxSessionFactory()
        val bad = descriptor("corrupt-model", ResidencyClass.HOT)
        factory.scripts[bad.assetRef.assetId] = FakeOnnxSessionFactory.Behaviour.LoadFails

        val result = factory.load(bad)

        assertTrue(result is org.ort.core.Outcome.Err)
    }
}
