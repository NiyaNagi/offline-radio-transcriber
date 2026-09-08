package org.ort.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Audit F-027 — the meta-guard for `./gradlew platformGuards`. Structural proxies only: a
 * dependency-coordinate or manifest-text check proves what was *declared*, never what a build
 * does at runtime. See [PlatformGuardsTask] and its KDoc for exactly what each check does and
 * does not establish.
 */
class PlatformGuardsTest {

    @Test
    fun `FR_OBS_5 a telemetry dependency coordinate anywhere is reported`() {
        val deps = mapOf(
            ":app" to setOf("com.google.firebase:firebase-crashlytics:18.0.0"),
            ":core" to setOf("org.jetbrains.kotlin:kotlin-stdlib:2.0.21"),
        )
        val violations = PlatformGuards.telemetryViolations(deps)
        assertEquals(1, violations.size)
        assertEquals(":app", violations.single().module)
    }

    @Test
    fun `FR_OBS_5 no violation when no dependency matches the telemetry list`() {
        val deps = mapOf(":app" to setOf("androidx.core:core-ktx:1.13.1"))
        assertTrue(PlatformGuards.telemetryViolations(deps).isEmpty())
    }

    @Test
    fun `NFR_6 an HTTP client dependency outside net is reported`() {
        val deps = mapOf(
            ":pipeline" to setOf("com.squareup.okhttp3:okhttp:4.12.0"),
            ":net" to setOf("com.squareup.okhttp3:okhttp:4.12.0"),
        )
        val violations = PlatformGuards.httpClientViolations(deps)
        assertEquals(1, violations.size)
        assertEquals(":pipeline", violations.single().module)
    }

    @Test
    fun `NFR_6 an HTTP client dependency inside net alone is not reported`() {
        val deps = mapOf(":net" to setOf("com.squareup.okhttp3:okhttp:4.12.0"))
        assertTrue(PlatformGuards.httpClientViolations(deps).isEmpty())
    }

    @Test
    fun `AC_59 NFR_6 an INTERNET permission declared outside net is reported`() {
        val manifests = mapOf(
            ":capture-android" to """<manifest><uses-permission android:name="android.permission.INTERNET"/></manifest>""",
            ":net" to """<manifest><uses-permission android:name="android.permission.INTERNET"/></manifest>""",
        )
        val violations = PlatformGuards.internetPermissionViolations(manifests)
        assertEquals(1, violations.size)
        assertEquals(":capture-android", violations.single().module)
    }

    @Test
    fun `AC_59 NFR_6 no INTERNET permission anywhere outside net is not reported`() {
        val manifests = mapOf(":capture-android" to "<manifest />", ":net" to "<manifest />")
        assertTrue(PlatformGuards.internetPermissionViolations(manifests).isEmpty())
    }
}
