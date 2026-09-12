package org.ort.app.ui.setup

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * S10b's own, `:app`-local seam onto a Bluetooth rig link (D33/D34, FR-RIG-14, FR-RIG-15). `:app`
 * is not permitted to depend on `:rig-bluetooth`/`:rig-usb` directly (`buildSrc/.../ModuleGraph.kt`'s
 * `allowed[":app"]` lists `:pipeline, :data, :net, :core, :lexicon, :rig, :llm-api` only —
 * constitution VII). [BridgeRigLinkPort] is the real implementation, over WPC3's
 * `org.ort.pipeline.rig.RigLinkBridge` (`:pipeline`, merged `8e40041`) — exactly the seam this
 * interface's own doc comment once said was still missing. [InMemoryRigLinkPort] remains the
 * behavioural fake every screen-level test drives (constitution II: a fake that cannot hang, fail
 * or drop tests nothing about FR-RIG-15).
 */
public data class PairedDevice(
    public val name: String,
    public val address: String,
    /** `true` — advertises SPP, selectable. `false` — headset-class only, listed but not
     * selectable (`Setup-Rig-Bluetooth.dc.html`'s dim row). `null` — the stack reported nothing
     * either way; shown as unknown, still selectable (never guessed as `false` — constitution I). */
    public val sppCapable: Boolean?,
)

/**
 * FR-PLT-2's discipline applied to pairing (mirrors WPC3's own `PairedRigDevicesResult`, S10b's
 * side of the same fact): an empty [devices] list alone is ambiguous — "nothing is paired" and
 * "`BLUETOOTH_CONNECT` is absent, so nothing CAN be listed" must not read the same
 * (constitution I). [permissionGranted] defaults `true` so every existing screen-level test that
 * only cares about the device list is unaffected.
 */
public data class PairedDevicesResult(
    public val devices: List<PairedDevice>,
    public val permissionGranted: Boolean = true,
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

    /**
     * R-1013: mirrors [org.ort.pipeline.rig.RigLinkProbeState.IdentifyTimedOut] — the transport
     * opened ([Open] was reached) but the descriptor's identify sequence never produced a single
     * [org.ort.rig.RigState] within [timeoutMillis]. Different in kind from [Lost]/[Failed]: the
     * link is not dropped and nothing failed to open — it is open and silent, which is why S10b's
     * `Continue` stays disabled here (there is no proof this is even the right rig to proceed with)
     * while `Continue without connecting` remains the honest way forward. [timeoutMillis] is
     * carried so the screen states the bound that actually applied, never a hardcoded number.
     */
    public data class IdentifyTimedOut(val rigId: String, val timeoutMillis: Long) : RigLinkState

    /**
     * R-1014: mirrors [org.ort.pipeline.rig.RigLinkProbeState.VerifyTimedOut] — [Identified] was
     * reached (the rig genuinely spoke) but not every capability the descriptor declares was
     * observed within [timeoutMillis]. **This is partial success, not failure** (constitution I: a
     * weaker device may know less; it must not be more wrong) — S10b's `Continue` enables here,
     * never rendered as [Verified]. [seenCapabilities]/[missingCapabilities] are already resolved to
     * the same operator-facing labels [Verified.commands] carries (via
     * [RigPickerCatalogue.capabilityLabel]), sorted the same way, so the screen can say exactly how
     * many of how many were seen and name the ones that were not.
     */
    public data class VerifyTimedOut(
        val rigId: String,
        val seenCapabilities: List<String>,
        val missingCapabilities: List<String>,
        val timeoutMillis: Long,
    ) : RigLinkState
}

/** S10b's port onto the paired-device list and the connect/identify/verify sequence. */
public interface RigLinkPort {
    public fun pairedDevices(): PairedDevicesResult

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
public class InMemoryRigLinkPort(
    private val devices: List<PairedDevice> = emptyList(),
    private var permissionGranted: Boolean = true,
) : RigLinkPort {

    /** `BLUETOOTH_CONNECT` is absent — [pairedDevices] reports an empty list with
     * [PairedDevicesResult.permissionGranted] `false`, never conflated with "nothing is paired". */
    public fun denyPermission() {
        permissionGranted = false
    }

    private sealed interface Script {
        data object Normal : Script
        data object Hang : Script
        data object HangAfterIdentify : Script
        data class FailToOpen(val reason: String) : Script
        data class DropAfterOpen(val reason: String) : Script
        data object NoPermission : Script
        data class IdentifyTimeout(val timeoutMillis: Long) : Script
        data class VerifyTimeout(
            val seenCapabilities: List<String>,
            val missingCapabilities: List<String>,
            val timeoutMillis: Long,
        ) : Script
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

    /**
     * Tour-builder addition (coordinator-approved, this round): [connect] reaches
     * [RigLinkState.Identified] and never proceeds to [RigLinkState.Verified] — the connect ->
     * identify boundary held indefinitely, distinct from [hang] (which never even reaches
     * [RigLinkState.Open]). Exists because [Script.Normal]'s own step delays are milliseconds, not
     * real Bluetooth latency (`SetupActivityTest`'s own tour-builder test found this directly: under
     * Compose-for-Robolectric's idling, and equally under the real screenshot tour's own settle
     * wait, the whole open -> identify -> verify sequence completes before anything could ever
     * observe the intermediate "identified, not yet verified" checklist state) — this is the one
     * script that holds there on purpose, so a tour step asking to capture exactly that state has a
     * real, stable one to land on.
     */
    public fun hangAfterIdentify(address: String) {
        scripts[address] = Script.HangAfterIdentify
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

    /** R-1013: [connect] opens, then reaches [RigLinkState.IdentifyTimedOut] — a genuine terminal
     * state, unlike [hang]/[hangAfterIdentify] (which never emit again at all): a caller (and a
     * tour step) can observe the checklist actually conclude this way, distinct from a hang that
     * never resolves on its own. */
    public fun identifyTimesOut(address: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS) {
        scripts[address] = Script.IdentifyTimeout(timeoutMillis)
    }

    /** R-1014: [connect] opens, identifies, then reaches [RigLinkState.VerifyTimedOut] naming
     * exactly the [seenCapabilities]/[missingCapabilities] scripted here — never inferred, since a
     * fake that only ever produced the same fixed pair would test nothing about a caller reading
     * either list correctly. */
    public fun verifyTimesOut(
        address: String,
        seenCapabilities: List<String>,
        missingCapabilities: List<String>,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ) {
        scripts[address] = Script.VerifyTimeout(seenCapabilities, missingCapabilities, timeoutMillis)
    }

    /** Overrides the verified command list the default (successful) script reports — otherwise
     * [DEFAULT_VERIFIED_COMMANDS]. */
    public fun verifiesWith(commands: List<String>) {
        verifiedCommands = commands
    }

    override fun pairedDevices(): PairedDevicesResult = if (permissionGranted) {
        PairedDevicesResult(devices, permissionGranted = true)
    } else {
        PairedDevicesResult(emptyList(), permissionGranted = false)
    }

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
            Script.HangAfterIdentify -> {
                emit(RigLinkState.Opening)
                delay(SCRIPT_STEP_DELAY_MILLIS)
                emit(RigLinkState.Open)
                delay(SCRIPT_STEP_DELAY_MILLIS)
                emit(RigLinkState.Identified(expectedRigId))
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
            is Script.IdentifyTimeout -> {
                emit(RigLinkState.Opening)
                delay(SCRIPT_STEP_DELAY_MILLIS)
                emit(RigLinkState.Open)
                delay(SCRIPT_STEP_DELAY_MILLIS)
                emit(RigLinkState.IdentifyTimedOut(expectedRigId, script.timeoutMillis))
            }
            is Script.VerifyTimeout -> {
                emit(RigLinkState.Opening)
                delay(SCRIPT_STEP_DELAY_MILLIS)
                emit(RigLinkState.Open)
                delay(SCRIPT_STEP_DELAY_MILLIS)
                emit(RigLinkState.Identified(expectedRigId))
                delay(SCRIPT_STEP_DELAY_MILLIS)
                emit(
                    RigLinkState.VerifyTimedOut(
                        rigId = expectedRigId,
                        seenCapabilities = script.seenCapabilities,
                        missingCapabilities = script.missingCapabilities,
                        timeoutMillis = script.timeoutMillis,
                    ),
                )
            }
        }
    }

    public companion object {
        public const val SCRIPT_STEP_DELAY_MILLIS: Long = 10L
        public val DEFAULT_VERIFIED_COMMANDS: List<String> = listOf("FQ", "BY", "FO", "AI")

        /** The default [identifyTimesOut]/[verifyTimesOut] bound when a scenario/test does not care
         * about the exact value — a real, plausible-looking descriptor timeout, never asserted on by
         * any test that does not itself pass a specific one. */
        public const val DEFAULT_TIMEOUT_MILLIS: Long = 15_000L
    }
}
