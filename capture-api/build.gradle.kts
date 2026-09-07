plugins {
    id("ort.jvm-library")
}

// Empty but wired — technical design §2. Implementation arrives in a later build-plan wave.
dependencies {
    implementation(project(":core"))
}
