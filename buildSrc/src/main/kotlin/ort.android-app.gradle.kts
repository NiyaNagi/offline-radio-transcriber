import com.android.build.gradle.internal.dsl.BaseAppModuleExtension

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
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
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

// P13: `testBuildType` above only redirects the `test`/`check` task *aliases* — `build`'s own
// dependency graph still wires up `testReleaseUnitTest` regardless, and that variant's merged
// manifest never carries `ui-test-manifest` (debug-only, correctly — see the dependency comment
// below), so it fails on every Compose UI test for a reason that has nothing to do with the code
// under test. The release build type differs from debug only by `isMinifyEnabled`, so testing it
// separately proves nothing `testDebugUnitTest` does not already prove; disable it outright.
tasks.matching { it.name == "testReleaseUnitTest" }.configureEach { enabled = false }

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
