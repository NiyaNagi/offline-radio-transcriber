package org.ort.rig.descriptor

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.ort.rig.NullRigModule
import org.ort.rig.RigModule
import org.ort.rig.RigTransport
import org.ort.rig.RigTransportKind

private val descriptorJson = Json { ignoreUnknownKeys = true }

/** The outcome of loading a descriptor: either a validated [RigDescriptor], or the reasons it
 * was rejected — [DescriptorLoader] never throws to the caller (FR-RIG-11). */
public sealed interface DescriptorLoadResult {
    public data class Loaded(val descriptor: RigDescriptor) : DescriptorLoadResult
    public data class Rejected(val errors: List<DescriptorError>) : DescriptorLoadResult
}

public object DescriptorLoader {

    /** Parses and validates [text]. Never throws — malformed JSON or a failing descriptor comes
     * back as [DescriptorLoadResult.Rejected], not an exception. */
    public fun load(text: String): DescriptorLoadResult {
        val descriptor = try {
            descriptorJson.decodeFromString(RigDescriptor.serializer(), text)
        } catch (e: SerializationException) {
            return DescriptorLoadResult.Rejected(listOf(DescriptorError.MalformedJson(e.message ?: "unreadable")))
        } catch (e: IllegalArgumentException) {
            return DescriptorLoadResult.Rejected(listOf(DescriptorError.MalformedJson(e.message ?: "unreadable")))
        }
        val errors = DescriptorValidator.validate(descriptor)
        return if (errors.isEmpty()) DescriptorLoadResult.Loaded(descriptor) else DescriptorLoadResult.Rejected(errors)
    }

    /**
     * Loads [text] and returns a working [RigModule]: a [DescriptorRigModule] when it validates,
     * or a [NullRigModule] with the error attached when it does not (FR-RIG-11) — a failing
     * descriptor never blocks capture and never throws to the caller.
     */
    public fun loadModule(
        text: String,
        transportFactory: (RigTransportKind, TransportSpec?, Map<String, String>) -> RigTransport,
    ): RigModule = when (val result = load(text)) {
        is DescriptorLoadResult.Loaded -> DescriptorRigModule(result.descriptor, transportFactory)
        is DescriptorLoadResult.Rejected -> NullRigModule(
            descriptorError = result.errors.joinToString("; ") { it.message },
        )
    }
}
