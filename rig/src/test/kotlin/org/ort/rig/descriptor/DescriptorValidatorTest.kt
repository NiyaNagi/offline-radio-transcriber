package org.ort.rig.descriptor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** FR-RIG-11: one test per rejection reason, plus the newer-version-fails-cleanly case. */
class DescriptorValidatorTest {

    private fun validDescriptor() = RigDescriptor(
        schemaVersion = 1,
        id = "test-rig",
        displayName = "Test Rig",
        transports = listOf(
            TransportSpec(kind = "usb_serial", capabilities = listOf("FREQUENCY", "SQUELCH_STATE")),
        ),
        poll = PollSpec(
            intervalMs = 500,
            commands = listOf(
                CommandSpec(send = "FQ", expect = "^FQ(\\d{10})$", map = mapOf("frequencyHz" to "$1")),
                CommandSpec(send = "BY", expect = "^BY(\\d)$", map = mapOf("squelchOpen" to "$1")),
            ),
        ),
    )

    @Test
    fun `FR_RIG_11_a valid descriptor has no errors`() {
        assertTrue(DescriptorValidator.validate(validDescriptor()).isEmpty())
    }

    @Test
    fun `FR_RIG_11_unknown_transport_kind is rejected`() {
        val descriptor = validDescriptor().copy(
            transports = listOf(TransportSpec(kind = "carrier_pigeon", capabilities = emptyList())),
        )

        val errors = DescriptorValidator.validate(descriptor)

        assertTrue(errors.any { it is DescriptorError.UnknownTransportKind && it.kind == "carrier_pigeon" })
    }

    @Test
    fun `FR_RIG_11_missing_expect_pattern is rejected`() {
        val descriptor = validDescriptor().copy(
            poll = PollSpec(intervalMs = 500, commands = listOf(CommandSpec(send = "FQ", expect = ""))),
        )

        val errors = DescriptorValidator.validate(descriptor)

        assertTrue(errors.any { it is DescriptorError.MissingExpectPattern && it.command == "FQ" })
    }

    @Test
    fun `FR_RIG_11_capability_not_derivable is rejected`() {
        val descriptor = validDescriptor().copy(
            transports = listOf(
                TransportSpec(kind = "usb_serial", capabilities = listOf("FREQUENCY", "MODE")),
            ),
            // no command in this descriptor's poll block maps "mode"
        )

        val errors = DescriptorValidator.validate(descriptor)

        assertTrue(
            errors.any { it is DescriptorError.CapabilityNotDerivable && it.capability == "MODE" },
        )
    }

    @Test
    fun `FR_AST_7 newer schema version fails cleanly and applies nothing else`() {
        val descriptor = validDescriptor().copy(
            schemaVersion = SUPPORTED_DESCRIPTOR_SCHEMA_VERSION + 1,
            // also carries an otherwise-fatal problem, to prove the newer-version error alone is
            // reported rather than being mixed in with unrelated validation noise.
            transports = listOf(TransportSpec(kind = "not_a_real_kind")),
        )

        val errors = DescriptorValidator.validate(descriptor)

        val expectedError = DescriptorError.UnsupportedSchemaVersion(
            found = SUPPORTED_DESCRIPTOR_SCHEMA_VERSION + 1,
            supported = SUPPORTED_DESCRIPTOR_SCHEMA_VERSION,
        )
        assertEquals(listOf(expectedError), errors)
    }

    @Test
    fun `no transports is rejected`() {
        val descriptor = validDescriptor().copy(transports = emptyList())

        val errors = DescriptorValidator.validate(descriptor)

        assertTrue(errors.any { it is DescriptorError.NoTransports })
    }

    @Test
    fun `invalid regex is rejected`() {
        val descriptor = validDescriptor().copy(
            poll = PollSpec(intervalMs = 500, commands = listOf(CommandSpec(send = "FQ", expect = "^FQ(\\d{10}$"))),
        )

        val errors = DescriptorValidator.validate(descriptor)

        assertTrue(errors.any { it is DescriptorError.InvalidRegex })
    }

    @Test
    fun `catastrophically backtracking regex is rejected as too complex`() {
        val descriptor = validDescriptor().copy(
            poll = PollSpec(intervalMs = 500, commands = listOf(CommandSpec(send = "FQ", expect = "^(a+)+$"))),
        )

        val errors = DescriptorValidator.validate(descriptor)

        assertTrue(errors.any { it is DescriptorError.RegexTooComplex })
    }
}
