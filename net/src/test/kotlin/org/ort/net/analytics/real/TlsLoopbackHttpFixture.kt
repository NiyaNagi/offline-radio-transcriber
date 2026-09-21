package org.ort.net.analytics.real

import org.ort.net.real.LoopbackRequest
import org.ort.net.real.LoopbackResponse
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/**
 * R-1101: a real, in-process HTTPS loopback server — the TLS counterpart to
 * [org.ort.net.real.LoopbackHttpFixture] — for proving [RealAnalyticsUploadClient] against a
 * genuine `SSLServerSocket` handshake rather than only plain HTTP. Reuses that class's own
 * `LoopbackRequest`/`LoopbackResponse` wire types (same module, `internal` visibility) so the two
 * fixtures speak an identical scripted-response shape and differ only in the socket layer.
 *
 * Presents [net/src/test/resources/analytics-tls/server.p12]'s self-signed certificate
 * (`CN=localhost`, SAN `dns:localhost,ip:127.0.0.1`, keystore/key password `changeit` — a
 * throwaway test credential with no real-world value, committed on purpose so this fixture is
 * reproducible; see [ClientTrust] for what a caller uses to decide whether to trust it). Binds to
 * `127.0.0.1` on an ephemeral port only — no external network, and this class never reads
 * [org.ort.net.analytics.real.RealAnalyticsUploadClient.endpoint] from anywhere but a test's own
 * local `port`.
 */
internal class TlsLoopbackHttpFixture private constructor(
    private val serverSocket: SSLServerSocket,
    private val mode: Mode,
) : Closeable {

    /** [Respond] answers every request the same way [org.ort.net.real.LoopbackHttpFixture] does.
     * [AbortAfterHandshake] completes the TLS handshake (so this is never mistaken for a refused
     * certificate) and then closes the socket with no response at all — R-1101's "mid-upload
     * failure": the client's own request write breaks mid-stream against a connection that was
     * genuinely alive a moment before. */
    sealed interface Mode {
        data class Respond(val respond: (LoopbackRequest) -> LoopbackResponse) : Mode
        data object AbortAfterHandshake : Mode
    }

    val port: Int get() = serverSocket.localPort

    @Volatile
    private var stopped = false

    private val thread = Thread({ acceptLoop() }, "tls-loopback-http-fixture").apply {
        isDaemon = true
        start()
    }

    private fun acceptLoop() {
        while (!stopped) {
            val socket = try {
                serverSocket.accept() as SSLSocket
            } catch (e: IOException) {
                return
            }
            try {
                handleOne(socket)
            } catch (e: IOException) {
                // A scripted abort or a mid-body drop is expected to break the pipe.
            } finally {
                try {
                    socket.close()
                } catch (e: IOException) {
                    // already gone
                }
            }
        }
    }

    private fun handleOne(socket: SSLSocket) {
        socket.startHandshake()
        when (val m = mode) {
            is Mode.AbortAfterHandshake -> return // handshake succeeded; closes with no response.
            is Mode.Respond -> {
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

                val response = m.respond(LoopbackRequest(method, path, headers))
                response.writeTo(socket.getOutputStream())
            }
        }
    }

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

    companion object {
        /** Starts a fixture that answers every request via [respond] — the "successful upload"
         * and "server returns a non-2xx status" shapes. */
        fun respond(respond: (LoopbackRequest) -> LoopbackResponse): TlsLoopbackHttpFixture =
            TlsLoopbackHttpFixture(newServerSocket(), Mode.Respond(respond))

        /** Starts a fixture that completes the handshake and then drops every connection with no
         * response — R-1101's "mid-upload failure" shape. */
        fun abortAfterHandshake(): TlsLoopbackHttpFixture =
            TlsLoopbackHttpFixture(newServerSocket(), Mode.AbortAfterHandshake)

        private fun newServerSocket(): SSLServerSocket {
            val keyStore = TestKeyMaterial.serverKeyStore()
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, TestKeyMaterial.PASSWORD)
            val context = SSLContext.getInstance("TLS")
            context.init(kmf.keyManagers, null, null)
            return context.serverSocketFactory
                .createServerSocket(0, 50, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        }
    }
}

/**
 * R-1101: the one place both [TlsLoopbackHttpFixture] (the server side) and
 * [RealAnalyticsUploadClientTlsTest] (the trusting-client side) load the same throwaway,
 * committed test credential from `net/src/test/resources/analytics-tls/` — `server.p12` (the
 * self-signed cert + private key the fixture presents) and `truststore.p12` (that same
 * certificate alone, so a test can build an [javax.net.ssl.SSLContext] that trusts it without
 * ever touching the JVM's real, system-wide default trust store). Both keystores share one
 * password, [PASSWORD] — `changeit`, a well-known Java-tooling default, not a secret: this
 * material's only purpose is to be a certificate nobody's real trust store already contains, so
 * connecting against it with the *default* trust manager (no override) is R-1101's "refused
 * certificate" case.
 */
internal object TestKeyMaterial {
    val PASSWORD: CharArray = "changeit".toCharArray()

    fun serverKeyStore(): KeyStore = loadResource("server.p12")

    fun trustingSslContext(): SSLContext {
        val trustStore = loadResource("truststore.p12")
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trustStore)
        val context = SSLContext.getInstance("TLS")
        context.init(null, tmf.trustManagers, null)
        return context
    }

    private fun loadResource(name: String): KeyStore {
        val keyStore = KeyStore.getInstance("PKCS12")
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("analytics-tls/$name")) {
            "missing test fixture analytics-tls/$name on the test classpath"
        }
        stream.use { keyStore.load(it, PASSWORD) }
        return keyStore
    }
}
