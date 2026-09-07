package org.ort.onnx.fake

import org.ort.core.Outcome
import org.ort.onnx.ModelDescriptor
import org.ort.onnx.OnnxSession
import org.ort.onnx.OnnxSessionFactory

/**
 * The behavioural fake for [OnnxSessionFactory] (constitution II): can be told, per asset id,
 * to load cleanly, fail to load (a bad file or signature mismatch), or load and then crash on
 * its first [OnnxSession.run] — which is exactly the "probe run before activation" failure mode
 * F13 and FR-AST-2 require a caller to handle.
 */
public class FakeOnnxSessionFactory : OnnxSessionFactory {

    /** assetId -> behaviour. Absent entries load and run successfully, echoing the input. */
    public val scripts: MutableMap<String, Behaviour> = mutableMapOf()

    public val loadedAssetIds: MutableList<String> = mutableListOf()

    public sealed interface Behaviour {
        public data object LoadFails : Behaviour
        public data object CrashesOnRun : Behaviour
        public data object Healthy : Behaviour
    }

    override fun load(descriptor: ModelDescriptor): Outcome<OnnxSession> {
        loadedAssetIds += descriptor.assetRef.assetId
        return when (scripts[descriptor.assetRef.assetId] ?: Behaviour.Healthy) {
            Behaviour.LoadFails -> Outcome.Err("fake: refused to load ${descriptor.assetRef} (scripted failure)")
            Behaviour.CrashesOnRun -> Outcome.Ok(FakeSession(descriptor, crashesOnRun = true))
            Behaviour.Healthy -> Outcome.Ok(FakeSession(descriptor, crashesOnRun = false))
        }
    }

    private class FakeSession(override val descriptor: ModelDescriptor, private val crashesOnRun: Boolean) :
        OnnxSession {
        override var isClosed: Boolean = false
            private set

        override fun run(input: FloatArray): FloatArray {
            check(!isClosed) { "run() called on a closed session for ${descriptor.assetRef}" }
            if (crashesOnRun) error("fake: scripted native crash running ${descriptor.assetRef}")
            return input
        }

        override fun close() {
            isClosed = true
        }
    }
}
