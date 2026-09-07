plugins {
    id("ort.android-app")
}

// Empty but wired — technical design §2. The Compose UI, navigation, onboarding and Hilt
// graph land across build-plan P8 and P11; for now this is the shell that proves the app
// assembles and starts at minSdk 26 (AC-93 / NFR-5).
dependencies {
    implementation(project(":core"))
    implementation(project(":pipeline"))
    implementation(project(":data"))
    implementation(project(":net"))
}
