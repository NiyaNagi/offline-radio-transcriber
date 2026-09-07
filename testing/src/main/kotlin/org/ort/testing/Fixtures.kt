package org.ort.testing

import java.io.File
import java.io.InputStream

/**
 * Loads test fixtures from the classpath (`src/test/resources/fixtures/...`) or from an
 * on-disk fixtures directory. Kept deliberately small; the corpus proper is the Python
 * `corpus/` subproject (build-plan P2), reachable here only through [CorpusManifest].
 */
public object Fixtures {

    /** Root for on-disk fixtures, overridable with `-Dort.fixtures.dir=` for local corpora. */
    public val dir: File
        get() = File(System.getProperty("ort.fixtures.dir") ?: "src/test/resources/fixtures")

    public fun file(relativePath: String): File {
        val f = File(dir, relativePath)
        require(f.exists()) { "fixture not found: ${f.absolutePath}" }
        return f
    }

    public fun resource(name: String): InputStream =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "classpath fixture not found: $name" }

    public fun text(name: String): String = resource(name).bufferedReader().use { it.readText() }

    public fun bytes(name: String): ByteArray = resource(name).use { it.readBytes() }
}
