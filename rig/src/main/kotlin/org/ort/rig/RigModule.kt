package org.ort.rig

import kotlinx.coroutines.flow.Flow

/**
 * The contract every radio adapter implements (FR-RIG-1). Three implementations ship in this
 * package: [NullRigModule] (FR-RIG-2, manual frequency entry, first-class not fallback),
 * [org.ort.rig.descriptor.DescriptorRigModule] (FR-RIG-4, the declarative engine) and the test
 * double [org.ort.rig.fakes.FakeRigModule].
 */
public interface RigModule {
    public val id: String
    public val displayName: String

    /** Every transport this module can be reached over (FR-RIG-14). */
    public val transports: Set<RigTransportKind>

    /** What this module yields on [transport] (FR-RIG-17) — a function, not a fixed set, because
     * capabilities genuinely differ by transport. */
    public fun capabilities(transport: RigTransportKind): Set<RigCapability>

    /** Declared connection parameters, e.g. baud (FR-RIG-1). */
    public val configSchema: RigConfigSchema

    public fun connect(transport: RigTransportKind, params: Map<String, String>): Result<Connection>

    public fun disconnect()

    /** Push or polled, module's choice (FR-RIG-1). */
    public fun observe(): Flow<RigState>

    public fun health(): Flow<RigHealth>
}
