package org.ort.rig.descriptor

/**
 * Why a descriptor was rejected (FR-RIG-11). Every case names exactly one cause so
 * [DescriptorLoader]'s fallback is precise, never a generic "invalid descriptor" — a failing
 * descriptor falls back to the null module **with the error attached** (constitution I).
 */
public sealed class DescriptorError(public val message: String) {
    public data class UnknownTransportKind(val kind: String) : DescriptorError("unknown transport kind '$kind'")

    public data class MissingExpectPattern(val command: String) :
        DescriptorError("command '$command' has no expect pattern")

    public data class InvalidRegex(val pattern: String, val reason: String) :
        DescriptorError("expect pattern '$pattern' is not a valid regex: $reason")

    /** Technical design §11: a descriptor is untrusted data driving a regex engine; a pattern
     * shaped for catastrophic backtracking would hang the rig thread indefinitely. */
    public data class RegexTooComplex(val pattern: String) :
        DescriptorError("expect pattern '$pattern' exceeds the complexity bound (technical design §11)")

    public data class CapabilityNotDerivable(val transportKind: String, val capability: String) :
        DescriptorError("transport '$transportKind' declares capability '$capability' but no command yields it")

    public data class UnsupportedSchemaVersion(val found: Int, val supported: Int) :
        DescriptorError("schema version $found is newer than this build supports ($supported)")

    public data class NoTransports(val descriptorId: String) :
        DescriptorError("descriptor '$descriptorId' declares no transports")

    public data class MalformedJson(val reason: String) : DescriptorError("malformed descriptor JSON: $reason")

    /** FR-RIG-3: a USB transport declaring exactly one of `usbVendorId`/`usbProductId` — a
     * hardware identity is both-or-neither, never half-guessed (constitution I). */
    public data class IncompleteUsbIdentity(val transportKind: String) :
        DescriptorError("transport '$transportKind' declares only one of usbVendorId/usbProductId — both or neither")

    /** FR-RIG-3: a transport declaring `lineTerminator` as an explicit empty string — a missing
     * terminator is `null` (absent), never an empty one that would silently never terminate a line. */
    public data class EmptyLineTerminator(val transportKind: String) :
        DescriptorError("transport '$transportKind' declares an empty lineTerminator")

    override fun toString(): String = message
}
