package org.ort.net

import java.io.InputStream

/**
 * The one seam through which `:net` makes an HTTP call — this interface, [org.ort.net.real.RealHttpRangeClient]
 * beneath it, and nothing else in this module or any of its dependents. A resumable ranged GET:
 * `rangeStart > 0` requests `Range: bytes=<rangeStart>-`, so an interrupted ~100 MB model
 * download can continue rather than restart (FR-AST-3).
 */
public interface HttpRangeClient {
    public fun get(url: String, rangeStart: Long): HttpRangeResult
}

public sealed interface HttpRangeResult {
    /**
     * [servedFromStart] is true when the server ignored the Range header and returned the whole
     * body from byte 0 (HTTP 200) rather than honouring it (HTTP 206) — a caller resuming a
     * partial download MUST discard whatever partial bytes it already had rather than append to
     * them, or the result is corrupt.
     */
    public data class Success(val body: InputStream, val servedFromStart: Boolean) : HttpRangeResult

    public data class Failure(val reason: String, val cause: Throwable? = null) : HttpRangeResult
}
