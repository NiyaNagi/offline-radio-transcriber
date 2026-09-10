package org.ort.rig

import kotlinx.coroutines.flow.Flow

/**
 * The wire underneath a [RigModule] — a line-oriented ASCII CAT link (FR-RIG-4). Implemented for
 * real hardware in `:rig-usb` (USB serial, per technical design §11's `SerialTransport`) and a
 * future Bluetooth SPP module; [org.ort.rig.fakes.FakeRigTransport] is the scripted double every
 * test in this package runs against.
 */
public interface RigTransport {
    public fun open()

    public fun close()

    public fun write(line: String)

    /**
     * Waits up to [timeoutMs] for the next line off the wire. Returns `null` on timeout — a
     * well-behaved transport never blocks past it. A transport that cannot honour this contract
     * (an unresponsive port) is exactly what
     * [org.ort.rig.fakes.FakeRigTransport.hangOnNextRead] models, and exactly why
     * [org.ort.rig.descriptor.DescriptorRigModule] applies its own watchdog on top rather than
     * trusting this parameter alone.
     */
    public suspend fun readLine(timeoutMs: Long): String?

    public val state: Flow<TransportState>
}

/** The lifecycle of a [RigTransport] connection. */
public sealed interface TransportState {
    public data object Connecting : TransportState

    public data object Open : TransportState

    public data class Lost(val reason: String) : TransportState

    public data object Closed : TransportState
}

public class RigTransportException(message: String, cause: Throwable? = null) : Exception(message, cause)
