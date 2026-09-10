plugins {
    id("ort.android-library")
}

// Empty but wired — technical design §2. Android-only; implementation lands in a later wave.
dependencies {
    implementation(project(":core"))
    implementation(project(":llm-api"))

    // WP0' (Wave F scaffolding): pinned to the newest version published at fetch time — see
    // gradle/libs.versions.toml's mediapipeTasksGenai comment for where it was read. Resolution
    // confirmed by this module's own dependencies task; if platformGuards ever fails on this
    // coordinate, the fix is to keep the dependency commented out and report it, never to
    // weaken the guard (constitution V, VII).
    implementation(libs.mediapipe.tasks.genai)
}
