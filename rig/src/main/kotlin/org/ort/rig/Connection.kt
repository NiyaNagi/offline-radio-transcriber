package org.ort.rig

/**
 * A live connection handle returned by [RigModule.connect]. Opaque to the caller by design —
 * state and health are read from the module itself via [RigModule.observe] and
 * [RigModule.health], never from this handle, so there is exactly one place either can be read.
 */
public data class Connection(public val transport: RigTransportKind, public val moduleId: String)

/** Wrapped in the [Result] from [RigModule.connect] when a connection attempt fails. */
public class RigConnectException(message: String, cause: Throwable? = null) : Exception(message, cause)
