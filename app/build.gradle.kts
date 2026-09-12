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

    // R-1001 (register): `:asr-sherpa/build.gradle.kts` now scopes the Windows-x64 sherpa-onnx
    // native jar `testRuntimeOnly`, which already keeps it off this module's own runtime/packaging
    // classpath structurally (see that file's own comment for why that is the real fix, verified
    // against the built APK). This exclusion is a second, narrower line of defense — belt and
    // braces, not the fix itself — against any future dependency anywhere in this app's graph
    // that resolves `sherpa-onnx-native-lib-*` onto a `runtime`/`implementation` configuration:
    // those jars carry their `.dll`/`.so` files as plain Java resources under `sherpa-onnx/native/`
    // (confirmed by listing the win-x64 jar's contents), which AGP would otherwise merge into the
    // APK root as inert, undexed resource files no Android runtime ever loads — exactly the 22 MB
    // of dead Windows DLLs this register row found shipping to every device.
    packaging {
        resources {
            excludes += "sherpa-onnx/native/**"
            // R-1001 build report: pre-existing, unrelated to sherpa-onnx — the first time anyone
            // actually assembled `:app:connectedDebugAndroidTest` in this repository (this
            // session's own new androidTest is the first file in that source set besides the
            // never-yet-run `HarnessInstrumentedTest`), it failed packaging on the JUnit5 jars
            // `androidTestImplementation(project(":eval"))` already pulled in before this session
            // (for `harnessShared`'s own JVM-parity test code): six of those jars each ship an
            // identical `META-INF/LICENSE.md`, then (once that was excluded) an identical
            // `META-INF/LICENSE-notice.md`. Excluding the whole family rather than one file at a
            // time — a resource-exclusion fix, squarely within this file's own packaging/resource-
            // exclusion mandate, same shape as the exclusion above, not a dependency or logic
            // change.
            excludes += "META-INF/LICENSE*.md"
        }
    }

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
    // Wave F scaffolding (WP0'): the setup UI reads the rig catalogue directly (FR-RIG-16) and
    // the Settings LLM toggle reads engine state through the :llm-api contract (FR-DIG-3b).
    implementation(project(":rig"))
    implementation(project(":llm-api"))
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
    // R-1001: RealSherpaDecoderOnDeviceTest constructs the same RealSherpaDecoder :pipeline's
    // AsrEngineProvisioning.kt constructs in production, directly, to prove the JNI binding
    // resolves on a real Android runtime — test-scoped only, same exemption as :eval/:testing above.
    // :asr-api is needed explicitly too: :asr-sherpa depends on it via `implementation`, not `api`,
    // so DecodeOptions is not visible transitively.
    androidTestImplementation(project(":asr-sherpa"))
    androidTestImplementation(project(":asr-api"))
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
    // R-590 round, coordinator escalation (2026-09-09): a full-gate wedge at `cbaca66` — `jstack`
    // of the wedged worker showed "Test worker" parked on a `FutureTask` at
    // `LevelMeterScreenTest.R_542 a clipped reading at the scale's own ceiling renders the chart
    // without crashing`, while the SDK 34 main thread spun `RUNNABLE` inside
    // `AbstractMainTestClock.advanceTimeByFrame` <- `ComposeIdlingResource.isIdleNow` <-
    // `RobolectricIdlingStrategy.runUntilIdle` with 1474s of CPU — the identical "Compose never
    // reaches idle, so `waitForIdle` spins forever" signature this task's own KDoc already
    // documents for every class above. Read directly before writing this: unlike the classes
    // above, `LevelMeterScreen.kt` (WP4-owned) itself carries no `LaunchedEffect`, poll or
    // animation of its own — this class's real mechanism is not confirmed to be identical to
    // theirs, only the *symptom* is (a Compose idle-check that never resolves once this class's
    // tests share a JVM with enough others). Isolated here on the strength of that jstack evidence
    // alone, the same remedy already proven for the whole set, rather than left in a shared JVM on
    // an unconfirmed theory of exactly why.
    include("**/LevelMeterScreenTest*")
    // Poison-hunt-2 (register, full-suite gate): `FrequencyScreenTest` — 100% reproducible at the
    // exact same test (`frequency detail with no regulars shows an honest empty state`) whenever the
    // full `--tests 'org.ort.app.ui.screens.*'` run reaches it, unaffected by every accumulation fix
    // tried in this session (closing/deleting the on-disk `ort.db` the WP8 `*ContentTest` classes
    // leaked, `forkEvery` down to 6, `maxHeapSize` up to 2g) — ruling out the general JVM/file
    // accumulation this file's own KDoc documents elsewhere as the mechanism *here* specifically.
    // Always green alone (14/14 in ~8s, confirmed repeatedly) and always failing at `setContent`
    // itself once it follows `FrequencyDetailContentTest` in the same JVM (`jstack`: the identical
    // never-reaches-idle `ComposeIdlingResource.isIdleNow` signature). Isolated on that same
    // evidence standard as the classes above, still unconfirmed to a single line.
    include("**/FrequencyScreenTest*")
    // Poison-hunt-2 (register, full-suite gate): the four classes below are this session's own
    // confirmed accumulators, not victims — bisection (`--tests`, varying combinations, `jstack`)
    // found that removing any *one* of `SearchContentTest`/`SearchFiltersSheetTest`/
    // `SearchScreenTest`/`SearchContentBackHandlerTest` from a set otherwise wedging
    // `ThreadDetailScreenTest` made that run clean, yet no single upstream class, preceding-class
    // count, `forkEvery` value (down to 6) or on-disk-`ort.db` fix (closing/deleting it, the fix
    // this file already carries for `StationsContentTest`/`StationDetailContentTest`/
    // `FrequencyDetailContentTest` above) made the combination reliably clean either — isolating the
    // *victim* only moved the wedge to the next class in file order (`ThreadDetailScreenTest` ->
    // `ThreadScreenTest`, confirmed directly). `SearchContentBackHandlerTest` carries the one
    // structural difference the other three don't: `createAndroidComposeRule<ComponentActivity>()`,
    // the exact pattern `LogAndThreadContentActivityTest`/`LogContentBackHandlerTest` above are
    // already isolated for. `SearchContentTest`/`StationDetailContentTest`/`StationsContentTest`
    // each open a real `OrtDatabase` against the shared on-disk file the same way the already-
    // isolated `*ContentTest` classes above do. Isolating this whole cluster — the same "isolate the
    // whole set once the shape is established, rather than re-bisect each remaining crossing one at
    // a time" call this file's own KDoc already made for the nine `*ContentTest` classes above —
    // rather than the victim it produces, whichever class that turns out to be.
    include("**/SearchContentBackHandlerTest*")
    include("**/SearchContentTest*")
    include("**/StationDetailContentTest*")
    include("**/StationsContentTest*")
    // idle-root task (2026-09-10, GitHub Actions run 34444706036 on NiyaNagi/offline-radio-
    // transcriber, 2-core Linux runner): the first CI failure since poison-hunt-2's own forkEvery=4
    // cut, and the first time either poison-hunt session had a real, ordered log to point at rather
    // than a `jstack` signature alone — every test in exactly two classes, `LogFilterSheetTest` and
    // `LogScreenTest` (neither touches a `Context` or a database at all), failed with
    // `AppNotIdleException`, and the CI log shows `FrequencyDetailContentTest`'s own lone test
    // PASSED immediately before the first of them, 60.1 seconds apart (its own timeout).
    //
    // **Root cause found and fixed at the source, not just isolated**: `FrequencyDetailContentTest`
    // (and five sibling classes of the identical shape — `TransmissionDetailContentTest`,
    // `StationsContentTest`, `StationDetailContentTest`, `SearchContentTest`, `SessionsContentTest`,
    // already isolated above for the same symptom) declared a bare `@After fun closeDatabase()`
    // alongside a `composeTestRule` `@Rule`. JUnit4 wraps the *entire* `@Before`/`@Test`/`@After`
    // sequence inside a `@Rule`'s own statement — a plain `@After` always runs *before* the rule's
    // own teardown, so `db.close()` ran while `composeTestRule` still owned a live composition,
    // racing its disposal. `app/src/test/kotlin/org/ort/app/testing/OrtComposeTestRule.kt`
    // (`ortComposeTestRule`) fixes this structurally: every one of those six classes now closes its
    // database from a `RuleChain` outer rule, which runs strictly after the compose rule's own
    // `after()`, so the composition is always fully disposed — its `LaunchedEffect`s cleanly
    // cancelled — before the database underneath it goes away. The same function also resets
    // `ModelsController`'s process-lifetime `stagedActivation` `StateFlow` and the
    // `DebugSearchOverride`/`DebugLexiconImportOverride` one-shot flags after every Compose test
    // that routes through it, closing three more `ui/data` leaks this task's own investigation
    // found (see `ModelsController.resetForTest`'s and `StationPolling.kt`'s `SharedDatabase`'s own
    // doc comments for the rest).
    //
    // **`FrequencyDetailContentTest` stays isolated here anyway.** A targeted local reproduction of
    // the exact `FrequencyDetailContentTest` -> `LogFilterSheetTest` -> `LogScreenTest` fork
    // ordering CI hit passed both with and without the ordering fix, on this session's own
    // many-core workstation — the race the fix closes is plausible, not locally provable, and CI's
    // own 2 cores (a fraction of this machine's own) are the more likely place a race like this
    // actually loses. Isolating the one class CI's own log names, on that log's own evidence, is
    // the certain fix for the *reported* failure; the ordering fix is kept as a genuine,
    // independently-reasoned correctness improvement that also covers `TransmissionDetailContentTest`
    // below — never proven to be *this* failure's whole story, but real regardless.
    include("**/FrequencyDetailContentTest*")
    // idle-root task: carries the identical shape (`composeTestRule` + a real `OrtDatabase` closed
    // in `@After`, one-shot, non-recurring `LaunchedEffect`s only) as `FrequencyDetailContentTest`
    // above, confirmed the CI poisoner — never itself observed to poison anything, but isolated on
    // the same shape-based standard this file already uses elsewhere (`LevelMeterScreenTest`,
    // `FrequencyScreenTest`) rather than left as the one remaining unconfirmed instance of a shape
    // just proven to matter.
    include("**/TransmissionDetailContentTest*")
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
    debugUnitTest.exclude("**/LevelMeterScreenTest*")
    debugUnitTest.exclude("**/FrequencyScreenTest*")
    debugUnitTest.exclude("**/SearchContentBackHandlerTest*")
    debugUnitTest.exclude("**/SearchContentTest*")
    debugUnitTest.exclude("**/StationDetailContentTest*")
    debugUnitTest.exclude("**/StationsContentTest*")
    // idle-root task (2026-09-10) — see the smoke task's own KDoc above for the CI evidence and
    // the structural (`ortComposeTestRule`) fix this pairs with.
    debugUnitTest.exclude("**/FrequencyDetailContentTest*")
    debugUnitTest.exclude("**/TransmissionDetailContentTest*")
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
    //
    // Poison-hunt-2 (register, full-suite gate, 2026-09-09): a full, unfiltered
    // `:app:testDebugUnitTest` — and the narrower `--tests 'org.ort.app.ui.screens.*'` reproduction
    // this session's own bisection used to investigate it faster — kept wedging at whichever Compose
    // test happened to run after `ui.screens`'s now much larger concentration of real
    // `OrtDatabase`-backed/`createAndroidComposeRule`-hosted `*ContentTest` classes (WP7/WP8), always
    // the identical `jstack` signature this comment already documents
    // (`ComposeIdlingResource.isIdleNow` <- `advanceTimeByFrame`, CPU-bound `RUNNABLE`, never
    // `BLOCKED`/`WAITING`). None of the following, tried individually, made it reliably reproduce
    // clean: isolating the victim class alone (the wedge simply moved to the next class in file
    // order once the run reached that far — confirmed directly, twice); closing/deleting the shared
    // on-disk `ort.db` the WP7/WP8 `*ContentTest` classes left open (`StationsContentTest`/
    // `StationDetailContentTest`/`FrequencyDetailContentTest`/`SearchContentTest`'s own `@Before`,
    // above — a real leak, worth keeping, but not sufficient alone); raising `maxHeapSize` (tried up
    // to `2g`, ruling out plain GC pressure as the mechanism). What did produce a clean, repeated,
    // full `:app:testDebugUnitTest` and a clean full `--tests 'org.ort.app.ui.screens.*'`, together:
    // isolating the confirmed accumulator cluster below (`SearchContentBackHandlerTest`/
    // `SearchContentTest`/`StationDetailContentTest`/`StationsContentTest`, plus `FrequencyScreenTest`
    // as a separately-confirmed case) **and** lowering `forkEvery` from 40 to 4 — a smaller number of
    // classes than the smallest confirmed-wedging combination this session's own bisection found (8
    // real Compose/Robolectric classes sharing one JVM), so no batch, wherever its boundary falls,
    // can accumulate as much as a wedge needs. `forkEvery = 40` alone (the entire point of this
    // comment's own prior paragraph) is no longer a large enough safety margin now that `ui.screens`
    // alone holds ~29 such classes; the trade is more, individually cheap JVM forks for a suite that
    // actually finishes. `maxHeapSize` is kept at `2g` regardless — it did not fix this on its own,
    // but a smaller forkEvery means more concurrent Robolectric/Compose class-loading over the life
    // of the whole task, and 512m was already the tightest margin available.
    //
    // idle-root task (2026-09-10): the fixed `forkEvery = 4` above is itself what CI run 34444706036
    // proved insufficient — tuned entirely on this session's own many-core workstation, where the
    // whole suite passes in minutes regardless, and never validated against the 2-core Linux runner
    // CI actually gives this job. Machine-adaptive rather than a second fixed guess: `availableProcessors`
    // is a real fact about *this* machine, not a magic number carried over from whichever machine last
    // tuned it. A slower, lower-core machine gets a smaller batch (more frequent JVM recycling, the
    // structural mitigation this file's own KDoc already relies on, at a higher time cost it can
    // less afford to skip); a fast many-core machine — this session's own, and presumably every other
    // contributor's local box — gets a larger one for wall-time's sake, now that
    // `FrequencyDetailContentTest`/`TransmissionDetailContentTest` (the smoke task's own KDoc above)
    // are isolated and the underlying close-before-dispose ordering bug they shared with four already-
    // isolated classes is fixed at the source (`ortComposeTestRule`). Not restored to the original
    // `forkEvery = 40`: that value predates both the `ui.screens` growth poison-hunt-2 already
    // diagnosed and this session's own CI-evidenced finding, and re-asserting it without a CI run to
    // check it against would be exactly the same mistake this comment is fixing — a number that works
    // on one machine, asserted as safe everywhere. `2` is CI's own reported core count; the runner
    // that just failed gets the smallest, safest batch this formula produces (`= 1`, matching every
    // class this file already isolates outright), never a number only this session's own hardware
    // vouches for.
    val availableCpuCount = Runtime.getRuntime().availableProcessors()
    debugUnitTest.forkEvery = (availableCpuCount / 4).coerceIn(1, 12).toLong()
    debugUnitTest.maxHeapSize = "2g"
    composeIdlePoisoningSmokeTestDebugUnitTest.configure {
        testClassesDirs = debugUnitTest.testClassesDirs
        classpath = debugUnitTest.classpath
        jvmArgumentProviders.addAll(debugUnitTest.jvmArgumentProviders)
        systemProperties.putAll(debugUnitTest.systemProperties)
    }
}

tasks.named("check") { dependsOn(composeIdlePoisoningSmokeTestDebugUnitTest) }
tasks.named("build") { dependsOn(composeIdlePoisoningSmokeTestDebugUnitTest) }
