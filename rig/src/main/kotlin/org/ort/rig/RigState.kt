package org.ort.rig

/** How much to trust a [RigState] reading — FR-RIG-1's `sourceConfidence`, deliberately
 * non-optional (constitution I: "an attribution without its confidence state is a bug" applies
 * just as much to rig state as to a callsign attribution). */
public enum class RigStateConfidence {
    /** Parsed from a poll response or an unsolicited push while the transport is healthy. */
    FRESH,

    /** The last known value, carried forward after the transport was lost (FR-RIG-7, FR-RIG-15). */
    STALE,
}

/** A GPS fix in decimal degrees (FR-LEX-22, [RigCapability.POSITION]). */
public data class RigPosition(public val latitude: Double, public val longitude: Double)

/**
 * A snapshot of everything a [RigModule] knows right now (FR-RIG-1).
 *
 * Every field but [timestampNanos], [band] and [sourceConfidence] is optional: a module reports
 * only what its transport and descriptor actually yield — see [RigModule.capabilities]. [band]
 * is `null` for a module with no band concept (see [RigBand]'s doc comment for why it exists at
 * all). [sourceConfidence] is the one field that is never optional.
 */
public data class RigState(
    public val timestampNanos: Long,
    public val band: RigBand? = null,
    public val frequencyHz: Long? = null,
    public val mode: String? = null,
    public val squelchOpen: Boolean? = null,
    public val signalStrength: Int? = null,
    public val memoryChannel: String? = null,
    public val channelName: String? = null,
    public val position: RigPosition? = null,
    public val sourceConfidence: RigStateConfidence,
)
