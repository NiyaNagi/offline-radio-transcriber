package org.ort.app.ui.setup

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * S10b's own, `:app`-local seam onto a Bluetooth rig link (D33/D34, FR-RIG-14, FR-RIG-15). Real
 * hardware access lives in `:rig-bluetooth` (`BluetoothSppTransport`/`AndroidBluetoothLink`,
 * `PairedBluetoothDevice`, `SppSupport`, all landed on `main` at `898b8b6`) — but `:app` is not
 * permitted to depend on `:rig-bluetooth` at all (`buildSrc/.../ModuleGraph.kt`'s
 * `allowed[":app"]` lists `:pipeline, :data, :net, :core, :lexicon, :rig, :llm-api` only;
 * `:rig-bluetooth` is not among them, and `ModuleGraph.kt` is outside this package's ownership to
 * change). This interface — and [PairedDevice]/[RigLinkState], deliberately shaped like
 * `:rig-bluetooth`'s own [org.ort.rig.bluetooth.PairedBluetoothDevice]/[org.ort.rig.bluetooth.SppSupport]
 * and `TransportState` so the eventual adapter is a thin, mechanical translation rather than a
 * redesign — is therefore where the boundary has to sit: **the real
 * `BluetoothSppTransport`/`DescriptorRigModule` wiring can only be built once either `:pipeline`
 * exposes it (WPC2's `pipeline/.../rig` package, per `spec/e2e-capture-modes-plan.md`'s WPC2 row) or the
 * lead amends `ModuleGraph.kt` to admit the edge** — this package's own report says so explicitly,
 * so the gap is decided rather than silently worked around. Until then, every S10b behaviour is
 * proven against [InMemoryRigLinkPort], scripted the same way `FakeBluetoothLink`/`FakeRigTransport`
 * script theirs (constitution II: a fake that cannot hang, fail or drop tests nothing about
 * FR-RIG-15).
 */
public data class PairedDevice(
    public val name: String,
    public val address: String,
    /** `true` — advertises SPP, selectable. `false` — headset-class only, listed but not
     * selectable (`Setup-Rig-Bluetooth.dc.html`'s dim row). `null` — the stack reported nothing
     * either way; shown as unknown, still selectable (never guessed as `false` — constitution I). */
    public val sppCapable: Boolean?,
)

/** Every state S10b's open -> identify -> verify checklist can be in (`Setup-Rig-Bluetooth.dc.html`).
 * Mirrors `:rig`'s `TransportState` plus the descriptor-level identify/verify outcomes a real
 * adapter would read off `DescriptorRigModule.observe()` — see this file's own class doc comment. */
public sealed interface RigLinkState {
    public data object Opening : RigLinkState
    public data object Open : RigLinkState
    public data class Identified(val rigId: String) : RigLinkState
    public data class Verified(val commands: List<String>) : RigLinkState

    /** A drop after the link was open (FR-RIG-15) — capture-side, this degrades exactly like a USB
     * disconnection; here, before capture has even started, it is shown, never a blank screen. */
    public data class Lost(val reason: String) : RigLinkState

    /** `BLUETOOTH_CONNECT` is absent — never a `SecurityException` surfacing as a crash. */
    public data object NoPermission : RigLinkState

    public data class Failed(val reason: String) : RigLinkState
}

/** S10b's port onto the paired-device list and the connect/identify/verify sequence. */
public interface RigLinkPort {
    public fun pairedDevices(): List<PairedDevice>

    /** Opens a link to [address], expecting [expectedRigId] (`descriptor.id`) to answer — a real
     * adapter drives this from `DescriptorRigModule`/`BluetoothSppTransport`; see this file's own
     * class doc comment for why that adapter cannot yet live in `:app`. */
    public fun connect(address: String, expectedRigId: String): Flow<RigLinkState>
}

/**
 * The behavioural fake (constitution II) — every failure mode [RigLinkPort.connect] can exhibit is
 * a named, scriptable case, the same shape `FakeBluetoothLink`/`FakeRigTransport` already
 * establish: a device can [hang] (never emits, so `Continue` never enables and no crash occurs
 * either — proves the UI does not assume forward progress), [failToOpen], or [dropAfterOpen]
 * partway through the checklist (FR-RIG-15 — capture-side this is exactly F9/F23's shape; here it
 * is S10b's own banner, never a blank screen).
 */
public class InMemoryRigLinkPort(private val devices: List<PairedDevice> = emptyList()) : RigLinkPort {

    private sealed interface Script {
        data object Normal : Script
        data object Hang : Script
        data class FailToOpen(val reason: String) : Script
        data class DropAfterOpen(val reason: String) : Script
        data object NoPermission : Script
    }

    private val scripts = mutableMapOf<String, Script>()
    private var verifiedCommands: List<String> = DEFAULT_VERIFIED_COMMANDS

    /** The default: opens, identifies as [expectedRigId] (passed to [connect] at call time) and
     * verifies — never scripted explicitly, this is what an address not otherwise configured does. */
    public fun useDefaultBehaviour(address: String) {
        scripts[address] = Script.Normal
    }

    /** [connect] never emits again after [RigLinkState.Opening] — the link seems to hang forever. */
    public fun hang(address: String) {
        scripts[address] = Script.Hang
    }

    /** The link never gets past [RigLinkState.Opening] — reported as [RigLinkState.Failed]. */
    public fun failToOpen(address: String, reason: String = "connection refused") {
        scripts[address] = Script.FailToOpen(reason)
    }

    /** The link opens, then drops before verification completes (FR-RIG-15). */
    public fun dropAfterOpen(address: String, reason: String = "connection dropped") {
        scripts[address] = Script.DropAfterOpen(reason)
    }

    /** `BLUETOOTH_CONNECT` is absent for this address — never a `SecurityException`. */
    public fun noPermission(address: String) {
        scripts[address] = Script.NoPermission
    }

    /** Overrides the verified command list the default (successful) script reports — otherwise
     * [DEFAULT_VERIFIED_COMMANDS]. */
    public fun verifiesWith(commands: List<String>) {
        verifiedCommands = commands
    }

    override fun pairedDevices(): List<PairedDevice> = devices

    override fun connect(address: String, expectedRigId: String): Flow<RigLinkState> = flow {
        when (val script = scripts[address] ?: Script.Normal) {
            Script.Normal -> {
                emit(RigLinkState.Opening)
                delay(SCRIPT_STEP_DELAY_MILLIS)
                emit(RigLinkState.Open)
                delay(SCRIPT_STEP_DELAY_MILLIS)
                emit(RigLinkState.Identified(expectedRigId))
                delay(SCRIPT_STEP_DELAY_MILLIS)
                emit(RigLinkState.Verified(verifiedCommands))
            }
            Script.Hang -> {
                emit(RigLinkState.Opening)
                awaitCancellation()
            }
            is Script.FailToOpen -> {
                emit(RigLinkState.Opening)
                delay(SCRIPT_STEP_DELAY_MILLIS)
                emit(RigLinkState.Failed(script.reason))
            }
            is Script.DropAfterOpen -> {
                emit(RigLinkState.Opening)
                delay(SCRIPT_STEP_DELAY_MILLIS)
                emit(RigLinkState.Open)
                delay(SCRIPT_STEP_DELAY_MILLIS)
                emit(RigLinkState.Lost(script.reason))
            }
            Script.NoPermission -> emit(RigLinkState.NoPermission)
        }
    }

    public companion object {
        public const val SCRIPT_STEP_DELAY_MILLIS: Long = 10L
        public val DEFAULT_VERIFIED_COMMANDS: List<String> = listOf("FQ", "BY", "FO", "AI")
    }
}
