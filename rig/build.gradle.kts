plugins {
    id("ort.jvm-library")
    alias(libs.plugins.kotlin.serialization)
}

// :rig — the RigModule contract (FR-RIG-1), the declarative descriptor engine (FR-RIG-4/11), the
// TH-D75A and generic ASCII CAT descriptors (FR-RIG-3/14), the onboarding catalogue
// (FR-RIG-16..19) and the behavioural fakes every consumer's tests need (constitution II). Pure
// JVM, :core only (technical design §2 / ModuleGraph) — the physical USB and Bluetooth
// transports live in :rig-usb and a future Bluetooth module, which depend on this one, never the
// other way around. Descriptors are JSON (technical design §11: "not YAML — no extra parser"),
// parsed with kotlinx.serialization, already in the version catalog for exactly this use.
dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":testing"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
