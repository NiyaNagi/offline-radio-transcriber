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

    // ---- Signing-stability guard — debug-fix session, 2026-09-19 -----------------------------
    // Operator report, currently released build: install fails on-device with "App not installed.
    // Package appears to be invalid." The published `latest-build` APK (commit 152a9283) was
    // downloaded via `gh release download`, hash-verified byte for byte against the release
    // asset, and installed successfully via `adb install` AND `pm install` from a local file on
    // three real Android package-manager instances (API 34 x86_64; genuine Android 16/API 36 at
    // both 4 KB and 16 KB page size) — so the artifact itself is not structurally invalid, not
    // Zip64, correctly zipaligned, and validly v2-signed.
    //
    // Root cause found by direct reproduction, not inference: `release.yml` never pins a signing
    // key, so `:app:assembleDebug` signs with AGP's own auto-generated `~/.android/debug.keystore`
    // — freshly created on every GitHub Actions run, since the runner is a new VM each time and
    // nothing seeds or caches it. Downloading `v0.1.1` and the current `latest-build` and running
    // `apksigner verify --print-certs` on both shows two *different* certificate SHA-256 digests
    // for the same `org.ort.app` package. Resigning the published APK's own bytes with a second,
    // different debug-style key and installing it over the first with `adb install -r` on a real
    // device reproduces `INSTALL_FAILED_UPDATE_INCOMPATIBLE: ... signatures do not match` —
    // exactly the situation any operator hits updating from a previously installed build (an
    // earlier `latest-build`, or the tagged `v0.1.1`) to a new one. Stock Android's own Package
    // Installer does not give this failure a distinct message on many OS/OEM builds; it falls back
    // to the same generic "Package appears to be invalid" text INSTALL_FAILED_INVALID_APK/
    // INSTALL_PARSE_FAILED_* produce, so the operator's report and this mechanism are consistent —
    // the emulator reproduction above rules out the artifact being literally malformed, leaving
    // signing-key instability as the mechanism this test suite guards against for every future
    // build.
    @Test
    fun `signing-stability guard — a matching certificate and v2 or v3 scheme is not reported`() {
        assertTrue(
            PlatformGuards.signingStabilityViolations(
                verified = true,
                hasV2OrV3Scheme = true,
                certificateSha256 = PlatformGuards.PINNED_ROLLING_RELEASE_CERTIFICATE_SHA256,
            ).isEmpty(),
        )
    }

    @Test
    fun `signing-stability guard — apksig reporting unverified is reported`() {
        val violations = PlatformGuards.signingStabilityViolations(
            verified = false,
            hasV2OrV3Scheme = true,
            certificateSha256 = PlatformGuards.PINNED_ROLLING_RELEASE_CERTIFICATE_SHA256,
        )
        assertEquals(1, violations.size)
        assertTrue(violations.single().reason.contains("not installably signed"))
    }

    @Test
    fun `signing-stability guard — no v2 or v3 scheme is reported (v1-only or unsigned fails on minSdk 26)`() {
        val violations = PlatformGuards.signingStabilityViolations(
            verified = true,
            hasV2OrV3Scheme = false,
            certificateSha256 = PlatformGuards.PINNED_ROLLING_RELEASE_CERTIFICATE_SHA256,
        )
        assertEquals(1, violations.size)
        assertTrue(violations.single().reason.contains("v2/v3"))
    }

    @Test
    fun `signing-stability guard — no readable certificate is reported`() {
        val violations = PlatformGuards.signingStabilityViolations(
            verified = true,
            hasV2OrV3Scheme = true,
            certificateSha256 = null,
        )
        assertEquals(1, violations.size)
        assertTrue(violations.single().reason.contains("no signer certificate"))
    }

    @Test
    fun `signing-stability guard discrimination — a certificate that drifted from the pin is reported by both digests`() {
        val violations = PlatformGuards.signingStabilityViolations(
            verified = true,
            hasV2OrV3Scheme = true,
            certificateSha256 = "deadbeef",
            expectedCertificateSha256 = "cafef00d",
        )
        assertEquals(1, violations.size)
        assertTrue(violations.single().reason.contains("deadbeef"))
        assertTrue(violations.single().reason.contains("cafef00d"))
    }

    @Test
    fun `signing-stability guard — the digest comparison is case-insensitive`() {
        assertTrue(
            PlatformGuards.signingStabilityViolations(
                verified = true,
                hasV2OrV3Scheme = true,
                certificateSha256 = PlatformGuards.PINNED_ROLLING_RELEASE_CERTIFICATE_SHA256.uppercase(),
            ).isEmpty(),
        )
    }

    // ---- SigningStabilityGuardTask — reads a real APK's real signature, via apksig ------------
    // Signs a tiny real zip with the project's own pinned keystore (buildSrc/signing/
    // ort-rolling-release.keystore) using apksig's own `ApkSigner`, then verifies the task accepts
    // it against the pin and rejects it once resigned with a different key — the same
    // discrimination the register's own reproduction used (a resigned copy of the same bytes).

    private fun pinnedKeystoreFile(): File =
        File("signing/ort-rolling-release.keystore").let { relativeToBuildSrc ->
            if (relativeToBuildSrc.exists()) {
                relativeToBuildSrc
            } else {
                // buildSrc's own test working directory is buildSrc/ itself when run via Gradle;
                // fall back to an absolute resolution for IDE-run tests whose working dir differs.
                File(System.getProperty("user.dir"), "signing/ort-rolling-release.keystore")
            }
        }

    private fun signWithKeystore(apk: File, keystore: File, alias: String, password: String, outDir: File): File {
        val ks = java.security.KeyStore.getInstance("PKCS12")
        java.io.FileInputStream(keystore).use { ks.load(it, password.toCharArray()) }
        val privateKey = ks.getKey(alias, password.toCharArray()) as java.security.PrivateKey
        val certChain = ks.getCertificateChain(alias).map { it as java.security.cert.X509Certificate }
        val signerConfig = com.android.apksig.ApkSigner.SignerConfig.Builder(alias, privateKey, certChain).build()
        val signed = File(outDir, "signed-${apk.name}")
        com.android.apksig.ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(apk)
            .setOutputApk(signed)
            // The test fixture is a plain zip, not a real APK with an AndroidManifest.xml apksig
            // could read a minSdkVersion from — same reason `app/build.gradle.kts`'s real
            // `minSdk 26` matters here: this project only ever ships minSdk 26, so v2 signing
            // (available since API 24) always applies, matching the real assembled APK's own
            // signing config exactly (`ort.android-app.gradle.kts`'s `defaultConfig.minSdk = 26`).
            .setMinSdkVersion(26)
            .build()
            .sign()
        return signed
    }

    private fun tinyZip(dir: File, name: String = "unsigned.zip"): File {
        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
            // apksig's ApkVerifier requires an "AndroidManifest.xml" entry to *exist* even when
            // both platform-version bounds are pinned explicitly (confirmed directly: it throws
            // ApkFormatException("Missing AndroidManifest.xml") otherwise) — but does not parse
            // its content in that case, only in the unset-bounds path this task never takes (real
            // minSdk is always known and pinned — `ort.android-app.gradle.kts`'s own
            // `defaultConfig.minSdk = 26`). Confirmed directly (standalone apksig repro, outside
            // Gradle/JUnit entirely) that garbage bytes here verify identically to a real manifest.
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()))
            zip.closeEntry()
        }
        return file
    }

    @Test
    fun `AC discrimination — the task accepts a real APK signed with the pinned keystore`(@TempDir dir: File) {
        val keystore = pinnedKeystoreFile()
        org.junit.jupiter.api.Assumptions.assumeTrue(keystore.exists(), "pinned keystore not found at ${keystore.path}")
        val signed = signWithKeystore(tinyZip(dir), keystore, "ort-rolling-release", "ort-rolling-release", dir)

        val project = ProjectBuilder.builder().build()
        val task = project.tasks.create("verifyReleaseSigningStability", SigningStabilityGuardTask::class.java)
        task.apkFile.set(signed)
        task.expectedCertificateSha256.set(PlatformGuards.PINNED_ROLLING_RELEASE_CERTIFICATE_SHA256)

        task.check() // must not throw
    }

    @Test
    fun `AC discrimination — the task fails a real APK signed with a different key than the pin`(@TempDir dir: File) {
        val keystore = pinnedKeystoreFile()
        org.junit.jupiter.api.Assumptions.assumeTrue(keystore.exists(), "pinned keystore not found at ${keystore.path}")

        // A second, different self-signed debug-style key — models exactly the register's own
        // reproduction: a different CI run's freshly auto-generated debug.keystore. Building a
        // minimal self-signed cert directly is not portable across JDKs (no public JDK API
        // generates one; apksig's own test utilities are not exported for reuse), so this second
        // cert is generated the same way apksigner/keytool itself would: shell out to keytool,
        // which every environment building this project already has (JAVA_HOME/bin).
        val otherKeystore = File(dir, "other.keystore")
        val javaHome = System.getProperty("java.home")
        val keytool = File(javaHome, if (System.getProperty("os.name").startsWith("Windows")) "bin/keytool.exe" else "bin/keytool")
        val proc = ProcessBuilder(
            keytool.path, "-genkeypair", "-keystore", otherKeystore.path, "-storetype", "PKCS12",
            "-storepass", "other-pass", "-alias", "other-key", "-keypass", "other-pass",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650", "-dname", "CN=Other Debug Key",
        ).redirectErrorStream(true).start()
        proc.inputStream.readBytes()
        proc.waitFor()

        val signed = signWithKeystore(tinyZip(dir), otherKeystore, "other-key", "other-pass", dir)

        val project = ProjectBuilder.builder().build()
        val task = project.tasks.create("verifyReleaseSigningStability", SigningStabilityGuardTask::class.java)
        task.apkFile.set(signed)
        task.expectedCertificateSha256.set(PlatformGuards.PINNED_ROLLING_RELEASE_CERTIFICATE_SHA256)

        assertThrows(org.gradle.api.GradleException::class.java) { task.check() }
    }
}
