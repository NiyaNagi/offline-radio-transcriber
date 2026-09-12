package org.ort.gradle

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

    // audit F-008: exclusivity alone is not the requirement — the declared channel must exist.
    // A manifest set where every module (net included) lacks INTERNET passes
    // internetPermissionViolations() vacuously; that is a defect on its own (constitution V — the
    // user-initiated download channel must be able to reach the network at all, not just be the
    // only one that could).
    @Test
    fun `F_008 constitution_V net missing the INTERNET permission is reported even though no one else has it`() {
        val manifests = mapOf(":capture-android" to "<manifest />", ":net" to "<manifest />")
        val violations = PlatformGuards.missingInternetPermissionViolations(manifests)
        assertEquals(1, violations.size)
        assertEquals(":net", violations.single().module)
    }

    @Test
    fun `F_008 constitution_V net declaring INTERNET is not reported as missing`() {
        val manifests = mapOf(
            ":capture-android" to "<manifest />",
            ":net" to """<manifest><uses-permission android:name="android.permission.INTERNET"/></manifest>""",
        )
        assertTrue(PlatformGuards.missingInternetPermissionViolations(manifests).isEmpty())
    }

    @Test
    fun `F_008 constitution_V net absent from the manifest map entirely is reported as missing`() {
        val manifests = mapOf(":capture-android" to "<manifest />")
        val violations = PlatformGuards.missingInternetPermissionViolations(manifests)
        assertEquals(1, violations.size)
        assertEquals(":net", violations.single().module)
    }

    // ---- R-1001 — the packaged APK must really contain the sherpa-onnx native libraries ---------
    // Unlike every check above, this one reads a real build artifact (the APK's own zip entries),
    // not a declared coordinate or manifest string — see PlatformGuards's own KDoc update and this
    // package's WPJ build report for why: R-1001 was invisible to every declared-artifact proxy in
    // this file, because the dependency coordinate was declared and resolved correctly; only the
    // native library it calls at runtime was never packaged.

    @Test
    fun `R_1001 every required native library present for every required ABI is not reported`() {
        val entries = setOf(
            "lib/arm64-v8a/libsherpa-onnx-jni.so",
            "lib/arm64-v8a/libonnxruntime.so",
            "lib/x86_64/libsherpa-onnx-jni.so",
            "lib/x86_64/libonnxruntime.so",
            "classes.dex",
        )
        assertTrue(PlatformGuards.missingNativeLibraryViolations(entries).isEmpty())
    }

    @Test
    fun `R_1001 a missing native library for one ABI is reported by its exact lib path`() {
        val entries = setOf(
            "lib/arm64-v8a/libsherpa-onnx-jni.so",
            "lib/arm64-v8a/libonnxruntime.so",
            "lib/x86_64/libsherpa-onnx-jni.so",
            // x86_64/libonnxruntime.so missing
        )
        val violations = PlatformGuards.missingNativeLibraryViolations(entries)
        assertEquals(1, violations.size)
        assertEquals("lib/x86_64/libonnxruntime.so", violations.single().path)
    }

    @Test
    fun `R_1001 discrimination — an APK with none of the native libraries reports every combination`() {
        val violations = PlatformGuards.missingNativeLibraryViolations(emptySet())
        assertEquals(4, violations.size)
        assertEquals(
            setOf(
                "lib/arm64-v8a/libsherpa-onnx-jni.so",
                "lib/arm64-v8a/libonnxruntime.so",
                "lib/x86_64/libsherpa-onnx-jni.so",
                "lib/x86_64/libonnxruntime.so",
            ),
            violations.map { it.path }.toSet(),
        )
    }

    @Test
    fun `R_1001 the win-x64 desktop native jar's own dll resources are not confused with an android lib`() {
        // The original defect (register R-1001): the win-x64 jar's DLLs land at the APK ROOT under
        // sherpa-onnx/native/win-x64/*.dll, never under lib/<abi>/ — an APK carrying only those must
        // still report every android lib/<abi>/*.so path as missing, not be fooled into thinking
        // "some sherpa-onnx native file is present" is good enough.
        val entries = setOf(
            "sherpa-onnx/native/win-x64/onnxruntime.dll",
            "sherpa-onnx/native/win-x64/sherpa-onnx-jni.dll",
        )
        assertEquals(4, PlatformGuards.missingNativeLibraryViolations(entries).size)
    }

    // ---- NativeLibraryPackagingGuardTask — the Gradle task itself, not just the pure function ----
    // R-1001 build report: a first version of this task relied on
    // `requiredAbis.getOrElse(PlatformGuards.REQUIRED_NATIVE_LIBRARY_ABIS)` for its defaults and
    // silently checked *zero* required libraries against every real APK, because Gradle
    // auto-initializes a managed `ListProperty` to an empty list rather than leaving it absent —
    // `getOrElse` only falls back when a property is genuinely unset, and an empty-but-present list
    // is not that. Confirmed directly against a real built APK missing every required native
    // library, which this bug reported "OK" for. These two tests are the one place in this package
    // a Gradle task class itself, not just the pure object behind it, is exercised — necessary
    // because the defect lived entirely in Gradle's own property-default wiring, invisible to any
    // test of [PlatformGuards] alone.

    private fun writeZip(dir: File, entries: List<String>): File {
        val file = File(dir, "app-debug.apk")
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `AC discrimination — the task's own required-list conventions apply with no explicit configuration`(
        @TempDir dir: File,
    ) {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.create("verifySherpaNativeLibrariesPackaged", NativeLibraryPackagingGuardTask::class.java)
        // Deliberately NOT setting requiredAbis/requiredFiles — the whole point of this test.
        task.apkFile.set(
            writeZip(
                dir,
                listOf(
                    "lib/arm64-v8a/libsherpa-onnx-jni.so",
                    "lib/arm64-v8a/libonnxruntime.so",
                    "lib/x86_64/libsherpa-onnx-jni.so",
                    "lib/x86_64/libonnxruntime.so",
                ),
            ),
        )

        task.check() // must not throw — every required library/ABI combination is present

        assertEquals(
            listOf("arm64-v8a", "x86_64"),
            task.requiredAbis.get(),
            "the task's own convention, not an empty Gradle-managed default, must supply the ABIs",
        )
    }

    @Test
    fun `AC discrimination — the task fails a real APK missing every required native library`(
        @TempDir dir: File,
    ) {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.create("verifySherpaNativeLibrariesPackaged", NativeLibraryPackagingGuardTask::class.java)
        task.apkFile.set(writeZip(dir, listOf("classes.dex", "lib/arm64-v8a/libsqliteJni.so")))

        assertThrows(org.gradle.api.GradleException::class.java) { task.check() }
    }
}
