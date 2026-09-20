package org.ort.gradle

import com.android.apksig.ApkVerifier
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Audit F-027 — fails the build on a dependency coordinate or manifest declaration the design
 * does not permit, structurally, the same way [DependencyRulesTask] enforces the module graph
 * (constitution VII). See [PlatformGuards]'s KDoc for exactly what each check does and does not
 * prove: a declared coordinate or a manifest string, never observed runtime behaviour.
 */
abstract class PlatformGuardsTask : DefaultTask() {

    @get:Input
    abstract val externalDependencies: MapProperty<String, List<String>>

    @get:Input
    abstract val manifestTexts: MapProperty<String, String>

    @TaskAction
    fun check() {
        val deps = externalDependencies.get().mapValues { it.value.toSet() }
        val manifests = manifestTexts.get()

        val telemetry = PlatformGuards.telemetryViolations(deps)
        val httpClient = PlatformGuards.httpClientViolations(deps)
        val internet = PlatformGuards.internetPermissionViolations(manifests)
        val missingInternet = PlatformGuards.missingInternetPermissionViolations(manifests)
        val fgsTypes = PlatformGuards.fgsTypeDeclarationViolations(manifests)

        logger.lifecycle(
            "platformGuards: checked ${deps.size} modules' external dependencies and " +
                "${manifests.size} manifests — no analytics/telemetry SDK, no HTTP client outside :net, " +
                "android.permission.INTERNET declared by :net and only :net, every declared " +
                "FOREGROUND_SERVICE_<TYPE> permission has a matching foregroundServiceType " +
                "(FR-OBS-5, NFR-6, AC-59, audit F-008, P23 Play-readiness).",
        )

        if (telemetry.isNotEmpty() || httpClient.isNotEmpty() || internet.isNotEmpty() ||
            missingInternet.isNotEmpty() || fgsTypes.isNotEmpty()
        ) {
            throw GradleException(
                buildString {
                    appendLine("Platform guard violation(s) — audit F-008/F-027, P23:")
                    telemetry.forEach { appendLine("  ${it.module} -> ${it.coordinate}   (${it.reason})") }
                    httpClient.forEach { appendLine("  ${it.module} -> ${it.coordinate}   (${it.reason})") }
                    internet.forEach { appendLine("  ${it.module}   (${it.reason})") }
                    missingInternet.forEach { appendLine("  ${it.module}   (${it.reason})") }
                    fgsTypes.forEach { appendLine("  ${it.module}   (${it.reason})") }
                    appendLine()
                    appendLine(
                        "This is a declared-artifact check, not a runtime traffic capture — it proves " +
                            "nothing about what a build actually sent, only what it could send.",
                    )
                },
            )
        }
        logger.lifecycle("platformGuards: OK.")
    }
}

/**
 * R-1001 — the one guard in this package that reads a real packaged APK rather than a declared
 * coordinate or manifest string. See [PlatformGuards.missingNativeLibraryViolations]'s own KDoc
 * for why it lives apart from [PlatformGuardsTask]/the root `platformGuards` aggregate: that task
 * (wired in the root `build.gradle.kts`, outside this package's ownership) runs from
 * `dependencyRules platformGuards build` before any variant has been assembled, so it has no APK
 * to read. This task instead runs from `ort.android-app.gradle.kts` (WPJ-owned), wired to run
 * after `assembleDebug` — see that file's own registration and its dependency comment for exactly
 * where in the task graph it sits (`:app:check`/`:app:build`, which the root `build` task already
 * reaches via `dependsOn(subprojects.map { "${it.path}:check" })`).
 */
abstract class NativeLibraryPackagingGuardTask : DefaultTask() {

    /** The packaged APK to inspect — a real build output, not a declared input like every other
     * guard in this package (see this class's own KDoc). */
    @get:InputFile
    abstract val apkFile: RegularFileProperty

    // Gradle auto-initializes a managed ListProperty to an empty list rather than leaving it
    // absent, so `.getOrElse(default)` in `check()` below would never fall back to `default` --
    // `.isPresent` is already `true` with an empty value the moment the task object is created,
    // before any registration block runs (found by direct verification: a first version relying on
    // `getOrElse` silently checked zero required libraries and reported every APK "OK", including
    // one built with the fetch task excluded and confirmed by `python -c "zipfile..."` to be
    // missing every required entry — see this package's WPJ build report). The convention below is
    // what actually supplies the default; `getOrElse` in `check()` is kept only as a second,
    // harmless line of defense.
    @get:Input
    @get:Optional
    abstract val requiredAbis: ListProperty<String>

    @get:Input
    @get:Optional
    abstract val requiredFiles: ListProperty<String>

    init {
        requiredAbis.convention(PlatformGuards.REQUIRED_NATIVE_LIBRARY_ABIS)
        requiredFiles.convention(PlatformGuards.REQUIRED_NATIVE_LIBRARY_FILES)
    }

    @TaskAction
    fun check() {
        val apk = apkFile.get().asFile
        val entryPaths: Set<String> = ZipFile(apk).use { zip ->
            zip.entries().asSequence().map { it.name }.toSet()
        }
        val violations = PlatformGuards.missingNativeLibraryViolations(
            apkEntryPaths = entryPaths,
            requiredAbis = requiredAbis.getOrElse(PlatformGuards.REQUIRED_NATIVE_LIBRARY_ABIS),
            requiredFiles = requiredFiles.getOrElse(PlatformGuards.REQUIRED_NATIVE_LIBRARY_FILES),
        )
        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Native library packaging violation(s) — register R-1001, checked ${apk.path}:")
                    violations.forEach { appendLine("  ${it.path}   (${it.reason})") }
                },
            )
        }
        logger.lifecycle(
            "verifySherpaNativeLibrariesPackaged: OK — ${apk.path} contains every required native " +
                "library for every required ABI (register R-1001).",
        )
    }
}

/**
 * Debug-fix session (2026-09-19, reworked after coordinator review) — reads the real assembled
 * APK's real signer certificate, one of the exceptions [PlatformGuards]'s class KDoc names
 * alongside [NativeLibraryPackagingGuardTask] and [BundledAssetPackagingGuardTask]. Uses
 * `com.android.apksig` (the library `apksigner`/AGP's own signing pipeline are themselves built
 * from) rather than shelling out to the SDK's `apksigner` binary or hand-parsing the APK Signing
 * Block v2/v3 format — no assumption about SDK layout or OS-specific executable name, and no
 * re-implementation of a security-sensitive binary format this project does not own.
 *
 * See [PlatformGuards.signingStabilityViolations]'s own KDoc for the full defect account this
 * guard closes, and for why [expectedCertificateSha256] carries no source-code default (a checked-
 * in private signing key was rejected on review) and [enforceExpectedCertificate] defaults to
 * `false` (a plain local `assembleFullDebug`, signed with AGP's own per-machine debug key, must
 * never fail this check).
 */
abstract class SigningStabilityGuardTask : DefaultTask() {

    /** The packaged APK to inspect — a real build output, same as [NativeLibraryPackagingGuardTask]. */
    @get:InputFile
    abstract val apkFile: RegularFileProperty

    /** The pinned digest from `buildSrc/signing/release-certificate.sha256` (or the
     * `ortReleaseCertificateSha256` override) — absent/blank means "not configured yet" and is
     * only itself a violation when [enforceExpectedCertificate] is `true`. */
    @get:Input
    @get:Optional
    abstract val pinnedCertificateSha256: Property<String>

    /** `true` only for `release.yml`'s own invocation (`-PortEnforcePinnedReleaseSigning=true`),
     * right after it has built with the injected release-signing secrets — see this class's own
     * KDoc. Defaults to `false` so every other invocation (a plain local build, CI's `android`
     * job) never fails on a certificate that was never supposed to match. */
    @get:Input
    @get:Optional
    abstract val enforceExpectedCertificate: Property<Boolean>

    init {
        enforceExpectedCertificate.convention(false)
    }

    @TaskAction
    fun check() {
        val apk = apkFile.get().asFile
        // Pinning both bounds tells apksig which schemes to check without it needing to read
        // AndroidManifest.xml for a minSdkVersion — this project only ever ships minSdk 26
        // (`ort.android-app.gradle.kts`'s own `defaultConfig.minSdk = 26`), so both bounds are
        // that single, real, already-declared value.
        val result = ApkVerifier.Builder(apk)
            .setMinCheckedPlatformVersion(MIN_SDK_VERSION)
            .setMaxCheckedPlatformVersion(MIN_SDK_VERSION)
            .build()
            .verify()
        val certificateSha256 = result.signerCertificates.firstOrNull()?.let { certificate ->
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") {
                "%02x".format(it)
            }
        }
        val violations = PlatformGuards.signingStabilityViolations(
            verified = result.isVerified,
            hasV2OrV3Scheme = result.isVerifiedUsingV2Scheme || result.isVerifiedUsingV3Scheme,
            certificateSha256 = certificateSha256,
            expectedCertificateSha256 = pinnedCertificateSha256.orNull,
            enforceExpectedCertificate = enforceExpectedCertificate.getOrElse(false),
        )
        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Signing-stability violation(s) — debug-fix session 2026-09-19, checked ${apk.path}:")
                    violations.forEach { appendLine("  ${it.reason}") }
                    appendLine()
                    appendLine(
                        "An operator installing this artifact over any previously installed build of " +
                            "org.ort.app signed with a different certificate will see " +
                            "INSTALL_FAILED_UPDATE_INCOMPATIBLE, which many devices show as \"App not " +
                            "installed. Package appears to be invalid.\" — see RELEASING.md.",
                    )
                },
            )
        }
        logger.lifecycle(
            "verifyReleaseSigningStability: OK — ${apk.path} is verified and v2/v3-signed" +
                if (enforceExpectedCertificate.getOrElse(false)) {
                    ", and matches the pinned release certificate."
                } else {
                    " (pinned-certificate enforcement not requested for this invocation)."
                },
        )
    }

    private companion object {
        /** This project's one and only minSdk (`ort.android-app.gradle.kts`'s own
         * `defaultConfig.minSdk`) — pinned as both bounds so apksig checks exactly the schemes a
         * real minSdk-26 device needs without reading AndroidManifest.xml for it. */
        const val MIN_SDK_VERSION = 26
    }
}

/**
 * P24 fix (register, Wave G batch gate, FR-AST-13, AC-190): reads a real packaged debug APK per
 * flavor to prove [PlatformGuards.bundledAssetPackagingViolations]'s boundary actually holds —
 * `full` bundles every asset, `play` bundles none — the same real-artifact-not-declared-coordinate
 * shape [NativeLibraryPackagingGuardTask] established for R-1001. Unlike that task, this one is
 * NOT symmetric across flavors by design: it exists precisely because `full` and `play` MUST
 * differ here, so `ort.android-app.gradle.kts` registers one instance per flavor with
 * [expectBundled] set the opposite way and wires both into `:app:check` — checking only one
 * variant's APK would prove nothing about the boundary the other side of this fix depends on.
 */
abstract class BundledAssetPackagingGuardTask : DefaultTask() {

    /** The packaged APK to inspect — a real build output, not a declared input, same as
     * [NativeLibraryPackagingGuardTask.apkFile]. */
    @get:InputFile
    abstract val apkFile: RegularFileProperty

    /** `true` for the `full`-flavor instance, `false` for the `play`-flavor instance — see this
     * class's own KDoc for why both are registered rather than one shared task. */
    @get:Input
    abstract val expectBundled: Property<Boolean>

    @TaskAction
    fun check() {
        val apk = apkFile.get().asFile
        val entryPaths: Set<String> = ZipFile(apk).use { zip ->
            zip.entries().asSequence().map { it.name }.toSet()
        }
        val violations = PlatformGuards.bundledAssetPackagingViolations(entryPaths, expectBundled.get())
        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine(
                        "Bundled-asset packaging boundary violation(s) — FR-AST-13, AC-190, checked ${apk.path}:",
                    )
                    violations.forEach { appendLine("  ${it.path}   (${it.reason})") }
                },
            )
        }
        logger.lifecycle(
            "verifyBundledAssetPackagingBoundary: OK — ${apk.path} " +
                (if (expectBundled.get()) "bundles" else "does not bundle") +
                " model assets as expected for this flavor (FR-AST-13, AC-190).",
        )
    }
}
