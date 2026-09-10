package org.ort.rig.catalogue

import org.ort.rig.NullRigModule
import org.ort.rig.RigCapability
import org.ort.rig.RigTransportKind
import org.ort.rig.descriptor.BundledDescriptors
import org.ort.rig.descriptor.DescriptorError
import org.ort.rig.descriptor.DescriptorLoadResult
import org.ort.rig.descriptor.DescriptorLoader
import org.ort.rig.descriptor.RigDescriptor
import java.io.File

/** One radio in the onboarding picker (FR-RIG-16/FR-RIG-17): which transports, and what each
 * transport actually yields — they genuinely differ, so the operator sees the gap up front. */
public data class RigCatalogueEntry(
    public val id: String,
    public val displayName: String,
    public val verified: Boolean,
    public val transportCapabilities: Map<RigTransportKind, Set<RigCapability>>,
)

/** Thrown from [RigCatalogue.import] when the supplied descriptor fails validation. */
public class DescriptorImportException(public val errors: List<DescriptorError>) :
    Exception(errors.joinToString("; ") { it.message })

/**
 * FR-RIG-16..19: the onboarding radio picker, generated from an installed descriptor set — never
 * a hardcoded list. [entries] always leads with the null module and the generic ASCII CAT entry
 * (FR-RIG-18), reachable without scrolling past whatever else is installed, regardless of what
 * [fromDescriptors] was given — adding a descriptor to the installed set is the only thing that
 * changes the rest of the list (FR-RIG-16 / AC-134).
 */
public class RigCatalogue private constructor(private val entries: List<RigCatalogueEntry>) {

    public fun entries(): List<RigCatalogueEntry> = entries

    public companion object {

        /**
         * Builds the catalogue from an arbitrary installed descriptor set. The null module and
         * the generic ASCII CAT entry are always present — loaded from this package's own
         * bundled resource, not passed in — regardless of what [descriptors] contains, and lead
         * the list (AC-135). A descriptor whose [RigDescriptor.id] collides with either is
         * dropped in favour of the bundled one, so a corrupt or spoofed import cannot displace
         * the two entries onboarding always guarantees.
         */
        public fun fromDescriptors(descriptors: Set<RigDescriptor>): RigCatalogue {
            val generic = BundledDescriptors.genericAsciiCat()
            val rest = descriptors
                .filterNot { it.id == NullRigModule.ID || it.id == generic.id }
                .map { it.toCatalogueEntry() }
                .sortedBy { it.displayName }
            return RigCatalogue(listOf(nullEntry()) + generic.toCatalogueEntry() + rest)
        }

        /** FR-RIG-19: import one descriptor from a file's text, through the same validator every
         * installed descriptor goes through (FR-AST-7 governs its version check). */
        public fun import(text: String): Result<RigDescriptor> = when (val result = DescriptorLoader.load(text)) {
            is DescriptorLoadResult.Loaded -> Result.success(result.descriptor)
            is DescriptorLoadResult.Rejected -> Result.failure(DescriptorImportException(result.errors))
        }

        /** Convenience overload of [import] for a descriptor on disk. */
        public fun import(file: File): Result<RigDescriptor> = import(file.readText())

        private fun nullEntry(): RigCatalogueEntry = RigCatalogueEntry(
            id = NullRigModule.ID,
            displayName = NullRigModule.DISPLAY_NAME,
            verified = true,
            transportCapabilities = mapOf(RigTransportKind.NONE to emptySet()),
        )
    }
}

private fun RigDescriptor.toCatalogueEntry(): RigCatalogueEntry = RigCatalogueEntry(
    id = id,
    displayName = displayName,
    verified = verified,
    transportCapabilities = transports.associate { spec ->
        val kind = when (spec.kind.lowercase()) {
            "usb_serial" -> RigTransportKind.USB_SERIAL
            "bluetooth_spp" -> RigTransportKind.BLUETOOTH_SPP
            "ble" -> RigTransportKind.BLE
            "network" -> RigTransportKind.NETWORK
            else -> RigTransportKind.NONE
        }
        kind to spec.capabilities.mapNotNull { runCatching { RigCapability.valueOf(it) }.getOrNull() }.toSet()
    },
)
