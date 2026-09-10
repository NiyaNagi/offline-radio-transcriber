package org.ort.rig

/** One declared connection parameter (FR-RIG-1's `configSchema`) — a baud rate, a Bluetooth MAC. */
public data class RigConfigField(
    public val key: String,
    public val label: String,
    public val required: Boolean = true,
    public val defaultValue: String? = null,
)

/** The connection parameters a [RigModule] declares it needs. */
public data class RigConfigSchema(public val fields: List<RigConfigField> = emptyList()) {
    public companion object {
        public val EMPTY: RigConfigSchema = RigConfigSchema()
    }
}
