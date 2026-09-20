import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.ort.gradle.BundledAssetCatalogRenderer
import org.ort.gradle.BundledAssetManifest
import org.ort.gradle.BundledAssetPackaging
import org.ort.gradle.BundledAssetPackagingGuardTask
import org.ort.gradle.FetchBundledAssetsTask
import org.ort.gradle.FetchSherpaNativeTask
import org.ort.gradle.NativeLibraryPackagingGuardTask
import org.ort.gradle.PlatformGuards
import org.ort.gradle.PublishModelMirrorTask
import org.ort.gradle.SherpaNativeManifest
import org.ort.gradle.SigningStabilityGuardTask
import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // P13 (D15 — Compose): the Compose compiler is its own Gradle plugin from Kotlin 2.0 on,
    // resolved from buildSrc's own classpath (buildSrc/build.gradle.kts), pinned there to the
    // exact Kotlin version applied above so compiler and language version can never drift apart.
    id("org.jetbrains.kotlin.plugin.compose")
    id("ort.common")
}

// P13: the version catalog (`libs`) defined in the root build's settings.gradle.kts is not
// visible inside a buildSrc precompiled script plugin — buildSrc is a separate build with its
// own classpath (see buildSrc/build.gradle.kts). Coordinates below are therefore hardcoded,
// deliberately kept in exact sync with the `composeBom`/`activityCompose` entries in
// gradle/libs.versions.toml, which is what app/build.gradle.kts (a normal project script, where
// `libs` *is* visible) uses for its own compose-adjacent test dependencies.
val composeBomCoordinate = "androidx.compose:compose-bom:2024.09.03"
val activityComposeCoordinate = "androidx.activity:activity-compose:1.9.3"

extensions.configure<BaseAppModuleExtension> {
    namespace = "org.ort.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.ort.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // R-1001 (register): exactly the two ABIs this project fetches native libraries for
        // (sherpa-native.json, FetchSherpaNativeTask) — arm64-v8a is the operator's phone, x86_64
        // is the audit AVDs (so the tour can exercise real ASR for the first time). Deliberately
        // NOT armeabi-v7a/x86: the APK is already ~600 MB with every bundled model, and this
        // project ships no 32-bit-only device in its device matrix (spec/test-plan.md).
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        // FR-OBS-12: the default every variant gets unless `buildTypes { debug { ... } }` below
        // overrides it — see that block's own comment for why the field must exist for both
        // variants. Blank, never a placeholder that could be mistaken for a real value.
        buildConfigField("String", "FIELD_REPORT_TOKEN", "\"\"")
    }

    // Debug-fix session (2026-09-19, operator report: "App not installed. Package appears to be
    // invalid."): root cause was AGP's own auto-generated `~/.android/debug.keystore`, freshly
    // created on every GitHub Actions run because the runner is a new VM each time — confirmed
    // directly: `apksigner verify --print-certs` on the published `v0.1.1` and a `latest-build`
    // reported two different certificate SHA-256 digests for the same `org.ort.app` package, and
    // resigning a published APK's own bytes with a second key and installing it over the first on
    // a real device reproduced `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
    //
    // **A first version of this fix checked a signing keystore into the repository.** Rejected on
    // review: this repository is public, and a published private key would let anyone sign an APK
    // Android accepts as an update to the real one — the opposite of the guarantee this fix
    // exists to provide. The key now lives ONLY as GitHub Actions secrets
    // (`ORT_RELEASE_KEYSTORE_BASE64` and its three companions — see RELEASING.md's "Signing"
    // section for how the operator generates and sets them), decoded to a temp file by
    // `release.yml` at run time and never committed, printed, or cached. This build script reads
    // that temp file's path and the passwords/alias from environment variables `release.yml` sets
    // for the one step that assembles the published artifact — exactly the same pattern this file
    // already uses for `HF_TOKEN` and `ORT_FIELD_REPORT_TOKEN` below.
    //
    // A **plain local `assembleFullDebug`** (no such environment variables set) gets AGP's own
    // default, per-machine debug keystore, exactly as before this session — this is deliberate
    // (coordinator direction): a local developer build must never fail or behave differently for
    // lacking a secret only CI holds. Only `verifyReleaseSigningStability` below, and only when
    // explicitly told to enforce the pin (`-PortEnforcePinnedReleaseSigning=true`, `release.yml`
    // only), cares whether the certificate actually matches.
    val releaseSigningStoreFile = providers.environmentVariable("ORT_RELEASE_SIGNING_STORE_FILE")
    val releaseSigningStorePassword = providers.environmentVariable("ORT_RELEASE_SIGNING_STORE_PASSWORD")
    val releaseSigningKeyAlias = providers.environmentVariable("ORT_RELEASE_SIGNING_KEY_ALIAS")
    val releaseSigningKeyPassword = providers.environmentVariable("ORT_RELEASE_SIGNING_KEY_PASSWORD")

    if (releaseSigningStoreFile.isPresent) {
        signingConfigs {
            create("release") {
                storeFile = File(releaseSigningStoreFile.get())
                storePassword = releaseSigningStorePassword.orNull
                keyAlias = releaseSigningKeyAlias.orNull
                keyPassword = releaseSigningKeyPassword.orNull
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
        // FR-OBS-12: the field-report upload token, scoped to the one destination repository,
        // present only in a debug build. Injected at build time from the `ORT_FIELD_REPORT_TOKEN`
        // user-scope environment variable exactly as `HF_TOKEN` is (FetchBundledAssetsTask below) —
        // never committed, never printed. The field is declared for both build types (below,
        // `defaultConfig`) because AGP compiles `:app`'s one shared main source set against each
        // variant's own generated `BuildConfig`, so a field that existed only for `debug` would
        // fail `compileReleaseKotlin` the moment any code referenced it — but only this `debug`
        // block ever gives it a real value; a release build always sees the empty-string default,
        // indistinguishable from "not configured" (`RealFieldReportUploadClient`'s own contract).
        // `FieldReportUploadClientFactory` (app/.../fieldreport/upload) additionally gates on
        // `BuildConfig.DEBUG` before ever reading this field, so a release build never constructs a
        // real client regardless.
        getByName("debug") {
            buildConfigField(
                "String",
                "FIELD_REPORT_TOKEN",
                "\"${providers.environmentVariable("ORT_FIELD_REPORT_TOKEN").getOrElse("")}\"",
            )
            // See this block's own top-of-file comment: only present when release.yml has decoded
            // the release-signing secrets into the environment; absent for every local build.
            if (releaseSigningStoreFile.isPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // P13 (D15): Compose lands here so every Android module built on this convention plugin has
    // it available — today that is only `:app`, the reader UI's home.
    buildFeatures {
        compose = true
    }

    // P13: Compose's own UI-testing rule (`createComposeRule`, used by the new tests under
    // ui/) launches its host activity via the debug-only stub `ui-test-manifest` provides
    // (declared debugImplementation below, per upstream guidance — it must never ship in
    // release). `test`/`check`/`build` otherwise also run the *same* test source set against the
    // release variant's merged manifest, which never carries that stub, and those tests fail
    // there for a reason that has nothing to do with the code under test. One unit-test build
    // type, run against the manifest that actually carries what these tests need.
    testBuildType = "debug"
}

extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension> {
    jvmToolchain(17)
}

// P23 (FR-AST-3, FR-AST-13, D43): bundled assets — build-time fetch, per-flavor catalogue
// generation, first-launch verify. Three tasks, deliberately independent of each other:
//
//  - `generateFullBundledAssetCatalog`/`generatePlayBundledAssetCatalog` each read the committed
//    `bundled-assets.json` (root) and emit a plain Kotlin source file into their OWN flavor's
//    kotlin source set (`app/src/full`/`app/src/play` — generated, not checked in) — `ModelCatalog`
//    (app/.../ui/data/ModelsViewData.kt) is built from whichever one a given variant actually
//    compiles, so the app and the build agree by construction (no second, hand-typed catalogue to
//    drift, and no runtime flavor branch needed at the call site: `GeneratedBundledAssetManifest`
//    resolves to a different, flavor-specific definition purely by which source set is on that
//    variant's compile classpath). Rendering itself needs no network and no HF_TOKEN: it only
//    reads the manifest's own committed text ([BundledAssetCatalogRenderer], buildSrc — extracted
//    there, not left as script-local functions, so it is directly unit-tested).
//  - `fetchBundledAssets` ([FetchBundledAssetsTask]) does the real network fetch, verification and
//    packaging into [BundledAssetPackaging.ASSETS_OUTPUT_RELATIVE_PATH] — the `full` flavor's OWN
//    source set, `src/full/assets/bundled/` (gitignored), not the shared `src/main/` — the thing
//    that actually needs HF_TOKEN and needs to run exactly once per verified asset, cached at
//    `$GRADLE_USER_HOME/ort-bundled-assets/` across worktrees. **`full`-flavor only**, and now
//    structurally so (P24 fix, register, Wave G batch gate, FR-AST-13, AC-190): before this fix the
//    destination was `src/main/assets/bundled/`, which every flavor inherits, so `play` (which is
//    supposed to bundle nothing) packaged the identical 628 MB `full` did the moment both had ever
//    been fetched on the same machine — AGP only merges a flavor's own `src/<flavor>/assets/` into
//    that flavor's own variants, so `play`'s own assemble/merge-assets tasks now cannot see this
//    directory at all, regardless of what has been fetched previously on this machine. See
//    [BundledAssetPackaging]'s own KDoc for the full account.
//
// `assembleFullDebug`/`assembleFullRelease` (and therefore `build`, which reaches every variant's
// assemble) depend on `fetchBundledAssets` so a shipping `full` artifact is never produced without
// every bundled asset verified (FR-AST-3). They do NOT depend on the generate tasks explicitly —
// every Kotlin compile task does, below, which assemble already depends on transitively.
val bundledAssetManifestFile = rootProject.layout.projectDirectory.file("bundled-assets.json")
val generatedFullBundledAssetCatalogDir = layout.buildDirectory.dir("generated/ort/bundledAssetCatalog/full/kotlin")
val generatedPlayBundledAssetCatalogDir = layout.buildDirectory.dir("generated/ort/bundledAssetCatalog/play/kotlin")

fun registerCatalogGenerationTask(taskName: String, bundled: Boolean, outputDir: Provider<Directory>) =
    tasks.register(taskName) {
        group = "build"
        description = "Generates GeneratedBundledAssetManifest.kt (bundled=$bundled) from the root " +
            "bundled-assets.json (P23, FR-AST-3, FR-AST-13)."
        inputs.file(bundledAssetManifestFile)
        outputs.dir(outputDir)
        doLast {
            val entries = BundledAssetManifest.parse(bundledAssetManifestFile.asFile.readText())
            val packageDir = outputDir.get().asFile.resolve("org/ort/app/assets")
            packageDir.mkdirs()
            File(packageDir, "GeneratedBundledAssetManifest.kt")
                .writeText(BundledAssetCatalogRenderer.render(entries, bundled = bundled))
        }
    }

val generateFullBundledAssetCatalog = registerCatalogGenerationTask(
    "generateFullBundledAssetCatalog",
    bundled = true,
    outputDir = generatedFullBundledAssetCatalogDir,
)
val generatePlayBundledAssetCatalog = registerCatalogGenerationTask(
    "generatePlayBundledAssetCatalog",
    bundled = false,
    outputDir = generatedPlayBundledAssetCatalogDir,
)

// `afterEvaluate`: the `full`/`play` flavors are declared in app/build.gradle.kts, a normal
// project script whose own body runs AFTER this precompiled script plugin's top-level statements
// finish applying (Gradle applies a `plugins {}`-referenced precompiled plugin's body as part of
// applying the plugin, before the rest of the consuming script's own body executes) — so
// `sourceSets.getByName("full")`/`getByName("play")` would fail here if called eagerly, before
// app/build.gradle.kts's own `productFlavors { create("full") { ... } }` has run. `afterEvaluate`
// runs once the WHOLE project (every applied script, in whatever order) has finished configuring,
// by which point both AGP's and the Kotlin plugin's own flavor source sets are guaranteed to
// exist, regardless of which file declared the flavor.
afterEvaluate {
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension> {
        sourceSets.getByName("full").kotlin.srcDir(generatedFullBundledAssetCatalogDir)
        sourceSets.getByName("play").kotlin.srcDir(generatedPlayBundledAssetCatalogDir)
    }
}

tasks.withType<KotlinCompile>().configureEach {
    dependsOn(generateFullBundledAssetCatalog, generatePlayBundledAssetCatalog)
}

// ktlint/detekt scan every source set's directories directly (not through a KotlinCompile task),
// so they hit the identical implicit-input validation problem the merge*Assets wiring below
// explains — generating the catalog needs no network and costs nothing, so a hard `dependsOn` (not
// `mustRunAfter`) is the right call here, unlike fetchBundledAssets.
tasks.matching { it.name.contains("Ktlint", ignoreCase = true) || it.name.contains("detekt", ignoreCase = true) }
    .configureEach { dependsOn(generateFullBundledAssetCatalog, generatePlayBundledAssetCatalog) }

val fetchBundledAssets = tasks.register<FetchBundledAssetsTask>("fetchBundledAssets") {
    group = "build"
    description = "Fetches, verifies and packages every bundled asset into the full flavor's own " +
        "${BundledAssetPackaging.ASSETS_OUTPUT_RELATIVE_PATH} (WPG, FR-AST-3, FR-AST-13, AC-190)."
    manifestFile.set(bundledAssetManifestFile)
    assetsOutputDir.set(layout.projectDirectory.dir(BundledAssetPackaging.ASSETS_OUTPUT_RELATIVE_PATH))
    cacheRoot.set(layout.dir(providers.provider { gradle.gradleUserHomeDir.resolve("ort-bundled-assets") }))
    hfToken.set(providers.environmentVariable("HF_TOKEN"))
    // The one local-development escape hatch (never set by CI — see .github/workflows and
    // FetchBundledAssetsTask's own KDoc): -PortAllowMissingBundledAssets=true or
    // ORT_ALLOW_MISSING_BUNDLED_ASSETS=1.
    allowMissingBundledAssets.set(
        providers.gradleProperty("ortAllowMissingBundledAssets").map { it.toBoolean() }
            .orElse(providers.environmentVariable("ORT_ALLOW_MISSING_BUNDLED_ASSETS").map { it == "1" })
            .orElse(false),
    )
}

// P24 fix (register, Wave G batch gate, FR-AST-13, AC-190): a developer checkout that built `full`
// before this fix landed may still have real fetched bytes sitting at
// [BundledAssetPackaging.LEGACY_ASSETS_RELATIVE_PATH] (`src/main/assets/bundled/`) — gitignored, so
// invisible to `git status`, but still read by AGP as an implicit input of *every* flavor's own
// asset merge, since it sits under the shared main source set. That is the exact defect this fix
// closes, so a stale copy left over from before the fix must not silently defeat it. Wired ahead of
// every merge-assets task for BOTH flavors (below), not just `full`'s — a `play`-only checkout that
// never runs `fetchBundledAssets` again must still get the stale directory removed once.
val cleanupLegacyBundledAssets = tasks.register("cleanupLegacyBundledAssets") {
    group = "build"
    description = "Deletes a stale app/${BundledAssetPackaging.LEGACY_ASSETS_RELATIVE_PATH}/ left " +
        "over from before this fix moved fetchBundledAssets' output to the full flavor's own " +
        "source set (P24 fix, FR-AST-13, AC-190)."
    // Deliberately no declared outputs/up-to-date check: this is a cheap existence check plus,
    // at most once per checkout, a directory delete — not worth the complexity of caching a task
    // whose job is to make a stale directory NOT exist.
    doLast {
        val legacy = layout.projectDirectory.dir(BundledAssetPackaging.LEGACY_ASSETS_RELATIVE_PATH).asFile
        if (legacy.exists()) {
            logger.lifecycle("cleanupLegacyBundledAssets: removing stale $legacy — see this task's own description.")
            legacy.deleteRecursively()
        }
    }
}

tasks.matching { it.name.contains("merge") && it.name.contains("Assets") }.configureEach {
    dependsOn(cleanupLegacyBundledAssets)
}

// D44 (FR-AST-14): publishes the assets `fetchBundledAssets` has already fetched and verified to
// the `models-v1` GitHub Release mirror the `play` variant's setup step downloads from. Registered
// here (alongside `fetchBundledAssets`, the task whose cache it reads) but deliberately NOT wired
// into `assemble*`/`check`/`build` — publishing a release asset is a release-time action, invoked
// explicitly by `.github/workflows/release.yml` on an actual release tag, never a side effect of a
// plain local or CI build. Run it after `:app:assembleFullDebug` (or `assembleFullRelease`) so
// `fetchBundledAssets` has already populated the cache every entry needs.
val publishModelMirror = tasks.register<PublishModelMirrorTask>("publishModelMirror") {
    group = "publishing"
    description = "Uploads every verified bundled asset to the models-v1 GitHub Release mirror, " +
        "idempotently (D44, FR-AST-14). Release workflow only — never part of build/check."
    manifestFile.set(bundledAssetManifestFile)
    cacheRoot.set(layout.dir(providers.provider { gradle.gradleUserHomeDir.resolve("ort-bundled-assets") }))
}

// P23 (FR-AST-13): `fetchBundledAssets` fetches EVERY asset (D35, FR-AST-3) — that is exactly what
// the `full` variant ships and exactly what the `play` variant does not (its models download at
// setup instead). Scoped to the `full` flavor's own assemble/merge-assets tasks only, by exact
// name (AGP's flavor-qualified task names, `assemble<Flavor><BuildType>`) — a `play` build/test
// needs neither HF_TOKEN nor the escape hatch at all.
tasks.matching { it.name == "assembleFullDebug" || it.name == "assembleFullRelease" }.configureEach {
    dependsOn(fetchBundledAssets)
}

// CI regression (register, 2026-09-11, commit 8a8e8ea1): a fresh checkout has no
// `app/src/full/assets/bundled/` (gitignored — P24 fix moved this from `src/main/`, see
// [BundledAssetPackaging]'s own KDoc) and `:app:testFullDebugUnitTest` never pulled
// `fetchBundledAssets` into its own task graph — the assembleFullDebug/assembleFullRelease
// `dependsOn` above only helps when one of *those* is also requested, and the broad `mustRunAfter`
// sweep below deliberately excludes `UnitTest`-named tasks, so a plain `:app:testFullDebugUnitTest`
// invocation (exactly what CI's `android` job and Robolectric run) never scheduled the fetch at
// all. Robolectric reads assets through `mergeFullDebugUnitTestAssets`, which itself depends on
// `mergeFullDebugAssets` (AGP's own wiring, not ours) to combine the main and test asset sets — so
// a direct `dependsOn(fetchBundledAssets)` on `mergeFullDebugAssets`/`mergeFullReleaseAssets` (the
// `full` flavor's own two *main*-variant merge tasks, matched by exact name so no other task's —
// and no `play`-flavor task's — assets are touched) is the one place that reaches both the real
// app and every test that reads its packaged assets through one real dependency edge, not a
// same-invocation-only ordering hint. This does mean `:app:testFullDebugUnitTest` now needs a real
// `HF_TOKEN` (or the escape hatch) to run standalone, same as `assembleFullDebug` always has —
// deliberate, per this fix: tests and the app must see the identical packaged asset set
// (FR-AST-3), never a fresher one than what shipped. `HF_TOKEN` absent behaves exactly as before:
// `fetchBundledAssets` fails with its one-line message unless
// `-PortAllowMissingBundledAssets=true`/`ORT_ALLOW_MISSING_BUNDLED_ASSETS=1` is set, in which case
// it packages the 4 non-gated assets and marks the gated one `missing`. `:app:testPlayDebugUnitTest`
// is unaffected either way (P23) — `play` never merges the bundled assets at all.
tasks.matching { it.name == "mergeFullDebugAssets" || it.name == "mergeFullReleaseAssets" }.configureEach {
    dependsOn(fetchBundledAssets)
}

// R-1001 (register): sherpa-onnx's Android native libraries — see FetchSherpaNativeTask's own KDoc
// for the full defect account and sherpa-native.json for the manifest. Deliberately NOT wired to
// -PortAllowMissingBundledAssets/ORT_ALLOW_MISSING_BUNDLED_ASSETS the way fetchBundledAssets is
// (lead correction, WPJ build report): the archive needs no token and costs tens, not hundreds, of
// megabytes — a build that silently shipped without these libraries is the exact defect (R-1001)
// this task exists to close, so there is no escape hatch and a fetch failure always fails the
// build, in every environment including a plain no-token local iteration.
val sherpaNativeManifestFile = rootProject.layout.projectDirectory.file("sherpa-native.json")

val fetchSherpaNativeLibraries = tasks.register<FetchSherpaNativeTask>("fetchSherpaNativeLibraries") {
    group = "build"
    description = "Fetches, verifies and packages sherpa-onnx's Android native libraries into " +
        "src/main/jniLibs (register R-1001)."
    manifestFile.set(sherpaNativeManifestFile)
    jniLibsOutputDir.set(layout.projectDirectory.dir("src/main/jniLibs"))
    cacheRoot.set(layout.dir(providers.provider { gradle.gradleUserHomeDir.resolve("ort-sherpa-native") }))
}

// Every variant needs the native libraries regardless of flavor (they are the ASR/VAD JNI
// bindings themselves, never the model weights `play` defers to setup) — matched by regex across
// both flavors' debug/release assemble tasks, unlike fetchBundledAssets above.
val assembleVariantTaskName = Regex("assemble(Full|Play)(Debug|Release)")
tasks.matching { assembleVariantTaskName.matches(it.name) }.configureEach {
    dependsOn(fetchSherpaNativeLibraries)
}

// Same implicit-input shape fetchBundledAssets' own comment documents for mergeFullDebugAssets/
// mergeFullReleaseAssets, for the AGP task family that actually reads src/main/jniLibs directly —
// again both flavors, since the native libraries ship in every variant.
val mergeJniLibFoldersTaskName = Regex("merge(Full|Play)(Debug|Release)JniLibFolders")
tasks.matching { mergeJniLibFoldersTaskName.matches(it.name) }.configureEach {
    dependsOn(fetchSherpaNativeLibraries)
}

// R-1001: the structural guard (audit F-027's own family, PlatformGuards.kt) that closes the gap
// every declared-artifact check in that file cannot — see NativeLibraryPackagingGuardTask's own
// KDoc for why it reads the real APK instead. Wired here, not the root platformGuards task, because
// this needs an assembled APK to exist first and dependencyRules/platformGuards/build's own
// ordering (root build.gradle.kts, outside this package's ownership) runs platformGuards before
// any variant is assembled. `:app:check`/`:app:build` already reach it, and the root `build` task
// depends on every subproject's own `check` (root build.gradle.kts), so a plain `./gradlew build`
// still exercises this guard on every push.
// P23: scoped to the `full` flavor's own debug APK — the same single variant this guard always
// checked before flavors existed. `play`'s own packaging is not re-checked here (both flavors
// share the identical native-library wiring above, so the risk this guard exists for — R-1001,
// a declared dependency whose native `.so` never reaches the APK — is not flavor-specific; adding
// a second, full assembled `play` APK to `:app:check`'s own graph purely to re-prove an identical
// packaging step was judged not worth doubling this task's own build cost. Left open if that
// judgement call needs revisiting: see this session's own report.)
val verifySherpaNativeLibrariesPackaged = tasks.register<NativeLibraryPackagingGuardTask>(
    "verifySherpaNativeLibrariesPackaged",
) {
    group = "verification"
    description = "Fails if the packaged full-flavor debug APK is missing a required sherpa-onnx " +
        "native library for any required ABI (register R-1001)."
    apkFile.set(layout.buildDirectory.file("outputs/apk/full/debug/app-full-debug.apk"))
    dependsOn("assembleFullDebug")
}

tasks.named("check") { dependsOn(verifySherpaNativeLibrariesPackaged) }

// Debug-fix session (2026-09-19, reworked after coordinator review): the signing-stability half of
// the same fix verifySherpaNativeLibrariesPackaged models above (an assembled-APK guard, not a
// declared-coordinate one) — see PlatformGuards.signingStabilityViolations's own KDoc for the full
// defect account. Targets the `full`-flavor debug APK, the one artifact `release.yml` actually
// publishes (same path verifySherpaNativeLibrariesPackaged already checks, above).
//
// This does NOT fail a plain local `assembleFullDebug`: `enforceExpectedCertificate` defaults to
// `false` unless the invocation explicitly passes `-PortEnforcePinnedReleaseSigning=true`
// (release.yml only, right after it has built with the injected release-signing secrets) — a
// local build, signed with AGP's own per-machine debug key, is expected to differ from the pin
// and must not break the build for that. What IS always checked, in every invocation: the APK is
// actually verifiably signed with at least a v2 scheme — a build that ships unsigned or v1-only
// is a real defect on any machine, not just CI's.
//
// The expected digest itself is deliberately NOT a source constant (a first version of this fix
// hardcoded one derived from a keystore that got checked into the repository — rejected on review:
// a public repo must never carry a private signing key). It is read from
// [releaseCertificateDigestFile] — checked in, starts at the literal placeholder `UNSET` — or
// overridden by the `ortReleaseCertificateSha256` Gradle property, so the operator can configure it
// without touching build logic once the real key exists. See RELEASING.md's "Signing" section for
// exactly what to generate and paste in.
val releaseCertificateDigestFile = rootProject.layout.projectDirectory
    .file("buildSrc/signing/release-certificate.sha256")

fun readPinnedCertificateDigest(): String? {
    val file = releaseCertificateDigestFile.asFile
    if (!file.exists()) return null
    val configured = file.readLines()
        .map { it.substringBefore('#').trim() }
        .firstOrNull { it.isNotEmpty() }
    return configured?.takeUnless { it.equals("UNSET", ignoreCase = true) }
}

val verifyReleaseSigningStability = tasks.register<SigningStabilityGuardTask>(
    "verifyReleaseSigningStability",
) {
    group = "verification"
    description = "Fails if the packaged full-flavor debug APK is not installably signed, and, " +
        "when -PortEnforcePinnedReleaseSigning=true, if its certificate has drifted from the " +
        "pinned digest in buildSrc/signing/release-certificate.sha256 (debug-fix session " +
        "2026-09-19; release.yml only — see RELEASING.md)."
    apkFile.set(layout.buildDirectory.file("outputs/apk/full/debug/app-full-debug.apk"))
    pinnedCertificateSha256.set(
        providers.gradleProperty("ortReleaseCertificateSha256").orElse(
            provider { readPinnedCertificateDigest() ?: "" },
        ).map { it.ifBlank { null } },
    )
    enforceExpectedCertificate.set(
        providers.gradleProperty("ortEnforcePinnedReleaseSigning").map { it.toBoolean() }.orElse(false),
    )
    dependsOn("assembleFullDebug")
}

tasks.named("check") { dependsOn(verifyReleaseSigningStability) }

// P24 fix (register, Wave G batch gate, FR-AST-13, AC-190): [BundledAssetPackagingGuardTask]'s own
// KDoc explains why, unlike verifySherpaNativeLibrariesPackaged above, this guard is deliberately
// NOT scoped to one flavor's APK — the whole point of this fix is that `full` and `play` must
// differ here, so both are checked, with `expectBundled` set the opposite way. Neither adds a real
// assemble to the graph: `./gradlew build` already assembles every variant's debug and release APK
// (that is exactly how this defect's own build-report reproduced — `compressFullReleaseAssets` and
// `compressPlayReleaseAssets` running concurrently), so this only adds a cheap zip-entry scan after
// an APK the gate was already producing.
val verifyFullBundledAssetPackagingBoundary = tasks.register<BundledAssetPackagingGuardTask>(
    "verifyFullBundledAssetPackagingBoundary",
) {
    group = "verification"
    description = "Fails unless the full-flavor debug APK actually bundles every asset " +
        "(FR-AST-3, FR-AST-13, AC-190)."
    apkFile.set(layout.buildDirectory.file("outputs/apk/full/debug/app-full-debug.apk"))
    expectBundled.set(true)
    dependsOn("assembleFullDebug")
}

val verifyPlayBundledAssetPackagingBoundary = tasks.register<BundledAssetPackagingGuardTask>(
    "verifyPlayBundledAssetPackagingBoundary",
) {
    group = "verification"
    description = "Fails if the play-flavor debug APK bundles any model asset (FR-AST-13, AC-190)."
    apkFile.set(layout.buildDirectory.file("outputs/apk/play/debug/app-play-debug.apk"))
    expectBundled.set(false)
    dependsOn("assemblePlayDebug")
}

tasks.named("check") {
    dependsOn(verifyFullBundledAssetPackagingBoundary, verifyPlayBundledAssetPackagingBoundary)
}

// `app/src/full/assets/bundled/` (fetchBundledAssets' own output, P24 fix) is an *implicit* input
// to a whole family of AGP-internal tasks that read the full-flavor variant's assets directly — not
// just `mergeDebugAssets`/`mergeReleaseAssets`, but also lint's own model-writer tasks
// (`generateDebugLintReportModel` and siblings), found by actually running the full `build` task
// and reading what Gradle's own task-validation named next, rather than guessed up front. Running
// any of them in the same build as `fetchBundledAssets` with no declared relationship trips
// Gradle's validation ("uses this output ... without declaring an explicit or implicit
// dependency"). Enumerating every such AGP task by name is whack-a-mole — a new AGP version can
// add another one — so this orders **every** task in this project after `fetchBundledAssets`
// except the two kinds that must never be coupled to it:
//
//  - `fetchBundledAssets`/the two catalog-generation tasks themselves (ordering a task after
//    itself is a Gradle error).
//  - Anything with `UnitTest`/`AndroidTest` in its name — `mustRunAfter` on these would be
//    redundant, not a relaxation: since the fix above made `mergeFullDebugAssets`/
//    `mergeFullReleaseAssets` (and therefore `mergeFullDebugUnitTestAssets`/
//    `mergeFullDebugAndroidTestAssets`, which AGP wires to depend on the corresponding
//    main-variant merge) carry a real `dependsOn(fetchBundledAssets)`, every unit/instrumentation
//    test that reads the merged assets already has a `dependsOn`-strength ordering guarantee — a
//    stronger property than `mustRunAfter` gives, so adding the weaker hint on top would say
//    nothing new. `:app:testFullDebugUnitTest` therefore DOES need a real `HF_TOKEN` (or the
//    escape hatch) to run standalone now, same as `assembleFullDebug` — see the
//    `mergeFullDebugAssets`/`mergeFullReleaseAssets` block above for why.
//
// `mustRunAfter`, deliberately not `dependsOn`, for every one of them: it only orders the two
// tasks *when both are already scheduled* — true for `assembleFullDebug`/`assembleFullRelease`
// (which `dependsOn` the fetch task explicitly, above), for `mergeFullDebugAssets`/
// `mergeFullReleaseAssets` (ditto, above) and every task that in turn depends on any of those
// (which now includes the test tasks).
tasks.matching { task ->
    task.name != fetchBundledAssets.name &&
        task.name != generateFullBundledAssetCatalog.name &&
        task.name != generatePlayBundledAssetCatalog.name &&
        !task.name.contains("UnitTest") &&
        !task.name.contains("AndroidTest")
}.configureEach {
    mustRunAfter(fetchBundledAssets)
}

// CI regression (register, 2026-09-11, release run 34664670909): Robolectric's own
// `Asset$_CompressedAsset.getBuffer` inflates a compressed APK asset entirely into the test JVM's
// heap the moment a test reads it — and now that `mergeDebugAssets` (above) guarantees the real,
// 555 MB `LLM_GEMMA3_1B` asset is actually present for `:app:testDebugUnitTest` to read, any test
// that copies it through the real `AndroidBundledAssetSource` (not the fixture source) forces that
// entire inflation, which the JVM test worker's default heap cannot hold — `OutOfMemoryError: Java
// heap space` killed the release workflow's unit-test job. Raising `org.gradle.jvmargs` (the
// *daemon's* heap, R-807c) does not help here: `Test` tasks fork their own worker JVM with its own,
// separate heap, sized by `maxHeapSize`, not the daemon's. Set explicitly rather than left at
// Gradle's default (512m), which is what OOM'd.
//
// The alternative deliberately not taken: `androidResources.noCompress` for `.task`/`.onnx` would
// let Robolectric (and the real Android runtime) map the asset file instead of inflating it, since
// an uncompressed APK entry can be read directly rather than decompressed into memory — but AAPT2
// already compresses these binary, already-dense model formats poorly, so marking them
// non-compressed would grow the shipped APK from the measured ~611 MB (results/e2e-audit/
// installed-size.md, R18) to an estimated ~724 MB — a real, permanent cost to every install for a
// test-JVM-only problem. Documented here as an option, not applied.
tasks.withType<Test>().configureEach {
    maxHeapSize = "3g"
}

// P13: `testBuildType` above only redirects the `test`/`check` task *aliases* — `build`'s own
// dependency graph still wires up `test<Flavor>ReleaseUnitTest` for both flavors regardless, and
// that variant's merged manifest never carries `ui-test-manifest` (debug-only, correctly — see the
// dependency comment below), so it fails on every Compose UI test for a reason that has nothing to
// do with the code under test. The release build type differs from debug only by
// `isMinifyEnabled`, so testing it separately proves nothing `test<Flavor>DebugUnitTest` does not
// already prove; disable both flavors' release unit tests outright (P23: was the single
// `testReleaseUnitTest` before flavors existed).
val releaseUnitTestTaskName = Regex("test(Full|Play)ReleaseUnitTest")
tasks.matching { releaseUnitTestTaskName.matches(it.name) }.configureEach { enabled = false }

dependencies {
    "implementation"(platform(composeBomCoordinate))
    "implementation"("androidx.compose.ui:ui")
    "implementation"("androidx.compose.ui:ui-graphics")
    "implementation"("androidx.compose.ui:ui-tooling-preview")
    "implementation"("androidx.compose.material3:material3")
    "implementation"("androidx.compose.foundation:foundation")
    "implementation"(activityComposeCoordinate)
    "debugImplementation"("androidx.compose.ui:ui-tooling")
    // The debug-only stub activity ui-test-junit4/createComposeRule launches when a test does not
    // provide its own — declared debugImplementation (not testImplementation) because it must be
    // merged into the manifest the "test" build variant compiles against, same as upstream Compose
    // testing setup guides document.
    "debugImplementation"("androidx.compose.ui:ui-test-manifest")

    "testImplementation"(platform(composeBomCoordinate))
    "testImplementation"("androidx.compose.ui:ui-test-junit4")

    "androidTestImplementation"(platform(composeBomCoordinate))
    "androidTestImplementation"("androidx.compose.ui:ui-test-junit4")
    "androidTestImplementation"("androidx.test.ext:junit:1.2.1")
    "androidTestImplementation"("androidx.test:runner:1.6.2")
    "androidTestImplementation"("androidx.test:core:1.6.1")
}
