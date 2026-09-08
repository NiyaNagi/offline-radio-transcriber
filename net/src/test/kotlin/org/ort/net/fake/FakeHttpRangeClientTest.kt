package org.ort.net.fake

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.ort.net.HttpRangeResult
import java.io.IOException

/** The fake is tested on its own terms before [org.ort.net.ModelAcquisition] is trusted to drive it. */
class FakeHttpRangeClientTest {

    @Test
    fun `serves the full body from a fresh request and records it`() {
        val body = "abcdefgh".toByteArray()
        val client = FakeHttpRangeClient(body)

        val result = client.get("https://example.invalid/model.bin", 0L) as HttpRangeResult.Success
        assertEquals(true, result.servedFromStart)
        assertEquals("abcdefgh", result.body.readBytes().decodeToString())
        assertEquals(listOf("https://example.invalid/model.bin" to 0L), client.requests)
    }

    @Test
    fun `honours a range request by slicing the body from the requested offset`() {
        val body = "abcdefgh".toByteArray()
        val client = FakeHttpRangeClient(body)

        val result = client.get("u", 4L) as HttpRangeResult.Success
        assertEquals(false, result.servedFromStart)
        assertEquals("efgh", result.body.readBytes().decodeToString())
    }

    @Test
    fun `drops the connection after the scripted byte count`() {
        val body = "abcdefgh".toByteArray()
        val client = FakeHttpRangeClient(body)
        client.dropAfterBytes = 3

        val result = client.get("u", 0L) as HttpRangeResult.Success
        assertThrows(IOException::class.java) { result.body.readBytes() }
    }
}
