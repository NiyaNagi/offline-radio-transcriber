plugins {
    id("ort.jvm-library")
}

// :onnx — ONNX/sherpa-onnx runtime loading, session lifecycle, model residency (technical
// design §2, §4.3). Pure JVM; per ModuleGraph it may depend on :core only at compile time.
dependencies {
    implementation(project(":core"))

    testImplementation(project(":testing"))
}
