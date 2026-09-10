package org.ort.rig.descriptor

import org.ort.rig.RigCapability
import java.util.regex.PatternSyntaxException

/** The wire spellings [TransportSpec.kind] is allowed to use. */
internal val KNOWN_TRANSPORT_KINDS: Set<String> = setOf("usb_serial", "bluetooth_spp", "ble", "network", "none")

/** field name (as used in [CommandSpec.map] / [PatternSpec.map]) -> the capability it derives. */
internal val FIELD_TO_CAPABILITY: Map<String, RigCapability> = mapOf(
    "frequencyHz" to RigCapability.FREQUENCY,
    "mode" to RigCapability.MODE,
    "squelchOpen" to RigCapability.SQUELCH_STATE,
    "signalStrength" to RigCapability.SIGNAL_STRENGTH,
    "memoryChannel" to RigCapability.MEMORY_CHANNEL,
    "channelName" to RigCapability.CHANNEL_NAME,
    "band" to RigCapability.SUB_BAND,
    "position" to RigCapability.POSITION,
    "time" to RigCapability.TIME,
)

/**
 * A nested-unbounded-quantifier heuristic (technical design §11): a group that already contains
 * an unbounded quantifier, itself repeated unboundedly (`(a+)+`, `(.*)*`, ...), is the textbook
 * shape behind catastrophic backtracking. This is a static heuristic, not a proof of safety —
 * documented here rather than overclaimed — but it catches exactly that shape.
 */
internal val CATASTROPHIC_BACKTRACKING_SHAPE: Regex = Regex("""\([^()]*[*+][^()]*\)[*+]""")

/** FR-RIG-11: validates a descriptor on load, with a clear error per failure. */
public object DescriptorValidator {

    public fun validate(descriptor: RigDescriptor): List<DescriptorError> {
        val errors = mutableListOf<DescriptorError>()

        if (descriptor.schemaVersion > SUPPORTED_DESCRIPTOR_SCHEMA_VERSION) {
            // A newer schema may mean anything below means something this build cannot know —
            // fail fast and apply nothing else (FR-AST-7).
            return listOf(
                DescriptorError.UnsupportedSchemaVersion(descriptor.schemaVersion, SUPPORTED_DESCRIPTOR_SCHEMA_VERSION),
            )
        }

        if (descriptor.transports.isEmpty()) {
            errors += DescriptorError.NoTransports(descriptor.id)
        }

        for (transport in descriptor.transports) {
            if (transport.kind.lowercase() !in KNOWN_TRANSPORT_KINDS) {
                errors += DescriptorError.UnknownTransportKind(transport.kind)
            }
        }

        val allCommands = descriptor.poll?.commands.orEmpty() + descriptor.poll?.perBand.orEmpty()
        val allPatterns = descriptor.unsolicited?.patterns.orEmpty()

        for (cmd in allCommands) {
            errors += validateExpect(cmd.send, cmd.expect)
        }
        for (pattern in allPatterns) {
            errors += validateExpect("(unsolicited)", pattern.expect)
        }

        val derivableFields = allCommands.flatMap { it.map.keys }.toSet() + allPatterns.flatMap { it.map.keys }.toSet()
        for (transport in descriptor.transports) {
            for (rawCapability in transport.capabilities) {
                val capability = runCatching { RigCapability.valueOf(rawCapability) }.getOrNull()
                val requiredField = capability?.let { c ->
                    FIELD_TO_CAPABILITY.entries.firstOrNull { it.value == c }?.key
                }
                if (capability == null || requiredField == null || requiredField !in derivableFields) {
                    errors += DescriptorError.CapabilityNotDerivable(transport.kind, rawCapability)
                }
            }
        }

        return errors
    }

    private fun validateExpect(commandLabel: String, expect: String): List<DescriptorError> {
        if (expect.isBlank()) return listOf(DescriptorError.MissingExpectPattern(commandLabel))
        if (CATASTROPHIC_BACKTRACKING_SHAPE.containsMatchIn(expect)) {
            return listOf(DescriptorError.RegexTooComplex(expect))
        }
        return try {
            Regex(expect)
            emptyList()
        } catch (e: PatternSyntaxException) {
            listOf(DescriptorError.InvalidRegex(expect, e.message ?: "invalid pattern"))
        }
    }
}
