plugins {
    id("ort.jvm-library")
}

// :capture-api — CaptureSource, the deterministic resampler, the lock-free ring buffer and the
// file-backed WAV source (technical design §5). Pure JVM: no Android APIs (build-plan P4).
dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(project(":testing"))
    testImplementation(libs.kotlinx.coroutines.test)
}
