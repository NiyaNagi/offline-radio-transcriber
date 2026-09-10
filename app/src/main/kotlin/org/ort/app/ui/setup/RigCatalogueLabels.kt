package org.ort.app.ui.setup

import org.ort.core.capture.RigTransportKind as PresetRigTransportKind
import org.ort.rig.NullRigModule
import org.ort.rig.RigCapability
import org.ort.rig.RigTransportKind
import org.ort.rig.catalogue.RigCatalogue
import org.ort.rig.catalogue.RigCatalogueEntry
import org.ort.rig.descriptor.BundledDescriptors

/**
 * FR-RIG-16/17/18 — turns `:rig`'s [RigCatalogue] into what S09/S09b actually render: real,
 * generated labels (never hardcoded per-rig board copy — a descriptor added tomorrow gets the
 * same treatment with no code change here) plus the operator-facing display ordering the board
 * uses (`Setup-Rig.dc.html`: named rigs, then Generic ASCII CAT, then "No radio" last, immediately
 * before `Import it`) — the *opposite* of [RigCatalogue.entries]' own internal ordering (null +
 * generic first, so a descriptor collision can never displace them — see that class's own doc
 * comment), which is an implementation convenience, not the picker's display order.
 */
public object RigPickerCatalogue {

    /** `BundledDescriptors.genericAsciiCat().id`, cached — loading and validating the bundled
     * resource is cheap but not free, and [pickerOrder] compares against this id on every entry. */
    private val genericAsciiCatId: String by lazy { BundledDescriptors.genericAsciiCat().id }

    /** The bundled TH-D75A plus whatever [imported] descriptors this session has accepted via
     * S09's "Import it" (kept in [SetupActivity]'s own in-memory state — descriptor persistence
     * across process death is `:rig`'s own asset-lifecycle concern, FR-AST-7, not this screen's). */
    public fun build(imported: Set<org.ort.rig.descriptor.RigDescriptor> = emptySet()): RigCatalogue =
        RigCatalogue.fromDescriptors(setOf(BundledDescriptors.kenwoodThD75a()) + imported)

    /** AC-134/AC-135: named rigs first (already displayName-sorted by [RigCatalogue] itself),
     * Generic ASCII CAT second-to-last, the null module ("No radio") last — reachable without
     * scrolling past the radio list, exactly as the board draws it. */
    public fun RigCatalogue.pickerOrder(): List<RigCatalogueEntry> = entries().sortedBy { entry ->
        when (entry.id) {
            NullRigModule.ID -> 2
            genericAsciiCatId -> 1
            else -> 0
        }
    }

    /** One generated capability/transport sub-line per FR-RIG-17 — e.g. "USB serial · Bluetooth
     * SPP · frequency, squelch, per band · verified" for the TH-D75A, or a plain manual-entry line
     * for the null module. Never the board's own hand-written prose for a specific rig — this is
     * what makes a newly-installed descriptor's row honest without a designer having drawn it. */
    public fun subtitleFor(entry: RigCatalogueEntry): String {
        if (entry.id == NullRigModule.ID) return "Scanner, a handheld near the phone, or a rig with no data port"
        val transports = entry.transportCapabilities.keys
            .filter { it != RigTransportKind.NONE }
            .sortedBy { it.ordinal }
            .joinToString(" · ") { transportLabel(it) }
        val capabilities = entry.transportCapabilities.values
            .flatten()
            .toSet()
            .sortedBy { it.ordinal }
            .joinToString(", ") { capabilityLabel(it) }
        val verified = if (entry.verified) " · verified" else ""
        return listOf(transports, capabilities).filter { it.isNotBlank() }.joinToString(" · ") + verified
    }

    /** S09b's own per-transport capability bullets for one [RigCatalogueEntry] and one transport —
     * empty when that transport is not declared at all. */
    public fun capabilitiesFor(entry: RigCatalogueEntry, transport: RigTransportKind): List<String> =
        entry.transportCapabilities[transport].orEmpty().sortedBy { it.ordinal }.map(::capabilityLabel)

    public fun transportLabel(kind: RigTransportKind): String = when (kind) {
        RigTransportKind.USB_SERIAL -> "USB serial"
        RigTransportKind.BLUETOOTH_SPP -> "Bluetooth SPP"
        RigTransportKind.BLE -> "Bluetooth LE"
        RigTransportKind.NETWORK -> "network"
        RigTransportKind.NONE -> "manual"
    }

    /** The `:core` preset transport kind this catalogue transport corresponds to, or `null` for a
     * transport S09b does not offer a "Connect over …" button for at all (only USB serial and
     * Bluetooth SPP are reachable from setup today — the v1 catalogue never declares BLE/NETWORK,
     * per `spec/functional-spec.md` §9's own v1 scope). */
    public fun toPresetKind(kind: RigTransportKind): PresetRigTransportKind? = when (kind) {
        RigTransportKind.USB_SERIAL -> PresetRigTransportKind.USB_SERIAL
        RigTransportKind.BLUETOOTH_SPP -> PresetRigTransportKind.BLUETOOTH_SPP
        else -> null
    }

    /** The inverse of [toPresetKind] — `:rig`'s own transport kind for a `:core` preset value, so
     * [SetupActivity.onChooseRig] can preselect S09b's radio button from
     * [org.ort.core.capture.CaptureModePresets.presetsFor]'s answer. */
    public fun fromPresetKind(kind: PresetRigTransportKind): RigTransportKind = when (kind) {
        PresetRigTransportKind.USB_SERIAL -> RigTransportKind.USB_SERIAL
        PresetRigTransportKind.BLUETOOTH_SPP -> RigTransportKind.BLUETOOTH_SPP
    }

    public fun capabilityLabel(capability: RigCapability): String = when (capability) {
        RigCapability.FREQUENCY -> "frequency"
        RigCapability.MODE -> "mode"
        RigCapability.SQUELCH_STATE -> "squelch"
        RigCapability.SIGNAL_STRENGTH -> "signal strength"
        RigCapability.MEMORY_CHANNEL -> "memory channel"
        RigCapability.CHANNEL_NAME -> "channel name"
        RigCapability.SUB_BAND -> "per band"
        RigCapability.TIME -> "time"
        RigCapability.POSITION -> "position"
    }
}
