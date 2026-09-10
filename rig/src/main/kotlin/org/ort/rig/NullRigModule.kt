package org.ort.rig

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * FR-RIG-2 — manual frequency entry, first-class, not a fallback. Also the landing place for a
 * descriptor that failed validation (FR-RIG-11): [descriptorError], when non-null, names the
 * reason, so the failure is inspectable rather than silent (constitution I).
 */
public class NullRigModule(public val descriptorError: String? = null) : RigModule {

    override val id: String = ID
    override val displayName: String = DISPLAY_NAME
    override val transports: Set<RigTransportKind> = setOf(RigTransportKind.NONE)
    override val configSchema: RigConfigSchema = RigConfigSchema.EMPTY

    private val states = MutableStateFlow(
        RigState(timestampNanos = 0L, sourceConfidence = RigStateConfidence.STALE),
    )
    private val healths = MutableStateFlow<RigHealth>(RigHealth.Healthy(0L))

    override fun capabilities(transport: RigTransportKind): Set<RigCapability> = emptySet()

    override fun connect(transport: RigTransportKind, params: Map<String, String>): Result<Connection> =
        Result.success(Connection(RigTransportKind.NONE, id))

    override fun disconnect() {
        // Nothing is ever opened; nothing to release.
    }

    override fun observe(): Flow<RigState> = states

    override fun health(): Flow<RigHealth> = healths

    /**
     * Manual frequency entry (FR-RIG-8/FR-RIG-9): always available regardless of module, and —
     * at the layer that records provenance against a transmission — takes precedence with
     * provenance `manual`. This module only carries the value the operator typed.
     */
    public fun setManualFrequencyHz(frequencyHz: Long, timestampNanos: Long) {
        states.value = RigState(
            timestampNanos = timestampNanos,
            frequencyHz = frequencyHz,
            sourceConfidence = RigStateConfidence.FRESH,
        )
    }

    public companion object {
        public const val ID: String = "null"
        public const val DISPLAY_NAME: String = "Manual (no rig connected)"
    }
}
