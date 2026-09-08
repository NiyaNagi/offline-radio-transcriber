package org.ort.net.real

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * A minimal loopback HTTP/1.1 server for exercising [RealHttpRangeClient] without
 * `com.sun.net.httpserver.HttpServer` (see `net/README.md` — `jdk.httpserver` is not on this
 * Android-library module's unit-test classpath; JPMS module visibility). This speaks only as
 * much HTTP/1.1 as [RealHttpRangeClient] needs: a request line, headers up to the blank line, and
 * a scripted response. Binds to `127.0.0.1` on an ephemeral port only — no external network.
 */
internal class LoopbackHttpFixture(private val respond: (LoopbackRequest) -> LoopbackResponse) : Closeable {

    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = serverSocket.localPort

    @Volatile
    private var stopped = false

    private val thread = Thread({ acceptLoop() }, "loopback-http-fixture").apply {
        isDaemon = true
        start()
    }

    private fun acceptLoop() {
        while (!stopped) {
            val socket = try {
                serverSocket.accept()
            } catch (e: IOException) {
                return
            }
            try {
                handleOne(socket)
            } catch (e: IOException) {
                // The scripted response (e.g. a mid-body drop) is expected to break the pipe.
            } finally {
                try {
                    socket.close()
                } catch (e: IOException) {
                    // already gone
                }
            }
        }
    }

    private fun handleOne(socket: Socket) {
        val input = socket.getInputStream()
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(" ")
        val method = parts.getOrElse(0) { "" }
        val path = parts.getOrElse(1) { "/" }

        val headers = mutableMapOf<String, String>()
        var line = readLine(input)
        while (!line.isNullOrEmpty()) {
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            line = readLine(input)
        }

        val response = respond(LoopbackRequest(method, path, headers))
        response.writeTo(socket.getOutputStream())
    }

    /** Reads one CRLF- or LF-terminated line of header bytes as ASCII; null at EOF with nothing read. */
    private fun readLine(input: InputStream): String? {
        val buffer = StringBuilder()
        var any = false
        var done = false
        while (!done) {
            val b = input.read()
            if (b == -1) {
                done = true
            } else {
                any = true
                if (b == '\n'.code) {
                    done = true
                } else {
                    buffer.append(b.toChar())
                }
            }
        }
        if (buffer.isNotEmpty() && buffer.last() == '\r') buffer.deleteCharAt(buffer.length - 1)
        return if (any) buffer.toString() else null
    }

    override fun close() {
        stopped = true
        serverSocket.close()
        thread.join(1_000)
    }
}

internal data class LoopbackRequest(val method: String, val path: String, val headers: Map<String, String>)

/**
 * A scripted HTTP/1.1 response. [truncateBodyTo], when set, writes only that many body bytes
 * (fewer than [body]'s size) and then returns — the socket is closed by the fixture's caller,
 * simulating a server that dies mid-transfer despite having promised a full `Content-Length`.
 */
internal class LoopbackResponse(
    private val status: Int,
    private val reason: String,
    private val body: ByteArray,
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val truncateBodyTo: Int? = null,
) {
    fun writeTo(out: OutputStream) {
        val headerText = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            for ((name, value) in extraHeaders) append("$name: $value\r\n")
            append("\r\n")
        }
        out.write(headerText.toByteArray(Charsets.US_ASCII))
        out.write(body, 0, truncateBodyTo ?: body.size)
        out.flush()
    }
}
