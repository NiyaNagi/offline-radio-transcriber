plugins {
    id("ort.android-library")
}

// :rig-bluetooth — BluetoothSppTransport (FR-RIG-14/FR-RIG-15), the RFCOMM SPP implementation of
// :rig's RigTransport contract over BluetoothSocket, plus FakeBluetoothLink (constitution II).
// May depend only on :core and :rig (ModuleGraph); never :capture-android (WPB builder rule — the
// backoff *policy* is copied from BackoffLadder, the module is not depended on). Declares no HTTP
// client and no INTERNET permission (platformGuards, constitution V). The test-only dependency on
// :rig-usb below is scoped to `testImplementation`, which ModuleGraph deliberately excludes
// (`:testing` fakes and cross-module test fixtures are meant to be reachable from any module's
// tests) — it exists solely so E2-B04/AC-133's transport-parity test can construct both
// transports from one test class.
dependencies {
    implementation(project(":core"))
    implementation(project(":rig"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(project(":testing"))
    testImplementation(project(":rig"))
    testImplementation(project(":rig-usb"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
