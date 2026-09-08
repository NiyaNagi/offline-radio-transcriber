package org.ort.net.fake

import org.ort.net.HttpRangeClient
import org.ort.net.HttpRangeResult
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * The behavioural fake for [HttpRangeClient] (constitution II) — every test in this module, and
 * every future `:app` test, drives model acquisition through this rather than a real socket, so
 * no ordinary test run ever makes a real network call.
 *
 * Scriptable to serve the full body, honour or ignore a `Range` request, drop the connection
 * after a fixed number of bytes (to exercise resume), or fail outright.
 */
public class FakeHttpRangeClient(private val fullBody: ByteArray) : HttpRangeClient {

    /** Every `(url, rangeStart)` this fake was asked for, in order — assert resume/idempotence against this. */
    public val requests: MutableList<Pair<String, Long>> = mutableListOf()

    /** When set, the returned stream throws after serving this many bytes of the *current* response. */
    public var dropAfterBytes: Int? = null

    /** When set, [get] returns this unconditionally instead of serving any body. */
    public var scriptedFailure: HttpRangeResult.Failure? = null

    /** When false, the server ignores `rangeStart` and always serves the whole body from byte 0. */
    public var honoursRange: Boolean = true

    override fun get(url: String, rangeStart: Long): HttpRangeResult {
        requests += url to rangeStart
        scriptedFailure?.let { return it }

        val servedFromStart = !honoursRange || rangeStart == 0L
        val slice = if (servedFromStart) fullBody else fullBody.copyOfRange(rangeStart.toInt(), fullBody.size)
        val limit = dropAfterBytes
        val stream: InputStream = if (limit != null) {
            DroppingInputStream(ByteArrayInputStream(slice), limit)
        } else {
            ByteArrayInputStream(slice)
        }
        return HttpRangeResult.Success(stream, servedFromStart)
    }

    private class DroppingInputStream(delegate: InputStream, private val limit: Int) : FilterInputStream(delegate) {
        private var served = 0

        override fun read(): Int = error("single-byte read not exercised by this fake")

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (served >= limit) throw IOException("scripted connection drop after $served bytes")
            val n = super.read(b, off, minOf(len, limit - served))
            if (n > 0) served += n
            return n
        }
    }
}
