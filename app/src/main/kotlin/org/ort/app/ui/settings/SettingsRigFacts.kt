package org.ort.app.ui.settings

import android.content.Context
import org.ort.app.ui.data.realCaptureConfigurationStore
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.rig.CaptureConfiguration
import org.ort.pipeline.rig.DefaultRigTransportFactory
import java.util.Locale
import org.ort.rig.RigTransportKind as RigLinkTransportKind

/**
 * CF06 ("Rig link") and CF11's own Rig-link row (`Settings-Rig.dc.html`, `Settings-Mode.dc.html`) —
 * split out of [SettingsPolling] purely to keep that object under detekt's `LargeClass` threshold
 * (the same reason [ModelsScreen]'s own `groupedAssetSubLine` was already extracted as a top-level
 * function this round) — every doc comment, requirement citation and test this block already had
 * moves with it unchanged; [SettingsPolling.rig] stays the one public entry point every existing
 * caller/test already calls, now a one-line delegate to [rig] here.
 */
internal object SettingsRigFacts {

    /** Register R-950 (Reviewer B3, run 4a, spec): CF11's live Rig-link row read the raw descriptor
     * name with no transport or address clause ("Kenwood TH-D75A · connected") — the manufacturer
     * prefix undropped (R-845's own rule, unapplied here) and the transport/address axis (CF06's
     * own Link row already carries both) simply missing. Same shared helper, same
     * [linkAddressLabel] this file's own [rig] function already reads from the session's current
     * configuration — "name · transport · address when known · state", the board's own shape. */
    fun rigLinkSubLine(
        descriptor: String,
        transportKind: RigLinkTransportKind?,
        context: Context,
        stateWord: String,
    ): String = listOfNotNull(
        stripManufacturerPrefix(descriptor),
        transportLabelFor(transportKind),
        linkAddressLabel(context),
        stateWord,
    ).joinToString(" · ")

    /**
     * CF06 (amended 2026-09-10, FR-RIG-14/15): [transportLabel] is real from
     * `RigStatus.State.Connected.transportKind` (WPC2, merged `e464820`); [linkAddressLabel] is
     * real from [org.ort.pipeline.rig.CaptureConfigurationStore.current]'s own `rigParams` (the same
     * session-scoped configuration `RigSupervisor.connect` used to open this exact link) — read via
     * [context], the session's *current* configuration (not [org.ort.app.ui.data.CaptureModeFacts],
     * which this function has no need of: it never asks what mode this is, only what the rig link
     * itself is doing). `null` when the connected/stale descriptor's own params carry neither key
     * (an imported/generic descriptor, or nothing ever connected).
     */
    fun rig(context: Context): SettingsRigViewState = when (val state = RigStatus.state) {
        RigStatus.State.Absent -> SettingsRigViewState(
            descriptorLabel = "No radio configured",
            connected = false,
            staleSinceLabel = null,
            bands = emptyList(),
        )

        is RigStatus.State.Connected -> {
            val descriptor = matchedDescriptor(state.descriptorId)
            val transportKind = state.transportKind
            SettingsRigViewState(
                descriptorLabel = stripManufacturerPrefix(state.descriptor),
                connected = true,
                staleSinceLabel = null,
                bands = state.bands.map { it.toViewState(stale = false) },
                transportLabel = transportLabelFor(transportKind),
                linkAddressLabel = linkAddressLabel(context),
                rigModuleLabel = rigModuleLabel(state.descriptorId, descriptor, transportKind),
                otherTransportLabel = otherTransportLabel(descriptor, transportKind),
                autoInformation = autoInformationFor(descriptor),
                pollingClause = pollingClauseFor(descriptor),
            )
        }

        is RigStatus.State.Stale -> {
            val descriptor = matchedDescriptor(state.lastKnown.descriptorId)
            val transportKind = state.lastKnown.transportKind
            SettingsRigViewState(
                descriptorLabel = stripManufacturerPrefix(state.lastKnown.descriptor),
                connected = false,
                staleSinceLabel = "since ${state.sinceMillis}",
                bands = state.lastKnown.bands.map { it.toViewState(stale = true) },
                transportLabel = transportLabelFor(transportKind),
                linkAddressLabel = linkAddressLabel(context),
                rigModuleLabel = rigModuleLabel(state.lastKnown.descriptorId, descriptor, transportKind),
                otherTransportLabel = otherTransportLabel(descriptor, transportKind),
                autoInformation = autoInformationFor(descriptor),
                pollingClause = pollingClauseFor(descriptor),
            )
        }
    }

    private fun RigStatus.BandState.toViewState(stale: Boolean) = SettingsRigBandViewState(
        label = band,
        frequencyLabel = (frequencyHz?.let { "%.3f".format(Locale.ROOT, it / 1_000_000.0) } ?: "—") +
            (if (stale) "?" else ""),
        statusLabel = if (stale) "stale" else (mode ?: "—") + " · " + if (squelchOpen) "squelch open" else "closed",
        squelchOpen = if (stale) false else squelchOpen,
    )

    private fun transportLabelFor(kind: RigLinkTransportKind?): String? = when (kind) {
        RigLinkTransportKind.USB_SERIAL -> "USB serial"
        RigLinkTransportKind.BLUETOOTH_SPP -> "Bluetooth SPP"
        RigLinkTransportKind.BLE -> "Bluetooth LE"
        RigLinkTransportKind.NETWORK -> "network"
        RigLinkTransportKind.NONE, null -> null
    }

    /** The rig-side twin of `SettingsPolling.configuredInputLabel` — `"<descriptor name> ·
     * <transport>"` via [RigPickerCatalogue] (the same lookup S09/S09b/
     * [org.ort.app.ui.data.SessionRouteFacts] use, so a fallback name here is never a second,
     * differently-sourced copy of the onboarding picker's own name), or `null` when the store names
     * no real rig ([org.ort.rig.NullRigModule.ID], or an id the catalogue does not resolve — an
     * imported descriptor since removed). */
    private fun configuredRigLabel(config: CaptureConfiguration): String? {
        if (config.rigId == org.ort.rig.NullRigModule.ID) return null
        val descriptorName = org.ort.app.ui.setup.RigPickerCatalogue.build().entries()
            .firstOrNull { it.id == config.rigId }?.displayName ?: return null
        return listOfNotNull(descriptorName, transportLabelFor(config.rigTransportKind)).joinToString(" · ")
    }

    /** CF11's own single-string "Rig link" fallback — "no radio configured" unchanged when the
     * store has nothing honest to say either. */
    fun configuredRigLinkFallback(context: Context): String {
        val store = realCaptureConfigurationStore(context)
        val label = if (store.hasBeenConfigured()) configuredRigLabel(store.current()) else null
        return label?.let { "$it · not verified this session" } ?: "no radio configured"
    }

    private fun linkAddressLabel(context: Context): String? {
        val params = realCaptureConfigurationStore(context).current().rigParams
        params[DefaultRigTransportFactory.ParamKeys.BLUETOOTH_ADDRESS]?.let { return it }
        val vendorId = params[DefaultRigTransportFactory.ParamKeys.USB_VENDOR_ID]
        val productId = params[DefaultRigTransportFactory.ParamKeys.USB_PRODUCT_ID]
        if (vendorId != null && productId != null) return "vid 0x$vendorId pid 0x$productId"
        return null
    }

    /** R-845/R-916/R-920 (register): moved to the one shared helper N04 and DG04 also call now —
     * see [org.ort.app.ui.data.stripRigManufacturerPrefix]'s own doc comment for the rule itself
     * and why it lives in `ui/data` rather than as three private copies. */
    fun stripManufacturerPrefix(displayName: String): String =
        org.ort.app.ui.data.stripRigManufacturerPrefix(displayName)

    /** Register R-835: the bundled [org.ort.rig.descriptor.RigDescriptor] whose own `id` matches
     * [descriptorId] — `null` for an operator-imported descriptor this build has no bundled copy
     * of to introspect, or when nothing ever reported one. A plain `id`-keyed lookup over the two
     * bundled resources (`org.ort.rig.descriptor.BundledDescriptors`, real, cheap — a resource
     * read of a file already on the classpath, not I/O worth caching) rather than the full
     * `org.ort.rig.catalogue.RigCatalogue` (which also merges in operator-imported descriptors this
     * function has no access to without reading `SetupStore`, WPD's file, out of this package's
     * ownership) — this only ever needs the two bundled ones' own static shape. */
    private fun matchedDescriptor(descriptorId: String?): org.ort.rig.descriptor.RigDescriptor? = when (descriptorId) {
        org.ort.rig.descriptor.BundledDescriptors.kenwoodThD75a().id ->
            org.ort.rig.descriptor.BundledDescriptors
                .kenwoodThD75a()
        org.ort.rig.descriptor.BundledDescriptors.genericAsciiCat().id ->
            org.ort.rig.descriptor.BundledDescriptors
                .genericAsciiCat()
        else -> null
    }

    private fun descriptorTransportKindOf(kind: String): RigLinkTransportKind = when (kind.lowercase()) {
        "usb_serial" -> RigLinkTransportKind.USB_SERIAL
        "bluetooth_spp" -> RigLinkTransportKind.BLUETOOTH_SPP
        "ble" -> RigLinkTransportKind.BLE
        "network" -> RigLinkTransportKind.NETWORK
        else -> RigLinkTransportKind.NONE
    }

    /**
     * `<descriptor id> · built in · verified command set <caps>` — the descriptor's own real,
     * declared capabilities for the transport currently in use, rendered as the CAT mnemonics
     * `Settings-Rig.dc.html`'s own convention uses (`FQ BY …`), never the raw
     * [org.ort.rig.RigCapability] enum names. [NOT_REPORTED_BY_RIG_MODULE] for an unmatched
     * (operator-imported) descriptor.
     *
     * Register R-923 (Reviewer C2, run 3): this used to join the raw enum names
     * ("FREQUENCY, SQUELCH_STATE, SUB_BAND") — real, but not the screen's own established
     * shorthand. [catMnemonicFor] maps each real capability to the real two-letter command
     * `docs/reference/th-d75a-cat.md`'s own verified command table (read before writing this)
     * documents for it — never the board mockup's own full eight-command example (`FQ BY FO BC MR
     * ME AI BL`), which is illustrative of a broader command set no accessible source in this build
     * actually declares (the real, bundled `kenwood-thd75a.json` capability list is only three:
     * `FREQUENCY`, `SQUELCH_STATE`, `SUB_BAND`).
     */
    private fun rigModuleLabel(
        descriptorId: String?,
        descriptor: org.ort.rig.descriptor.RigDescriptor?,
        transportKind: RigLinkTransportKind?,
    ): String {
        if (descriptor == null || descriptorId == null) return NOT_REPORTED_BY_RIG_MODULE
        val transport = descriptor.transports.firstOrNull { descriptorTransportKindOf(it.kind) == transportKind }
            ?: descriptor.transports.firstOrNull()
        val rawCaps = transport?.capabilities ?: return NOT_REPORTED_BY_RIG_MODULE
        val caps = rawCaps.joinToString(" ") { raw ->
            runCatching { org.ort.rig.RigCapability.valueOf(raw) }.getOrNull()?.let(::catMnemonicFor) ?: raw
        }
        return "$descriptorId · built in · verified command set $caps"
    }

    /** R-923: the real two-letter CAT command `docs/reference/th-d75a-cat.md`'s own verified
     * command table documents for each [org.ort.rig.RigCapability] this build can actually declare
     * — never a guessed mnemonic for a capability that table does not cover
     * ([org.ort.rig.RigCapability.SIGNAL_STRENGTH]/`TIME`/`POSITION`, none of which are in that
     * table today), which falls back to the enum's own name, honestly, rather than inventing one. */
    private fun catMnemonicFor(capability: org.ort.rig.RigCapability): String = when (capability) {
        org.ort.rig.RigCapability.FREQUENCY -> "FQ"
        org.ort.rig.RigCapability.SQUELCH_STATE -> "BY"
        org.ort.rig.RigCapability.MODE -> "FO"
        org.ort.rig.RigCapability.SUB_BAND -> "BC"
        org.ort.rig.RigCapability.MEMORY_CHANNEL -> "MR"
        org.ort.rig.RigCapability.CHANNEL_NAME -> "ME"
        org.ort.rig.RigCapability.SIGNAL_STRENGTH,
        org.ort.rig.RigCapability.TIME,
        org.ort.rig.RigCapability.POSITION,
        -> capability.name
    }

    /**
     * The descriptor's *other* declared transport, named plainly — real `vid`/`pid` appended only
     * when the descriptor itself states them. `null` when the descriptor is unmatched or declares
     * only the one transport currently in use.
     *
     * Register R-835 reopened (Reviewer C2, run 3): `kenwood-thd75a.json` leaves both `null`
     * ("still to verify", `BundledDescriptors`'s own doc comment — H1, the operator with the radio
     * in hand over USB, fills them in later) — this used to render as a bare "USB serial also
     * supported" with no mention of vid/pid at all, silently omitting the fact that the ids are
     * simply not yet known rather than genuinely inapplicable. Now says so honestly: "vid/pid not
     * yet verified (H1)" — never the board mockup's own invented `vid 0x0451 pid 0x16a8`.
     */
    private fun otherTransportLabel(
        descriptor: org.ort.rig.descriptor.RigDescriptor?,
        transportKind: RigLinkTransportKind?,
    ): String? {
        val other = descriptor?.transports?.firstOrNull { descriptorTransportKindOf(it.kind) != transportKind }
            ?: return null
        val label = transportLabelFor(descriptorTransportKindOf(other.kind)) ?: return null
        val vidPid = if (other.usbVendorId != null && other.usbProductId != null) {
            ", vid 0x%04x pid 0x%04x".format(Locale.ROOT, other.usbVendorId, other.usbProductId)
        } else {
            ", vid/pid not yet verified (H1)"
        }
        return "$label also supported$vidPid"
    }

    /** `null` (the row is omitted, a structural absence — see [SettingsRigViewState.autoInformation]'s
     * own doc comment) unless the matched descriptor declares an `unsolicited` push block. */
    private fun autoInformationFor(
        descriptor: org.ort.rig.descriptor.RigDescriptor?,
    ): SettingsRigAutoInformationViewState? {
        val unsolicited = descriptor?.unsolicited ?: return null
        val pollClause = descriptor.poll?.let { "fallback poll every ${pollSecondsLabel(it.intervalMs)} if it stops" }
        return SettingsRigAutoInformationViewState(
            label = "Auto-information, ${unsolicited.enable}",
            subLine = listOfNotNull("changes arrive without polling", pollClause).joinToString(" · "),
        )
    }

    /** "reading both bands unpolled" when the matched descriptor declares `unsolicited` (sent on
     * every connect per that field's own contract — real, structural, not a live-observed flag no
     * holder in this build exposes); "polled every N s" from the descriptor's own real
     * `poll.intervalMs` when it declares only that; [NOT_REPORTED_BY_RIG_MODULE] otherwise. */
    private fun pollingClauseFor(descriptor: org.ort.rig.descriptor.RigDescriptor?): String {
        val pollIntervalMs = descriptor?.poll?.intervalMs
        return when {
            descriptor?.unsolicited != null -> "reading both bands unpolled"
            pollIntervalMs != null -> "polled every ${pollSecondsLabel(pollIntervalMs)}"
            else -> NOT_REPORTED_BY_RIG_MODULE
        }
    }

    private fun pollSecondsLabel(intervalMs: Long): String = if (intervalMs % 1000 == 0L) {
        "${intervalMs / 1000} s"
    } else {
        "%.1f s".format(Locale.ROOT, intervalMs / 1000.0)
    }
}
