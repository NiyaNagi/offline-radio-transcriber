package org.ort.pipeline.rig

import org.ort.rig.RigTransport
import org.ort.rig.RigTransportKind

/**
 * WPC2's seam for turning a chosen [RigTransportKind] into a live [RigTransport] (FR-RIG-13/14).
 * [org.ort.pipeline.rig.RigSupervisor] depends only on this interface — never on `:rig-usb` or
 * `:rig-bluetooth` directly — so swapping in the real transports (WPB) touches exactly one class,
 * [DefaultRigTransportFactory], and nothing that composes it.
 *
 * A `fun interface` rather than a plain typealias so a test can write `RigTransportFactory { _, _
 * -> fakeTransport }` directly, matching [org.ort.rig.descriptor.DescriptorRigModule]'s own
 * constructor parameter shape (`(RigTransportKind, Map<String, String>) -> RigTransport`) via
 * [RigTransportFactory::create] as a method reference.
 */
public fun interface RigTransportFactory {
    public fun create(kind: RigTransportKind, params: Map<String, String>): RigTransport
}

/**
 * Thrown by [DefaultRigTransportFactory] for a [RigTransportKind] it has no real transport
 * registered for — never returns a transport that would silently pretend to be connected
 * (constitution I).
 */
public class UnsupportedRigTransportException(kind: RigTransportKind) :
    Exception("no real RigTransport is registered for $kind")
