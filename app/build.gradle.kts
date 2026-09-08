import com.android.build.gradle.internal.dsl.BaseAppModuleExtension

plugins {
    id("ort.android-app")
}

extensions.configure<BaseAppModuleExtension> {
    testOptions { unitTests.isIncludeAndroidResources = true }

    // ui-conformance WP11b follow-up: AGP 8 defaults `buildConfig` to false, so `BuildConfig` was
    // not generated at all — `org.ort.app.ui.failures.DebugFailureOverride` needs `BuildConfig.DEBUG`
    // to gate its read on a real build-type check (a release build must never consult a debug-only
    // override, even if nothing in it happened to call `show()`), which needs this turned on.
    buildFeatures { buildConfig = true }

    // build-plan P9 / M2.21a: the on-device harness runner and its JVM-side verification must
    // call the exact same code, or "same report format as the JVM harness" is only asserted, not
    // proven. `src/harnessShared` holds that one implementation; wiring it into both `test` (JVM,
    // runs without a device) and `androidTest` (the real on-device entry point) is what makes the
    // JVM test in `test` a genuine proof about the code `androidTest` runs, not a parallel copy.
    // :eval is reachable from this shared source only because it lands in the `test`/`androidTest`
    // compile configurations below, which `dependencyRules` deliberately does not check (test
    // scope is exempted repo-wide, buildSrc/.../ModuleGraph.kt — :app's *main* source set still
    // has no path to :eval).
    sourceSets {
        getByName("test").java.srcDir("src/harnessShared/kotlin")
        getByName("androidTest").java.srcDir("src/harnessShared/kotlin")
    }
}

// WP0 (spec/ui-conformance-plan.md, register R-110/R-111): `app/src/debug` is a default source
// set AGP picks up on its own — no line was needed for that (checked first, per this package's
// brief). This is a different, pre-existing gap `ort.android-app.gradle.kts` (buildSrc, outside
// this package's ownership) already half-addressed: it disables the *`testReleaseUnitTest`*
// task (Compose's `ui-test-manifest` stub is debug-only, so the release variant's tests fail for a
// reason unrelated to the code under test) but not `compileReleaseUnitTestKotlin`, which compiles
// the *same* `app/src/test/kotlin` source set against the release variant's classpath — one that
// never carries `app/src/debug` (`ScenarioReceiver`, `Scenarios`, `ScenarioFixtures`,
// `ScenarioReaderActivity`...). That compile task still runs as part of `./gradlew build`/`check`
// regardless of the disabled test-execution task, and fails the moment a test file needs a
// debug-only symbol for the first time — found by actually running `./gradlew build`, not by
// inspection. No release artifact consumes this task's output; disabled here for the identical
// reason its sibling already is.
tasks.matching { it.name == "compileReleaseUnitTestKotlin" }.configureEach { enabled = false }

// build-plan P8 adds the capture status surface and permissions flow (plain Android views —
// Compose is not yet wired into ort.android-app.gradle.kts, and pulling it in is out of this
// prompt's scope). The reader UI and Hilt graph remain for a later session.
dependencies {
    implementation(project(":core"))
    implementation(project(":pipeline"))
    implementation(project(":data"))
    implementation(project(":net"))
    implementation(libs.androidx.core.ktx)
    // :data's Room types (OrtDatabase, its DAOs) are used directly by StatusActivity/
    // TransmissionListActivity's real-data polling (v0 smoke test — see RealCaptureService's doc
    // comment in :pipeline) — :data itself only has `implementation` on Room, so it is not on
    // this module's classpath transitively.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)

    testImplementation(project(":testing"))
    testImplementation(project(":eval"))
    testImplementation(project(":lexicon"))
    // Test-only (ModuleGraph/dependencyRules deliberately exempts test scope, buildSrc's
    // build.gradle.kts comment): build-plan P14's playback tests need to write a real
    // codec-encoded fixture file the same way `:capture-android`'s `RealSegmentSink` does, to
    // prove `RealTransmissionAudioPlayer` decodes retained audio through the identical codec
    // `:pipeline`'s own Pass B audio path uses — never a second, drifting implementation.
    testImplementation(project(":capture-android"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.test.core)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit.vintage.engine)

    // build-plan P9: the on-device harness runner (M2.21a). Test-scoped only — see the
    // sourceSets comment above and ModuleGraph.kt's documented test-scope exemption.
    androidTestImplementation(project(":eval"))
    androidTestImplementation(project(":testing"))
    androidTestImplementation(project(":lexicon"))
}

// ui-conformance WP3 (lead-approved edit to this file only — everything else stays this
// package's own row): `ReaderActivityDestinationSmokeTest` launches many real `Activity`
// instances, each with real polling `LaunchedEffect`s, more than any other file in this suite —
// see that class's own KDoc for the full account of what was tried. Left inside `testDebugUnitTest`
// it can leave the one JVM worker that task reuses for its ~900 other tests unable to reach Compose
// idle for whatever test composes *next*, a failure with nothing to do with the code under test.
// Giving it a task of its own is what actually fixes that: a different `Test` task is always a
// fresh worker process, never one shared with `testDebugUnitTest`'s own run, regardless of either
// task's own fork settings — `forkEvery = 1` below is an extra guard for if this task ever grows a
// second class, not what does the isolating.
val smokeTestDebugUnitTest = tasks.register<Test>("smokeTestDebugUnitTest") {
    group = "verification"
    description = "Runs ReaderActivityDestinationSmokeTest alone, in its own JVM never shared " +
        "with testDebugUnitTest's — see that class's own KDoc for why."
    include("**/ReaderActivityDestinationSmokeTest*")
    forkEvery = 1
}

// `afterEvaluate`, not immediate: `testDebugUnitTest` is AGP's own task (registered by the
// `com.android.application` plugin `ort.android-app` applies), and AGP does not create it until
// its own variant-configuration callbacks run — this script's own top level runs first and a plain
// `tasks.named("testDebugUnitTest")` at that point fails with "Task ... not found" (found by
// actually running this, not by inspection). `afterEvaluate` is the standard, documented point
// AGP's own task is guaranteed to exist. Its `classpath`/`testClassesDirs` and
// `jvmArgumentProviders` (exactly how modern AGP passes Robolectric's own resource/manifest paths
// into a unit test task — confirmed by inspecting `testDebugUnitTest`'s configuration before
// writing this) are copied wholesale into the new task, rather than reimplemented, so it is
// genuinely "the same type/config" and not a same-looking task that silently can't find its own
// resources; `testDebugUnitTest` itself is also excluded here, once it is guaranteed to exist.
afterEvaluate {
    val debugUnitTest = tasks.withType<Test>().named("testDebugUnitTest").get()
    debugUnitTest.exclude("**/ReaderActivityDestinationSmokeTest*")
    smokeTestDebugUnitTest.configure {
        testClassesDirs = debugUnitTest.testClassesDirs
        classpath = debugUnitTest.classpath
        jvmArgumentProviders.addAll(debugUnitTest.jvmArgumentProviders)
        systemProperties.putAll(debugUnitTest.systemProperties)
    }
}

tasks.named("check") { dependsOn(smokeTestDebugUnitTest) }
tasks.named("build") { dependsOn(smokeTestDebugUnitTest) }
