package org.ort.rig.fakes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.ort.rig.Connection
import org.ort.rig.RigCapability
import org.ort.rig.RigConfigSchema
import org.ort.rig.RigConnectException
import org.ort.rig.RigHealth
import org.ort.rig.RigModule
import org.ort.rig.RigState
import org.ort.rig.RigTransportKind

/**
 * A scripted [RigModule] (constitution II). Lets a consumer of `:rig` (`:pipeline`, `:app`) test
 * against a rig with no descriptor and no transport at all: [connect] succeeds or fails on
 * command, and [emit] / [emitHealth] push whatever [RigState] / [RigHealth] a test wants observed.
 */
public class FakeRigModule(
    override val id: String = "fake-rig",
    override val displayName: String = "Fake Rig",
    override val transports: Set<RigTransportKind> = setOf(RigTransportKind.NONE),
    override val configSchema: RigConfigSchema = RigConfigSchema.EMPTY,
    private val capabilitiesByTransport: Map<RigTransportKind, Set<RigCapability>> = emptyMap(),
) : RigModule {

    private val states = MutableSharedFlow<RigState>(replay = 1, extraBufferCapacity = 64)
    private val healths = MutableSharedFlow<RigHealth>(replay = 1, extraBufferCapacity = 64)

    private var connectResult: Result<Connection> = Result.success(Connection(RigTransportKind.NONE, id))

    public var connected: Boolean = false
        private set

    public var lastConnectParams: Map<String, String>? = null
        private set

    override fun capabilities(transport: RigTransportKind): Set<RigCapability> =
        capabilitiesByTransport[transport] ?: emptySet()

    /** The next [connect] call fails with [reason] instead of succeeding. */
    public fun scriptConnectFailure(reason: String) {
        connectResult = Result.failure(RigConnectException(reason))
    }

    override fun connect(transport: RigTransportKind, params: Map<String, String>): Result<Connection> {
        lastConnectParams = params
        return connectResult.also { if (it.isSuccess) connected = true }
    }

    override fun disconnect() {
        connected = false
    }

    override fun observe(): Flow<RigState> = states

    override fun health(): Flow<RigHealth> = healths

    /** Publishes [state] to every [observe] collector. */
    public suspend fun emit(state: RigState) {
        states.emit(state)
    }

    /** Publishes [health] to every [health] collector. */
    public suspend fun emitHealth(health: RigHealth) {
        healths.emit(health)
    }
}
