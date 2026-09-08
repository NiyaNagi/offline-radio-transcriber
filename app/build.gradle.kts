import com.android.build.gradle.internal.dsl.BaseAppModuleExtension

plugins {
    id("ort.android-app")
}

extensions.configure<BaseAppModuleExtension> {
    testOptions { unitTests.isIncludeAndroidResources = true }

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
