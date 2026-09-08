package org.ort.net

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement
import java.io.File

/**
 * Audit F-008 (the `:net` half) — constitution V names a user-initiated model download as one of
 * exactly two declared outbound channels. That channel cannot exist unless `:net`'s own manifest
 * grants `android.permission.INTERNET`, and constitution V is only honoured if no *other*
 * module's manifest does — `dependencyRules`/`PlatformGuards` (buildSrc) enforce the module-graph
 * and dependency-coordinate side of this structurally; this test is the same assertion made from
 * inside `:net` against the actual committed manifests on disk, independent of the Gradle build
 * script wiring, so a regression here fails a plain `./gradlew :net:test` with no build-script
 * involvement at all.
 *
 * This is a plain-file/XML-text check, not a Robolectric or on-device test: it proves what is
 * *declared* in source, never that a build actually made (or was blocked from making) a network
 * call at runtime.
 */
class NetManifestPermissionTest {

    private val internetPermission = "android.permission.INTERNET"

    /** Walk up from the test's working directory to the repo root (identified by settings.gradle.kts). */
    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("Could not locate repo root (settings.gradle.kts) above ${File(".").absolutePath}")
        }
        return dir
    }

    /** Every module directory that carries its own `src/main/AndroidManifest.xml`, by module path (e.g. ":net"). */
    private fun manifestsByModule(root: File): Map<String, String> =
        root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            .orEmpty()
            .mapNotNull { moduleDir ->
                val manifest = File(moduleDir, "src/main/AndroidManifest.xml")
                if (manifest.isFile) ":${moduleDir.name}" to manifest.readText() else null
            }
            .toMap()

    @Test
    @Requirement("NFR-6")
    fun `NFR_6 the INTERNET permission is declared by net and only net`() {
        val manifests = manifestsByModule(repoRoot())

        // The declared channel must exist (constitution V, audit F-008): the fix half this test
        // establishes. Failing this before the manifest change is the point of writing it first.
        val netManifest = manifests[":net"]
        assertTrue(
            netManifest != null && netManifest.contains(internetPermission),
            ":net's manifest must declare $internetPermission — it is the only declared channel " +
                "for a user-initiated model download (constitution V, FR-ASR-1, technical design §8.4).",
        )

        // Exclusivity: no other module's manifest may carry it (constitution V, AC-59, NFR-6) —
        // mirrors PlatformGuards.internetPermissionViolations without importing buildSrc, since
        // buildSrc is a separate included build not on this module's test classpath.
        val offenders = manifests.filterKeys { it != ":net" }.filterValues { it.contains(internetPermission) }
        assertTrue(
            offenders.isEmpty(),
            "Only :net may declare $internetPermission; found it in: ${offenders.keys} " +
                "(constitution V, AC-59, NFR-6).",
        )
    }

    @Test
    @Requirement("NFR-6")
    fun `NFR_6 every non-net manifest on disk is free of the INTERNET permission`() {
        // Belt-and-braces companion to the test above, phrased the other way round so a future
        // manifest that only *removed* net's grant (rather than adding one elsewhere) cannot make
        // this file look green by accident — assertFalse over the raw text of every found manifest.
        val root = repoRoot()
        val nonNetManifests = root.listFiles { f -> f.isDirectory && f.name != "net" && !f.name.startsWith(".") }
            .orEmpty()
            .mapNotNull { moduleDir ->
                val manifest = File(moduleDir, "src/main/AndroidManifest.xml")
                if (manifest.isFile) manifest else null
            }

        assertTrue(nonNetManifests.isNotEmpty(), "Expected to find at least one other module's manifest to check.")
        nonNetManifests.forEach { manifest ->
            assertFalse(
                manifest.readText().contains(internetPermission),
                "${manifest.path} must not declare $internetPermission — only :net may (constitution V).",
            )
        }
    }
}
