package org.ort.app.ui.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.app.ui.setup.RigPickerCatalogue.pickerOrder
import org.ort.core.capture.RigTransportKind as PresetRigTransportKind
import org.ort.rig.NullRigModule
import org.ort.rig.RigCapability
import org.ort.rig.RigTransportKind

/**
 * E2-E08 (`spec/e2e-capture-modes-plan.md` WPD) — AC-134/AC-135: S09 is generated from `:rig`'s
 * catalogue, and the null module + generic entry are reachable without scrolling past the radio
 * list. This is the pure half; `RadioScreenTest` proves the composed rendering.
 */
class RigCatalogueLabelsTest {

    private val catalogue = RigPickerCatalogue.build()

    @Test
    fun `AC_134 the TH-D75A is generated from the bundled descriptor, not hardcoded board copy`() {
        val entry = catalogue.entries().first { it.displayName == "Kenwood TH-D75A" }

        assertTrue(entry.verified)
        assertEquals(
            setOf(RigTransportKind.USB_SERIAL, RigTransportKind.BLUETOOTH_SPP),
            entry.transportCapabilities.keys,
        )
    }

    @Test
    fun `AC_135 the null module and the generic entry are the last two rows in picker order`() {
        val ordered = catalogue.pickerOrder()

        assertEquals(NullRigModule.ID, ordered.last().id)
        assertEquals("Generic ASCII CAT", ordered[ordered.size - 2].displayName)
    }

    @Test
    fun `AC_134 a rig added to the descriptor set appears in picker order with no code change`() {
        val imported = org.ort.rig.descriptor.RigDescriptor(
            schemaVersion = 1,
            id = "acme-9000",
            displayName = "Acme 9000",
            transports = listOf(org.ort.rig.descriptor.TransportSpec(kind = "usb_serial", capabilities = listOf("FREQUENCY"))),
        )
        val withImport = RigPickerCatalogue.build(setOf(imported)).pickerOrder()

        assertTrue(withImport.any { it.displayName == "Acme 9000" })
        // still last two, even with a new named rig added.
        assertEquals(NullRigModule.ID, withImport.last().id)
    }

    @Test
    fun `FR_RIG_17 subtitleFor generates transports and capabilities for the TH-D75A`() {
        val thd75a = catalogue.entries().first { it.displayName == "Kenwood TH-D75A" }
        val subtitle = RigPickerCatalogue.subtitleFor(thd75a)

        assertTrue(subtitle.contains("USB serial"))
        assertTrue(subtitle.contains("Bluetooth SPP"))
        assertTrue(subtitle.contains("frequency"))
        assertTrue(subtitle.contains("squelch"))
        assertTrue(subtitle.contains("verified"))
    }

    @Test
    fun `FR_RIG_17 subtitleFor the null module names manual entry, never a fabricated capability`() {
        val null_ = catalogue.entries().first { it.id == NullRigModule.ID }
        val subtitle = RigPickerCatalogue.subtitleFor(null_)

        assertEquals("Scanner, a handheld near the phone, or a rig with no data port", subtitle)
    }

    @Test
    fun `FR_RIG_17 capabilitiesFor returns only the capabilities that transport actually declares`() {
        val thd75a = catalogue.entries().first { it.displayName == "Kenwood TH-D75A" }

        val usb = RigPickerCatalogue.capabilitiesFor(thd75a, RigTransportKind.USB_SERIAL)
        val bluetooth = RigPickerCatalogue.capabilitiesFor(thd75a, RigTransportKind.BLUETOOTH_SPP)

        // AC-133: identical capabilities over both transports for the TH-D75A (parity).
        assertEquals(usb.toSet(), bluetooth.toSet())
        assertTrue(usb.contains("frequency"))
        assertTrue(usb.contains("squelch"))
    }

    @Test
    fun `capabilitiesFor a transport the entry does not declare is empty, never fabricated`() {
        val thd75a = catalogue.entries().first { it.displayName == "Kenwood TH-D75A" }

        assertEquals(emptyList<String>(), RigPickerCatalogue.capabilitiesFor(thd75a, RigTransportKind.NETWORK))
    }

    @Test
    fun `toPresetKind maps USB serial and Bluetooth SPP, and nothing else`() {
        assertEquals(PresetRigTransportKind.USB_SERIAL, RigPickerCatalogue.toPresetKind(RigTransportKind.USB_SERIAL))
        assertEquals(
            PresetRigTransportKind.BLUETOOTH_SPP,
            RigPickerCatalogue.toPresetKind(RigTransportKind.BLUETOOTH_SPP),
        )
        assertNull(RigPickerCatalogue.toPresetKind(RigTransportKind.BLE))
        assertNull(RigPickerCatalogue.toPresetKind(RigTransportKind.NETWORK))
        assertNull(RigPickerCatalogue.toPresetKind(RigTransportKind.NONE))
    }

    @Test
    fun `every RigCapability has a generated label, never an empty or enum-name fallback`() {
        RigCapability.entries.forEach { capability ->
            val label = RigPickerCatalogue.capabilityLabel(capability)
            assertTrue(label.isNotBlank())
            assertTrue(label == label.lowercase(), "expected a lowercase operator-facing label, got '$label'")
        }
    }
}
