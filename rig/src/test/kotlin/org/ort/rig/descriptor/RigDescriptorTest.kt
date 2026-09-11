package org.ort.rig.descriptor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private const val JSON_WITH_HEX_USB_IDENTITY = """
{
  "schemaVersion": 1,
  "id": "test-rig",
  "displayName": "Test Rig",
  "transports": [
    {
      "kind": "usb_serial",
      "capabilities": ["FREQUENCY"],
      "usbVendorId": "0x0451",
      "usbProductId": "0x8613",
      "lineTerminator": ";"
    }
  ],
  "poll": {
    "intervalMs": 500,
    "commands": [
      { "send": "FQ", "expect": "^FQ(\\d{10})$", "map": { "frequencyHz": "${'$'}1" } }
    ]
  }
}
"""

private const val JSON_WITHOUT_USB_IDENTITY = """
{
  "schemaVersion": 1,
  "id": "test-rig",
  "displayName": "Test Rig",
  "transports": [
    { "kind": "usb_serial", "capabilities": ["FREQUENCY"] }
  ],
  "poll": {
    "intervalMs": 500,
    "commands": [
      { "send": "FQ", "expect": "^FQ(\\d{10})$", "map": { "frequencyHz": "${'$'}1" } }
    ]
  }
}
"""

/**
 * FR-RIG-3 (WPC3): [TransportSpec.usbVendorId]/[TransportSpec.usbProductId] parse from the hex
 * string form every USB device table uses, via [HexIntSerializer]; [TransportSpec.lineTerminator]
 * is a plain string. Both stay absent (`null`) when a descriptor genuinely does not declare
 * them — never a fabricated default (constitution I).
 */
class RigDescriptorTest {

    @Test
    fun `FR_RIG_3 usbVendorId and usbProductId parse from hex strings in JSON`() {
        val result = DescriptorLoader.load(JSON_WITH_HEX_USB_IDENTITY)
        val descriptor = (result as DescriptorLoadResult.Loaded).descriptor

        val usb = descriptor.transports.single()
        assertEquals(0x0451, usb.usbVendorId)
        assertEquals(0x8613, usb.usbProductId)
        assertEquals(";", usb.lineTerminator)
    }

    @Test
    fun `FR_RIG_3 a descriptor that declares no USB identity leaves both fields null`() {
        val result = DescriptorLoader.load(JSON_WITHOUT_USB_IDENTITY)
        val descriptor = (result as DescriptorLoadResult.Loaded).descriptor

        val usb = descriptor.transports.single()
        assertNull(usb.usbVendorId)
        assertNull(usb.usbProductId)
        assertNull(usb.lineTerminator)
    }

    @Test
    fun `FR_RIG_3 HexIntSerializer round-trips through encode then decode`() {
        val encoded = kotlinx.serialization.json.Json.encodeToString(HexIntSerializer, 0x0451)
        assertEquals("\"0x0451\"", encoded)
        val decoded = kotlinx.serialization.json.Json.decodeFromString(HexIntSerializer, encoded)
        assertEquals(0x0451, decoded)
    }

    @Test
    fun `FR_RIG_3 HexIntSerializer accepts an uppercase 0X prefix on decode`() {
        val decoded = kotlinx.serialization.json.Json.decodeFromString(HexIntSerializer, "\"0X0451\"")
        assertEquals(0x0451, decoded)
    }
}
