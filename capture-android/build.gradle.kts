plugins {
    id("ort.android-library")
}

// :capture-android — AudioRecordSource, route verification, the FLAC-shaped lossless store,
// continuous archive, CaptureService (foreground + heartbeat + OEM guidance + "prove it"),
// technical design §5-6, build-plan P8. May depend only on :core and :capture-api (module graph)
// — never :data, :segment, :asr-*, :lexicon or :identity.
dependencies {
    implementation(project(":core"))
    implementation(project(":capture-api"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)

    testImplementation(project(":testing"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.test.core)
    // Most of this module's tests are pure logic against fakes and use JUnit5 directly (as
    // :core and :capture-api do); a handful need Robolectric, which still runs on JUnit4 — the
    // vintage engine lets those classes run on the same JUnit Platform test task.
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit.vintage.engine)
}
