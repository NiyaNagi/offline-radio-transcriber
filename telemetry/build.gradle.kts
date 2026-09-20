plugins {
    id("ort.android-library")
    alias(libs.plugins.kotlin.serialization)
}

// :telemetry — P28 (D42, FR-ANL-1..14): the analytics channel's event schema, provenance
// envelope, tier gating and on-device queue. Depends on :core only per ModuleGraph — in
// particular NOT :data, so an analytics payload can never be built by serialising a Room entity
// (FR-ANL-6); it is always recomputed from the closed, per-tier field lists declared here. Also
// NOT :net (only :net may link an HTTP client, constitution V) and never :capture-* in either
// direction (constitution IV, VII — capture must never block on or feed analytics directly; see
// buildSrc's ModuleGraph for the enforced edge). Android-only for SharedPreferences-backed tier
// toggles and the file-backed queue (technical design §2's `:telemetry [AND]`); the event schema
// and gating logic themselves are plain JVM and unit-testable without Robolectric.
dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)

    testImplementation(project(":testing"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.test.core)
    // ort.android-library brings JUnit4 for Robolectric's @RunWith; the vintage engine lets
    // those classes run on the JUnit Platform ort.common's Test tasks are configured for.
    testRuntimeOnly(libs.junit.vintage.engine)
}
