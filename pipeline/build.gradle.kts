plugins {
    id("ort.android-library")
}

// :pipeline — the shed controller, model residency budget checks, the pass-timeout watchdog
// and re-segmentation over the continuous archive (technical design §4, §7.3, build-plan P8).
// `capture-android` is `api`, not `implementation`: `:app` may not declare a compile edge to
// `:capture-android` (module graph), so the capture-status types this module re-exports need to
// reach `:app`'s compile classpath through this module's own dependency, not a new edge.
dependencies {
    implementation(project(":core"))
    implementation(project(":onnx"))
    implementation(project(":capture-api"))
    api(project(":capture-android"))
    implementation(project(":segment"))
    implementation(project(":asr-api"))
    implementation(project(":asr-sherpa"))
    implementation(project(":lexicon"))
    implementation(project(":identity"))
    implementation(project(":rig"))
    implementation(project(":rig-usb"))
    implementation(project(":rig-bluetooth"))
    implementation(project(":data"))
    // Wave F scaffolding (WP0'): the LLM contract (post-hoc rescoring/digest, FR-ASR-15/16,
    // FR-DIG-3) and its MediaPipe implementation are wired here empty; feature code lands with
    // the wave that owns P20.
    implementation(project(":llm-api"))
    implementation(project(":llm-mediapipe"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)
    // WPH follow-up (P21, FR-DIG-5): ProseDigestRunner's charging+idle-constrained schedule.
    // Not previously a project dependency anywhere (confirmed by search before adding) despite
    // AGENTS.md's Stack line naming WorkManager — this is the first module to actually link it.
    implementation(libs.androidx.work.runtime.ktx)
    // :data's Room types (OrtDatabase, its DAOs) are used directly by this module's real capture
    // wiring (RealCaptureService, a v0 smoke test — see its own doc comment) — :data itself only
    // has `implementation` on Room, so it is not on this module's main classpath transitively,
    // same reason the pre-existing testImplementation entries below exist.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)

    testImplementation(project(":testing"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    // WPH follow-up: TestListenableWorkerBuilder for ProseDigestRunnerTest.
    testImplementation(libs.androidx.work.testing)
    // WPH (build-plan P21): ProsePromptBuilderTest's FR_DIG_12 closed-field-list check reflects
    // over ThreadDigestInput's declared properties, the same reason :segment's own build.gradle.kts
    // already carries this for its AC-94 constructor-signature check.
    testImplementation(kotlin("reflect"))
    // :data's Room types (OrtDatabase, its DAOs) are used directly in this module's tests
    // (PassDrainRunnerTest, GapPersisterTest) — :data itself only has `implementation` on Room,
    // so it is not on this module's classpath transitively without a matching test dependency.
    testImplementation(libs.room.runtime)
    testImplementation(libs.room.ktx)
    // R-204 (see :data/build.gradle.kts's identical comment): this module's own Robolectric
    // tests (PassDrainRunnerTest, GapPersisterTest) open a real OrtDatabase, whose driver-mode
    // native library AGP otherwise resolves to the Android-variant artifact even on a unit-test
    // (host-JVM) classpath.
    testImplementation(libs.androidx.sqlite.bundled.jvm)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // ort.android-library brings JUnit4 for Robolectric's @RunWith; the vintage engine lets
    // those classes run on the same JUnit Platform test task.
    testRuntimeOnly(libs.junit.vintage.engine)
}
