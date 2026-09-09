import com.android.build.gradle.internal.dsl.BaseAppModuleExtension

plugins {
    id("ort.android-app")
}

// R-138 (register, ui-conformance WP10): `Settings-About`'s version line ("<version> · build <n>
// · <short commit>") and its Models row (the real sherpa-onnx version this build depends on) both
// need values `ort.android-app.gradle.kts` (buildSrc) cannot supply — that file's own doc comment
// explains `libs` (the version catalog) is not visible to a buildSrc precompiled script plugin,
// only to a normal project script like this one, and a real git process is likewise only sensible
// to invoke from here. Read via the Provider API (`providers.exec`), not `Runtime.exec` at
// configuration time, and falls back to the honest literal "unknown" — never a fabricated hash —
// if this checkout has no `git` on its `PATH` or is not a git checkout at all.
val gitShortCommitProvider = providers.exec {
    commandLine("git", "rev-parse", "--short=7", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim() }.orElse("unknown")

extensions.configure<BaseAppModuleExtension> {
    testOptions { unitTests.isIncludeAndroidResources = true }

    // ui-conformance WP11b follow-up: AGP 8 defaults `buildConfig` to false, so `BuildConfig` was
    // not generated at all — `org.ort.app.ui.failures.DebugFailureOverride` needs `BuildConfig.DEBUG`
    // to gate its read on a real build-type check (a release build must never consult a debug-only
    // override, even if nothing in it happened to call `show()`), which needs this turned on.
    buildFeatures { buildConfig = true }

    // R-138: both fields are real, sourced facts about *this* build — never board literals. See
    // this file's own top comment for why they are read here rather than in the buildSrc plugin.
    defaultConfig {
        buildConfigField("String", "GIT_SHORT_COMMIT", "\"${gitShortCommitProvider.get()}\"")
        buildConfigField("String", "SHERPA_ONNX_VERSION", "\"${libs.versions.sherpaOnnx.get()}\"")
    }

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
    // R-154 (FR-LEX-30, FR-AST-2): the Models screen's lexicon-import flow calls
    // LexiconImportValidator/LexiconImportInstaller directly. Previously test-only below;
    // promoted to main so ModelsViewData.kt can call it from real (non-test) code.
    implementation(project(":lexicon"))
    implementation(libs.androidx.core.ktx)
    // :data's Room types (OrtDatabase, its DAOs) are used directly by StatusActivity/
    // TransmissionListActivity's real-data polling (v0 smoke test — see RealCaptureService's doc
    // comment in :pipeline) — :data itself only has `implementation` on Room, so it is not on
    // this module's classpath transitively.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)

    testImplementation(project(":testing"))
    testImplementation(project(":eval"))
    // :lexicon is now a main `implementation` dependency above (R-154), so it no longer needs its
    // own testImplementation/androidTestImplementation lines here — both test source sets already
    // see it transitively.
    // Test-only (ModuleGraph/dependencyRules deliberately exempts test scope, buildSrc's
    // build.gradle.kts comment): build-plan P14's playback tests need to write a real
    // codec-encoded fixture file the same way `:capture-android`'s `RealSegmentSink` does, to
    // prove `RealTransmissionAudioPlayer` decodes retained audio through the identical codec
    // `:pipeline`'s own Pass B audio path uses — never a second, drifting implementation.
    testImplementation(project(":capture-android"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.robolectric)
    // R-204 (see :data/build.gradle.kts's identical comment): this module's own Robolectric
    // tests open a real OrtDatabase (via CorrectionPolling, StationPolling, ReaderPolling, ...),
    // whose driver-mode native library AGP otherwise resolves to the Android-variant artifact
    // even on a unit-test (host-JVM) classpath.
    testImplementation(libs.androidx.sqlite.bundled.jvm)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.test.core)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit.vintage.engine)

    // build-plan P9: the on-device harness runner (M2.21a). Test-scoped only — see the
    // sourceSets comment above and ModuleGraph.kt's documented test-scope exemption.
    androidTestImplementation(project(":eval"))
    androidTestImplementation(project(":testing"))
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
//
// Test-suite regression, 2026-09-08 (CHANGELOG's own entry has the full bisection): confirmed the
// same failure mode — bisected to `SessionsContentTest` alone reliably poisoning `ui.failures`'s
// own Compose tests when the two run in one JVM fork after it (`FailureScreensTest`/`FailureHostTest`
// then take tens of seconds to tens of *minutes* per test instead of milliseconds, all inside
// `RobolectricIdlingStrategy.runUntilIdle`/`MainTestClock.advanceTimeByFrame`, never inside this
// app's own code). Bisection ruled out every mechanism that looked like a leaked resource this class
// could plausibly own — closing its own `OrtDatabase`, caching `OrtDatabase.create()` process-wide
// (kept anyway: `data/src/main/kotlin/org/ort/data/OrtDatabase.kt`'s own report, a genuine unrelated
// leak worth fixing), even disabling the one recurring polling `LaunchedEffect` its tests reach
// (`LogContent.kt`'s embedded poll) made no difference — so this is the same class of Robolectric/
// Compose-testing JVM-sharing failure `ReaderActivityDestinationSmokeTest` already named above, not
// a leak in this package's own code, and the same fix applies: a JVM this class's tests never share
// with anything that runs after them.
// Test-suite regression, 2026-09-08 (this task's own CHANGELOG entry): `SessionsContentTest`
// (above) is one instance of a wider pattern — every test file below `setContent`s a screen's real
// `*Content` composable (not a hand-built `*Screen`/view-state one), each of which owns its own
// real `LaunchedEffect(key) { while (true) { ...; delay(2_000) } }` poll (`NowContent.kt`,
// `CaptureStatusContent.kt`, `LogContent.kt`, `ThreadContent.kt`, `FailureHost.kt`, `OrtNavHost.kt`
// itself). Bisecting the full suite kept finding a *different* one of these poisoning whatever
// Compose test ran after it once excluded — `SessionsContentTest` fixed the `ui.digest`→`ui.failures`
// crossing this session's own report bisected first; `NowContentTest` reproduced the identical
// signature crossing into `ui.screens.NowScreenTest`/`CorrectionSheetTest` next. Every file in this
// list reaches one of those real, recurring polls; isolating the whole set here (rather than
// re-bisecting each remaining crossing one at a time against a suite that takes minutes per attempt)
// is the same proven fix applied to every known instance of one root cause at once.
val composeIdlePoisoningSmokeTestDebugUnitTest = tasks.register<Test>("smokeTestDebugUnitTest") {
    group = "verification"
    description = "Runs the test classes already confirmed (or, by the same shape, suspected) to " +
        "poison later Compose tests' idle checking when they share a JVM with testDebugUnitTest's " +
        "~1200 others — each alone, in its own fresh JVM — see this task's own KDoc."
    include("**/ReaderActivityDestinationSmokeTest*")
    include("**/SessionsContentTest*")
    include("**/NowContentTest*")
    include("**/CaptureStatusContentTest*")
    include("**/LogContentBackHandlerTest*")
    include("**/LogAndThreadContentActivityTest*")
    include("**/FailureHostTest*")
    include("**/NavSeedTest*")
    include("**/OrtNavHostDestinationDispatchTest*")
    include("**/ReaderAccessibilityTest*")
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
    debugUnitTest.exclude("**/SessionsContentTest*")
    debugUnitTest.exclude("**/NowContentTest*")
    debugUnitTest.exclude("**/CaptureStatusContentTest*")
    debugUnitTest.exclude("**/LogContentBackHandlerTest*")
    debugUnitTest.exclude("**/LogAndThreadContentActivityTest*")
    debugUnitTest.exclude("**/FailureHostTest*")
    debugUnitTest.exclude("**/NavSeedTest*")
    debugUnitTest.exclude("**/OrtNavHostDestinationDispatchTest*")
    debugUnitTest.exclude("**/ReaderAccessibilityTest*")
    // Test-suite regression, 2026-09-08 (this task's own CHANGELOG entry): excluding the two
    // confirmed-poisoning classes above (this file's own KDoc) was not sufficient on its own — the
    // full suite still eventually wedged (confirmed: `ImproveScreensTest`, a file with no
    // coroutine, `LaunchedEffect` or `OrtDatabase` use at all, was still "involved" once combined
    // with enough of `ui.screens`, ruling out any single remaining bad test). Every real
    // `*Polling`/`*Runner`/producer object across `:app` opens an `OrtDatabase` and — outside the
    // handful of `@After` blocks that call `close()` — never closes it (`OrtDatabase.kt`'s own
    // report has the count and the fix that caps *repeat* opens against the same on-disk path).
    // Across ~1200 tests in one Robolectric-sandboxed JVM, though, most test methods get their own
    // fresh simulated app install (a fresh `filesDir`/path), so that cap does not consolidate
    // *across* them — each contributes its own live connection to `OrtDatabase`'s shared, never-
    // shut-down `queryExecutor` (`Executors.newCachedThreadPool()`, deliberately unbounded per that
    // pool's own doc comment, to isolate from a *different*, already-fixed contention bug). Enough
    // live connections piling up over one long JVM run compounds into exactly the "runs fine in any
    // one package, fine even at half the suite, eventually catastrophic" signature this session's
    // bisection kept finding no single owner for. `forkEvery` — Gradle's own, standard remedy for a
    // test task with cumulative, hard-to-fully-close JVM-process state — recycles the whole worker
    // (a fresh JVM, a fresh Robolectric sandbox, a fresh `queryExecutor`) periodically, bounding the
    // *worst case* to what one batch can accumulate regardless of which classes are in it, without
    // forking every single class the way the confirmed-poisoning tasks above must.
    debugUnitTest.forkEvery = 40
    composeIdlePoisoningSmokeTestDebugUnitTest.configure {
        testClassesDirs = debugUnitTest.testClassesDirs
        classpath = debugUnitTest.classpath
        jvmArgumentProviders.addAll(debugUnitTest.jvmArgumentProviders)
        systemProperties.putAll(debugUnitTest.systemProperties)
    }
}

tasks.named("check") { dependsOn(composeIdlePoisoningSmokeTestDebugUnitTest) }
tasks.named("build") { dependsOn(composeIdlePoisoningSmokeTestDebugUnitTest) }
