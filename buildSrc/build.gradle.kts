plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("com.android.tools.build:gradle:8.7.2")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
    // P13: the Compose compiler is its own Gradle plugin from Kotlin 2.0 on, pinned to the exact
    // Kotlin version above — it must be on buildSrc's own classpath for the precompiled
    // `ort.android-app` convention plugin to apply it.
    implementation("org.jetbrains.kotlin.plugin.compose:org.jetbrains.kotlin.plugin.compose.gradle.plugin:2.0.21")
    implementation("org.jlleitschuh.gradle:ktlint-gradle:12.1.1")
    implementation("io.gitlab.arturbosch.detekt:detekt-gradle-plugin:1.23.7")
    // R-1001 (WPJ, FR-ASR-8): FetchSherpaNativeTask extracts sherpa-onnx's own published Android
    // release archive, `sherpa-onnx-v<version>-android.tar.bz2` — a plain tar compressed with
    // bzip2, which the JDK has no built-in codec for (java.util.zip covers gzip/deflate only) and
    // which the platform `tar` binary cannot decompress on this dev machine either (Windows'
    // bundled bsdtar shells out to an external `bzip2` executable that is not present here —
    // confirmed directly: `tar -xjf ... ` fails with "unable to run program \"bzip2 -d\""). Apache
    // Commons Compress is a plain, dependency-free (no native code) Java library that reads both
    // formats directly, so extraction is deterministic on every platform the gate runs on
    // (Windows dev machine, Linux CI/Release runner) without depending on what happens to be
    // installed on the host.
    implementation("org.apache.commons:commons-compress:1.27.1")
    // Debug-fix session (2026-09-19, operator report: "App not installed. Package appears to be
    // invalid."): SigningStabilityGuardTask reads the real assembled APK's signer certificate the
    // same way `apksigner` itself does — this IS the library `apksigner`/AGP's own signing pipeline
    // are built from, so it needs no external SDK tool on PATH and no hand-rolled APK Signing Block
    // v2/v3 parser (register: see this task's own KDoc for the full defect account).
    implementation("com.android.tools.build:apksig:8.7.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
