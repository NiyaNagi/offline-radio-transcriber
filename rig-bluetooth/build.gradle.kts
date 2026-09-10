plugins {
    id("ort.android-library")
}

// Empty but wired — technical design §2. Android-only; implementation lands in a later wave.
dependencies {
    implementation(project(":core"))
    implementation(project(":rig"))
}
