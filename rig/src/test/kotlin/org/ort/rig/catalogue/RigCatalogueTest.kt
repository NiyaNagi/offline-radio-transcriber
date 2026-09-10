package org.ort.rig.catalogue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.rig.NullRigModule
import org.ort.rig.RigCapability
import org.ort.rig.RigTransportKind
import org.ort.rig.descriptor.BundledDescriptors
import org.ort.rig.descriptor.RigDescriptor
import org.ort.rig.descriptor.TransportSpec

private fun thirdPartyDescriptor(): RigDescriptor = RigDescriptor(
    schemaVersion = 1,
    id = "acme-9000",
    displayName = "Acme 9000",
    transports = listOf(TransportSpec(kind = "usb_serial", capabilities = listOf("FREQUENCY"))),
    poll = null,
)

class RigCatalogueTest {

    @Test
    fun `AC_135 null and generic are reachable first, with an empty installed set`() {
        val catalogue = RigCatalogue.fromDescriptors(emptySet())

        val ids = catalogue.entries().map { it.id }
        assertEquals(listOf(NullRigModule.ID, "generic-ascii-cat"), ids.take(2))
    }

    @Test
    fun `AC_134 a level-1 descriptor added to the installed set appears with no code change`() {
        val catalogue = RigCatalogue.fromDescriptors(setOf(thirdPartyDescriptor()))

        val entry = catalogue.entries().firstOrNull { it.id == "acme-9000" }

        assertTrue(entry != null, "the newly-installed descriptor must appear in the catalogue")
        assertEquals(
            mapOf(RigTransportKind.USB_SERIAL to setOf(RigCapability.FREQUENCY)),
            entry!!.transportCapabilities,
        )
    }

    @Test
    fun `AC_135 null and generic still lead even with several radios installed`() {
        val catalogue = RigCatalogue.fromDescriptors(
            setOf(thirdPartyDescriptor(), BundledDescriptors.kenwoodThD75a()),
        )

        val ids = catalogue.entries().map { it.id }
        assertEquals(listOf(NullRigModule.ID, "generic-ascii-cat"), ids.take(2))
        assertTrue("kenwood-thd75a" in ids)
        assertTrue("acme-9000" in ids)
    }

    @Test
    fun `the bundled TH-D75A descriptor is marked verified, a third-party one is not`() {
        val catalogue = RigCatalogue.fromDescriptors(
            setOf(thirdPartyDescriptor(), BundledDescriptors.kenwoodThD75a()),
        )

        assertTrue(catalogue.entries().first { it.id == "kenwood-thd75a" }.verified)
        assertTrue(!catalogue.entries().first { it.id == "acme-9000" }.verified)
    }

    @Test
    fun `FR_RIG_19 import validates through the same validator, success and failure`() {
        val valid = RigCatalogue.import(
            """{"schemaVersion":1,"id":"x","displayName":"X","transports":[{"kind":"usb_serial","capabilities":[]}]}""",
        )
        assertTrue(valid.isSuccess)

        val invalid = RigCatalogue.import(
            """{"schemaVersion":1,"id":"x","displayName":"X",""" +
                """"transports":[{"kind":"carrier_pigeon","capabilities":[]}]}""",
        )
        assertTrue(invalid.isFailure)
        assertTrue(invalid.exceptionOrNull() is DescriptorImportException)
    }
}
