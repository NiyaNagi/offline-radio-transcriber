plugins {
    id("ort.android-library")
}

// :llm-mediapipe — MediaPipeLlmEngine (build-plan P21, WPH), the :llm-api contract over
// `com.google.mediapipe:tasks-genai`'s LlmInference. T3-only load (FR-AST-3a, AC-138): loading
// happens lazily on the first LlmEngine.load() call, never at construction.
dependencies {
    implementation(project(":core"))
    implementation(project(":llm-api"))
    implementation(libs.kotlinx.coroutines.core)

    // WP0' (Wave F scaffolding): pinned to the newest version published at fetch time — see
    // gradle/libs.versions.toml's mediapipeTasksGenai comment for where it was read. Resolution
    // confirmed by this module's own dependencies task; if platformGuards ever fails on this
    // coordinate, the fix is to keep the dependency commented out and report it, never to
    // weaken the guard (constitution V, VII).
    implementation(libs.mediapipe.tasks.genai)

    testImplementation(project(":testing"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.test.core)
    // Most modules' tests are pure logic against fakes and use JUnit5 directly; the one
    // Robolectric test here (a missing model path yields Failed without a crash, E2-I06's
    // non-hardware half) still runs on JUnit4 — the vintage engine lets that class run on the
    // same JUnit Platform test task, matching capture-android/pipeline's own convention.
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit.vintage.engine)
}
