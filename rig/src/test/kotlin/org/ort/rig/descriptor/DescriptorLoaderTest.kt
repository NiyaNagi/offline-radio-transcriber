package org.ort.rig.descriptor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.rig.NullRigModule
import org.ort.rig.RigTransportKind
import org.ort.rig.fakes.FakeRigTransport

private const val VALID_JSON = """
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

private const val INVALID_JSON = """
{
  "schemaVersion": 1,
  "id": "test-rig",
  "displayName": "Test Rig",
  "transports": [
    { "kind": "carrier_pigeon", "capabilities": [] }
  ]
}
"""

private const val NOT_JSON_AT_ALL = "this is not json"

class DescriptorLoaderTest {

    @Test
    fun `FR_RIG_4 valid JSON loads into a descriptor`() {
        val result = DescriptorLoader.load(VALID_JSON)

        assertTrue(result is DescriptorLoadResult.Loaded)
        assertEquals("test-rig", (result as DescriptorLoadResult.Loaded).descriptor.id)
    }

    @Test
    fun `FR_RIG_11 malformed JSON is rejected, not thrown`() {
        val result = DescriptorLoader.load(NOT_JSON_AT_ALL)

        assertTrue(result is DescriptorLoadResult.Rejected)
        assertTrue((result as DescriptorLoadResult.Rejected).errors.single() is DescriptorError.MalformedJson)
    }

    @Test
    fun `FR_RIG_11 a failing descriptor loads a NullRigModule with the error attached`() {
        val module = DescriptorLoader.loadModule(INVALID_JSON) { _, _ -> FakeRigTransport() }

        assertTrue(module is NullRigModule)
        val nullModule = module as NullRigModule
        assertTrue(nullModule.descriptorError!!.contains("carrier_pigeon"))
    }

    @Test
    fun `FR_RIG_4 a valid descriptor loads a DescriptorRigModule`() {
        val module = DescriptorLoader.loadModule(VALID_JSON) { _, _ -> FakeRigTransport() }

        assertTrue(module is DescriptorRigModule)
        assertEquals(setOf(RigTransportKind.USB_SERIAL), module.transports)
    }
}
