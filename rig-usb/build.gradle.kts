plugins {
    id("ort.android-library")
}

// :rig-usb — UsbSerialTransport (FR-RIG-3), the CDC-ACM implementation of :rig's RigTransport
// contract over usb-serial-for-android, plus FakeUsbSerialPort (constitution II). May depend
// only on :core and :rig (ModuleGraph); never :capture-android (WPB builder rule — the backoff
// *policy* is copied from BackoffLadder, the module is not depended on). Declares no HTTP client
// and no INTERNET permission (platformGuards, constitution V).
dependencies {
    implementation(project(":core"))
    implementation(project(":rig"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.usb.serial.android) {
        // usb-serial-for-android 3.11.0 pulls androidx.annotation 1.10.0, which ships a
        // multiplatform artifact declaring a kotlin-stdlib 2.1.x dependency purely for its
        // Kotlin-metadata annotations — the library itself is still pure Java (confirmed against
        // its build.gradle at that tag). Left unexcluded, Gradle's highest-version-wins
        // resolution raises the whole project's kotlin-stdlib past what this build's Kotlin
        // 2.0.21 compiler can read, and every module fails with an unrelated-looking "compiled
        // with an incompatible version of Kotlin" error. Excluding it keeps the project on its
        // pinned 2.0.21 stdlib; usb-serial-for-android calls no Kotlin stdlib API at all.
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }

    testImplementation(project(":testing"))
    testImplementation(project(":rig"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
