package org.ort.rig

/** Why a [RigModule] is reporting less than full health. */
public enum class RigHealthIssue {
    /** A read did not complete inside the module's own timeout — FR-RIG-1's `health()` exists
     * precisely so a hung transport is observable rather than silently stalling capture. */
    TIMEOUT,

    /** A line arrived but matched no known pattern — the descriptor could not parse it. */
    UNPARSEABLE_RESPONSE,

    /** The transport reported [TransportState.Lost] (FR-RIG-7 / FR-RIG-15). */
    TRANSPORT_LOST,

    /** The descriptor itself failed validation (FR-RIG-11). */
    DESCRIPTOR_INVALID,
}

/** A point-in-time health report — FR-RIG-1's `health(): Flow<RigHealth>`. */
public sealed interface RigHealth {
    public val timestampNanos: Long

    public data class Healthy(override val timestampNanos: Long) : RigHealth

    public data class Degraded(
        override val timestampNanos: Long,
        public val issue: RigHealthIssue,
        public val detail: String? = null,
    ) : RigHealth
}
