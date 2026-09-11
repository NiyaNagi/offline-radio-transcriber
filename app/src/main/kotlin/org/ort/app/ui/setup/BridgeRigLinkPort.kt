package org.ort.app.ui.setup

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.ort.pipeline.rig.DefaultRigTransportFactory
import org.ort.pipeline.rig.PairedRigDevice
import org.ort.pipeline.rig.RigLinkBridge
import org.ort.pipeline.rig.RigLinkProbeState
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
                    sppCapable = toSppCapable(device),
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

    /**
     * **Known, reported gap**: [PairedRigDevice.sppSupport]'s declared type
     * (`org.ort.rig.bluetooth.SppSupport`) is not resolvable from `:app` at all — confirmed by
     * attempting `import org.ort.rig.bluetooth.SppSupport` here and reading the compiler's own
     * "Cannot access class ... Check your module classpath" error before writing this workaround.
     * `pipeline/build.gradle.kts` declares `implementation(project(":rig-bluetooth"))`, which Gradle
     * does not expose to `:pipeline`'s own consumers' compile classpath — only `api` would. That
     * line is outside this package's ownership to change (`:pipeline`, WPC3's own file), so this
     * reads the value through `java.lang.reflect` instead: [Class.getMethod]/[java.lang.reflect.Method.invoke]
     * need no compile-time reference to [PairedRigDevice.sppSupport]'s declared type at all, only
     * to `PairedRigDevice` itself (`:pipeline`'s own type, already resolvable). Once
     * `:rig-bluetooth` is exposed as `api`, this collapses to a plain `when (device.sppSupport) {
     * SppSupport.YES -> true; ... }` and this whole function (and its `getMethod` lookup) can be
     * deleted.
     */
    private fun toSppCapable(device: PairedRigDevice): Boolean? {
        val sppSupportName = runCatching {
            device.javaClass.getMethod("getSppSupport").invoke(device)?.toString()
        }.getOrNull()
        return when (sppSupportName) {
            "YES" -> true
            "NO" -> false
            // "UNKNOWN", or the reflective read itself failing -- both are honestly unknown,
            // never guessed as false (constitution I).
            else -> null
        }
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
    }
}
