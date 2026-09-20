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

    // ---- P23 (Play-readiness) — a declared FOREGROUND_SERVICE_<TYPE> permission needs a matching
    // android:foregroundServiceType on some <service> in the same module's manifest -------------

    @Test
    fun `P23 a microphone FGS permission with a matching service type is not reported`() {
        val manifests = mapOf(
            ":capture-android" to """
                <manifest>
                    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
                    <application>
                        <service android:name=".CaptureService" android:foregroundServiceType="microphone" />
                    </application>
                </manifest>
            """.trimIndent(),
        )
        assertTrue(PlatformGuards.fgsTypeDeclarationViolations(manifests).isEmpty())
    }

    @Test
    fun `P23 a declared FGS permission with no matching service type is reported`() {
        val manifests = mapOf(
            ":capture-android" to """
                <manifest>
                    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
                    <application>
                        <service android:name=".CaptureService" />
                    </application>
                </manifest>
            """.trimIndent(),
        )
        val violations = PlatformGuards.fgsTypeDeclarationViolations(manifests)
        assertEquals(1, violations.size)
        assertEquals(":capture-android", violations.single().module)
        assertTrue(violations.single().reason.contains("microphone"))
    }

    @Test
    fun `P23 no FGS permission declared at all is not reported`() {
        val manifests = mapOf(":core" to "<manifest />")
        assertTrue(PlatformGuards.fgsTypeDeclarationViolations(manifests).isEmpty())
    }

    @Test
    fun `P23 a dataSync FGS permission is checked independently of microphone`() {
        val manifests = mapOf(
            ":pipeline" to """
                <manifest>
                    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
                    <application>
                        <service android:name=".ReprocessService" android:foregroundServiceType="dataSync" />
                    </application>
                </manifest>
            """.trimIndent(),
        )
        assertTrue(PlatformGuards.fgsTypeDeclarationViolations(manifests).isEmpty())
    }

    @Test
    fun `P23 a service type value that only partially matches as a substring is not fooled`() {
        // "dataSync" must not be satisfied by a type value where it is merely a substring of a
        // longer, unrelated token with no word boundary around it (e.g. a typo'd custom value) —
        // only a real, word-bounded "dataSync" (optionally pipe-separated with another type, the
        // real multi-type manifest syntax) counts.
        val manifests = mapOf(
            ":pipeline" to """
                <manifest>
                    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
                    <application>
                        <service android:name=".X" android:foregroundServiceType="xdataSyncy" />
                    </application>
                </manifest>
            """.trimIndent(),
        )
        val violations = PlatformGuards.fgsTypeDeclarationViolations(manifests)
        assertEquals(1, violations.size)
    }

    @Test
    fun `P23 a real multi-type pipe-separated value is recognised`() {
        val manifests = mapOf(
            ":pipeline" to """
                <manifest>
                    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
                    <application>
                        <service android:name=".X" android:foregroundServiceType="camera|dataSync" />
                    </application>
                </manifest>
            """.trimIndent(),
        )
        assertTrue(PlatformGuards.fgsTypeDeclarationViolations(manifests).isEmpty())
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

    // ---- Signing-stability guard — debug-fix session, 2026-09-19 (reworked after coordinator ---
    // review: no keystore may be committed to this public repository) ---------------------------
    // Operator report, currently released build: install fails on-device with "App not installed.
    // Package appears to be invalid." The published `latest-build` APK (commit 152a9283) was
    // downloaded via `gh release download`, hash-verified byte for byte against the release
    // asset, and installed successfully via `adb install` AND `pm install` from a local file on
    // three real Android package-manager instances (API 34 x86_64; genuine Android 16/API 36 at
    // both 4 KB and 16 KB page size) — so the artifact itself is not structurally invalid, not
    // Zip64, correctly zipaligned, and validly v2-signed.
    //
    // Root cause found by direct reproduction, not inference: `release.yml` used to sign with
    // AGP's own auto-generated `~/.android/debug.keystore` — freshly created on every GitHub
    // Actions run, since the runner is a new VM each time and nothing seeded or cached it.
    // Downloading `v0.1.1` and a `latest-build` and running `apksigner verify --print-certs` on
    // both showed two *different* certificate SHA-256 digests for the same `org.ort.app` package.
    // Resigning a published APK's own bytes with a second, different debug-style key and
    // installing it over the first with `adb install -r` on a real device reproduces
    // `INSTALL_FAILED_UPDATE_INCOMPATIBLE: ... signatures do not match` — exactly the situation
    // any operator hits updating from a previously installed build to a new one. Stock Android's
    // own Package Installer does not give this failure a distinct message on many OS/OEM builds;
    // it falls back to the same generic "Package appears to be invalid" text
    // INSTALL_FAILED_INVALID_APK/INSTALL_PARSE_FAILED_* produce, so the operator's report and this
    // mechanism are consistent — the reproduction above rules out the artifact being literally
    // malformed, leaving signing-key instability as the mechanism this test suite guards against.
    //
    // The fix's first version pinned a constant certificate computed from a keystore checked into
    // the repository — rejected on review (a public repo must never carry a private signing key).
    // [PlatformGuards.signingStabilityViolations] now takes the expected digest as a plain nullable
    // parameter with an explicit [enforceExpectedCertificate] switch, so a local build (which never
    // passes a real pin, and is never signed with one) cannot fail this check — only `release.yml`,
    // which does both, can.
    @Test
    fun `signing-stability guard — enforcement off means a mismatched or absent pin never fails a build`() {
        assertTrue(
            PlatformGuards.signingStabilityViolations(
                verified = true,
                hasV2OrV3Scheme = true,
                certificateSha256 = "whatever-a-local-machines-own-debug-key-produces",
                expectedCertificateSha256 = null,
                enforceExpectedCertificate = false,
            ).isEmpty(),
            "a plain local build (AGP's own per-machine debug key, no enforcement requested) must never fail here",
        )
    }

    @Test
    fun `signing-stability guard — enforcement on with no pin configured yet is reported, naming what to do`() {
        val violations = PlatformGuards.signingStabilityViolations(
            verified = true,
            hasV2OrV3Scheme = true,
            certificateSha256 = "abc123",
            expectedCertificateSha256 = null,
            enforceExpectedCertificate = true,
        )
        assertEquals(1, violations.size)
        assertTrue(violations.single().reason.contains("release-certificate.sha256"))
        assertTrue(violations.single().reason.contains("UNSET"))
    }

    @Test
    fun `signing-stability guard — enforcement on with a blank pin is treated the same as unconfigured`() {
        val violations = PlatformGuards.signingStabilityViolations(
            verified = true,
            hasV2OrV3Scheme = true,
            certificateSha256 = "abc123",
            expectedCertificateSha256 = "   ",
            enforceExpectedCertificate = true,
        )
        assertEquals(1, violations.size)
        assertTrue(violations.single().reason.contains("no digest is configured"))
    }

    @Test
    fun `signing-stability guard — enforcement on with a matching certificate is not reported`() {
        assertTrue(
            PlatformGuards.signingStabilityViolations(
                verified = true,
                hasV2OrV3Scheme = true,
                certificateSha256 = "abc123",
                expectedCertificateSha256 = "ABC123",
                enforceExpectedCertificate = true,
            ).isEmpty(),
            "the comparison must be case-insensitive",
        )
    }

    @Test
    fun `signing-stability guard — apksig reporting unverified is reported regardless of enforcement`() {
        val violations = PlatformGuards.signingStabilityViolations(
            verified = false,
            hasV2OrV3Scheme = true,
            certificateSha256 = "abc123",
            expectedCertificateSha256 = null,
            enforceExpectedCertificate = false,
        )
        assertEquals(1, violations.size)
        assertTrue(violations.single().reason.contains("not installably signed"))
    }

    @Test
    fun `signing-stability guard — no v2 or v3 scheme is reported regardless of enforcement (v1-only or unsigned fails on minSdk 26)`() {
        val violations = PlatformGuards.signingStabilityViolations(
            verified = true,
            hasV2OrV3Scheme = false,
            certificateSha256 = "abc123",
            expectedCertificateSha256 = null,
            enforceExpectedCertificate = false,
        )
        assertEquals(1, violations.size)
        assertTrue(violations.single().reason.contains("v2/v3"))
    }

    @Test
    fun `signing-stability guard — enforcement on with no readable certificate is reported`() {
        val violations = PlatformGuards.signingStabilityViolations(
            verified = true,
            hasV2OrV3Scheme = true,
            certificateSha256 = null,
            expectedCertificateSha256 = "abc123",
            enforceExpectedCertificate = true,
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
            enforceExpectedCertificate = true,
        )
        assertEquals(1, violations.size)
        assertTrue(violations.single().reason.contains("deadbeef"))
        assertTrue(violations.single().reason.contains("cafef00d"))
    }

    // ---- SigningStabilityGuardTask — reads a real APK's real signature, via apksig ------------
    // No checked-in keystore exists any more (the whole point of this rework) — each test below
    // generates its own throwaway keystore with `keytool` (every environment building this project
    // already has one under JAVA_HOME/bin), signs a tiny real zip with apksig's own `ApkSigner`,
    // and reads the *real* certificate digest straight from the keystore for the assertion, the
    // same value `SigningStabilityGuardTask` itself computes from the signed APK via apksig.

    private fun generateKeystore(dir: File, alias: String, password: String, name: String = "$alias.keystore"): File {
        val keystore = File(dir, name)
        val javaHome = System.getProperty("java.home")
        val keytool = File(javaHome, if (System.getProperty("os.name").startsWith("Windows")) "bin/keytool.exe" else "bin/keytool")
        val proc = ProcessBuilder(
            keytool.path, "-genkeypair", "-keystore", keystore.path, "-storetype", "PKCS12",
            "-storepass", password, "-alias", alias, "-keypass", password,
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650", "-dname", "CN=$alias",
        ).redirectErrorStream(true).start()
        proc.inputStream.readBytes()
        check(proc.waitFor() == 0) { "keytool failed generating $keystore" }
        return keystore
    }

    private fun certificateSha256Of(keystore: File, alias: String, password: String): String {
        val ks = java.security.KeyStore.getInstance("PKCS12")
        java.io.FileInputStream(keystore).use { ks.load(it, password.toCharArray()) }
        val certificate = ks.getCertificate(alias) as java.security.cert.X509Certificate
        return java.security.MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }
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
    fun `AC discrimination — the task never fails a real APK when enforcement is off, whatever the pin says`(
        @TempDir dir: File,
    ) {
        val keystore = generateKeystore(dir, "local-debug", "local-debug")
        val signed = signWithKeystore(tinyZip(dir), keystore, "local-debug", "local-debug", dir)

        val project = ProjectBuilder.builder().build()
        val task = project.tasks.create("verifyReleaseSigningStability", SigningStabilityGuardTask::class.java)
        task.apkFile.set(signed)
        // Deliberately NOT setting enforceExpectedCertificate (its convention is false) and
        // deliberately setting a pin that does NOT match this build's own certificate — the exact
        // shape of a plain local `assembleFullDebug` once a real pin is configured for CI.
        task.pinnedCertificateSha256.set("not-this-builds-certificate-at-all")

        task.check() // must not throw
    }

    @Test
    fun `AC discrimination — the task accepts a real APK whose certificate matches the enforced pin`(
        @TempDir dir: File,
    ) {
        val keystore = generateKeystore(dir, "release-key", "release-pass")
        val digest = certificateSha256Of(keystore, "release-key", "release-pass")
        val signed = signWithKeystore(tinyZip(dir), keystore, "release-key", "release-pass", dir)

        val project = ProjectBuilder.builder().build()
        val task = project.tasks.create("verifyReleaseSigningStability", SigningStabilityGuardTask::class.java)
        task.apkFile.set(signed)
        task.pinnedCertificateSha256.set(digest)
        task.enforceExpectedCertificate.set(true)

        task.check() // must not throw
    }

    // ---- P24 fix (register, Wave G batch gate, FR-AST-13, AC-190) — `play` must never bundle any
    // model asset, `full` must bundle every one. Like R-1001 above, this reads a real packaged
    // APK's zip entries rather than a declared coordinate: nothing about bundled-assets.json or
    // either flavor's build.gradle.kts config says which physical directory fetchBundledAssets
    // actually wrote its output to — that is exactly how the defect (`play` packaging the identical
    // 628 MB `full` did) escaped every existing declared-artifact check in this file. ----------

    @Test
    fun `FR_AST_13 AC_190 the full variant with bundled assets present is not reported`() {
        val entries = setOf(
            "classes.dex",
            "assets/bundled/manifest.json",
            "assets/bundled/models/whisper-tiny-en-int8/tiny.en-encoder.int8.onnx",
        )
        assertTrue(PlatformGuards.bundledAssetPackagingViolations(entries, expectBundled = true).isEmpty())
    }

    @Test
    fun `FR_AST_13 AC_190 discrimination — the full variant with no bundled assets at all is reported`() {
        val entries = setOf("classes.dex", "assets/licenses/whisper.txt")
        val violations = PlatformGuards.bundledAssetPackagingViolations(entries, expectBundled = true)
        assertEquals(1, violations.size)
        assertTrue(violations.single().reason.contains("FR-AST-3"))
    }

    @Test
    fun `FR_AST_13 AC_190 the play variant with no bundled assets is not reported`() {
        val entries = setOf("classes.dex", "assets/licenses/whisper.txt")
        assertTrue(PlatformGuards.bundledAssetPackagingViolations(entries, expectBundled = false).isEmpty())
    }

    @Test
    fun `FR_AST_13 AC_190 discrimination — the play variant with a bundled model asset is reported by its exact path`() {
        // This is the Wave G batch gate's own defect, reproduced directly: `play`'s packaged APK
        // carrying the same assets/bundled/ entries `full` does.
        val entries = setOf(
            "classes.dex",
            "assets/bundled/manifest.json",
            "assets/bundled/models/llm/gemma3-1b-it-int4.task",
        )
        val violations = PlatformGuards.bundledAssetPackagingViolations(entries, expectBundled = false)
        assertEquals(2, violations.size)
        assertEquals(
            setOf("assets/bundled/manifest.json", "assets/bundled/models/llm/gemma3-1b-it-int4.task"),
            violations.map { it.path }.toSet(),
        )
        assertTrue(violations.all { it.reason.contains("FR-AST-13") })
    }

    // ---- BundledAssetPackagingGuardTask — the Gradle task itself, real-APK end to end -------------

    @Test
    fun `AC discrimination — the full guard task passes a real APK that bundles assets`(@TempDir dir: File) {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.create(
            "verifyFullBundledAssetPackagingBoundary",
            BundledAssetPackagingGuardTask::class.java,
        )
        task.apkFile.set(writeZip(dir, listOf("classes.dex", "assets/bundled/manifest.json")))
        task.expectBundled.set(true)

        task.check() // must not throw
    }

    @Test
    fun `AC discrimination — the task fails a real APK whose certificate does not match the enforced pin`(
        @TempDir dir: File,
    ) {
        // A second, different self-signed key — models exactly the register's own reproduction: a
        // different CI run's freshly auto-generated debug.keystore, or (post-rework) a release
        // build whose certificate has genuinely drifted from the pinned digest.
        val keystore = generateKeystore(dir, "release-key", "release-pass")
        val otherKeystore = generateKeystore(dir, "other-key", "other-pass")
        val pinnedDigest = certificateSha256Of(keystore, "release-key", "release-pass")
        val signed = signWithKeystore(tinyZip(dir), otherKeystore, "other-key", "other-pass", dir)

        val project = ProjectBuilder.builder().build()
        val task = project.tasks.create("verifyReleaseSigningStability", SigningStabilityGuardTask::class.java)
        task.apkFile.set(signed)
        task.pinnedCertificateSha256.set(pinnedDigest)
        task.enforceExpectedCertificate.set(true)

        assertThrows(org.gradle.api.GradleException::class.java) { task.check() }
    }

    @Test
    fun `AC discrimination — the play guard task fails a real APK that bundles assets`(@TempDir dir: File) {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.create(
            "verifyPlayBundledAssetPackagingBoundary",
            BundledAssetPackagingGuardTask::class.java,
        )
        task.apkFile.set(writeZip(dir, listOf("classes.dex", "assets/bundled/manifest.json")))
        task.expectBundled.set(false)

        val thrown = assertThrows(org.gradle.api.GradleException::class.java) { task.check() }
        assertTrue(thrown.message!!.contains("assets/bundled/manifest.json"))
    }

    @Test
    fun `AC discrimination — the play guard task passes a real APK with no bundled assets`(@TempDir dir: File) {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.create(
            "verifyPlayBundledAssetPackagingBoundary",
            BundledAssetPackagingGuardTask::class.java,
        )
        task.apkFile.set(writeZip(dir, listOf("classes.dex", "assets/licenses/whisper.txt")))
        task.expectBundled.set(false)

        task.check() // must not throw
    }
}
