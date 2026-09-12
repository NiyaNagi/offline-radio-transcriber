package org.ort.app.ui.setup

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.ort.pipeline.rig.DefaultRigTransportFactory
import org.ort.pipeline.rig.RigLinkBridge
import org.ort.pipeline.rig.RigLinkProbeState
import org.ort.pipeline.rig.RigLinkSppSupport
import org.ort.rig.RigTransportKind

/**
 * The real [RigLinkPort], over WPC3's [RigLinkBridge] (`:pipeline`, merged `8e40041`) — this is
 * the adapter [RigLinkPort]'s own doc comment named as still missing: `:app` never depends on
 * `:rig-bluetooth` directly (`ModuleGraph.kt`'s `allowed[":app"]` still does not list it,
 * constitution VII), only on `:pipeline`, which the bridge is built for exactly this purpose.
 * [SetupActivity] constructs this over [org.ort.pipeline.rig.DefaultRigLinkBridge]; every other
 * screen-level test keeps using [InMemoryRigLinkPort] — this class's own tests
 * ([BridgeRigLinkPortTest]) drive it over [org.ort.pipeline.rig.FakeRigLinkBridge] instead.
 */
public class BridgeRigLinkPort(private val bridge: RigLinkBridge) : RigLinkPort {

    override fun pairedDevices(): PairedDevicesResult {
        val result = bridge.pairedDevices()
        return PairedDevicesResult(
            devices = result.devices.map { device ->
                PairedDevice(
                    name = device.name ?: device.address,
                    address = device.address,
                    sppCapable = toSppCapable(device.sppSupport),
                )
            },
            permissionGranted = result.permissionGranted,
        )
    }

    /** S10b is Bluetooth-only (FR-RIG-14) — always probes [RigTransportKind.BLUETOOTH_SPP], the
     * [address] as [DefaultRigTransportFactory.ParamKeys.BLUETOOTH_ADDRESS]. The line terminator
     * is not supplied here: both bundled descriptors now declare their own
     * [org.ort.rig.descriptor.TransportSpec.lineTerminator] (WPC3), which
     * [DefaultRigTransportFactory] reads first — a descriptor missing one fails that connect
     * loudly rather than guessing (constitution I), exactly as the factory's own kdoc specifies. */
    override fun connect(address: String, expectedRigId: String): Flow<RigLinkState> = bridge.probe(
        rigId = expectedRigId,
        transportKind = RigTransportKind.BLUETOOTH_SPP,
        params = mapOf(DefaultRigTransportFactory.ParamKeys.BLUETOOTH_ADDRESS to address),
    ).map(::toRigLinkState)

    /** [RigLinkSppSupport] is [RigLinkBridge]'s own type, not `:rig-bluetooth`'s — introduced so
     * this exact mapping could be a plain, resolvable `when` (WPC3 follow-up, `d6b65fab`; see
     * [RigLinkSppSupport]'s own kdoc for the leaked-type gap it replaced). `UNKNOWN` is honestly
     * unknown, never guessed as `false` (constitution I). */
    private fun toSppCapable(sppSupport: RigLinkSppSupport): Boolean? = when (sppSupport) {
        RigLinkSppSupport.YES -> true
        RigLinkSppSupport.NO -> false
        RigLinkSppSupport.UNKNOWN -> null
    }

    private fun toRigLinkState(probeState: RigLinkProbeState): RigLinkState = when (probeState) {
        RigLinkProbeState.Opening -> RigLinkState.Opening
        RigLinkProbeState.Open -> RigLinkState.Open
        is RigLinkProbeState.Identified -> RigLinkState.Identified(probeState.rigId)
        is RigLinkProbeState.Verified -> RigLinkState.Verified(
            probeState.capabilities.sortedBy { it.ordinal }.map(RigPickerCatalogue::capabilityLabel),
        )
        is RigLinkProbeState.Lost -> RigLinkState.Lost(probeState.reason)
        RigLinkProbeState.NoPermission -> RigLinkState.NoPermission
        is RigLinkProbeState.Failed -> RigLinkState.Failed(probeState.reason)
        is RigLinkProbeState.IdentifyTimedOut -> RigLinkState.IdentifyTimedOut(
            probeState.rigId,
            probeState.timeoutMillis,
        )
        is RigLinkProbeState.VerifyTimedOut -> RigLinkState.VerifyTimedOut(
            rigId = probeState.rigId,
            seenCapabilities = probeState.seenCapabilities
                .sortedBy { it.ordinal }
                .map(RigPickerCatalogue::capabilityLabel),
            missingCapabilities = (probeState.declaredCapabilities - probeState.seenCapabilities)
                .sortedBy { it.ordinal }
                .map(RigPickerCatalogue::capabilityLabel),
            timeoutMillis = probeState.timeoutMillis,
        )
    }
}
