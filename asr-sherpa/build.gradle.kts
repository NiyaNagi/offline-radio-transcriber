plugins {
    id("ort.jvm-library")
}

// :asr-sherpa — AsrEngine over sherpa-onnx (technical design §8.1), plus the side-loaded model
// activation path (FR-ASR-8, FR-AST-2). Pure JVM; per ModuleGraph it may depend on :core,
// :onnx and :asr-api only. See SherpaAsrEngine's doc comment for what is real vs. a seam here.
dependencies {
    implementation(project(":core"))
    implementation(project(":onnx"))
    implementation(project(":asr-api"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.sherpa.onnx.jvm)
    // Platform-specific native binding: this is a JVM build on Windows x64, so only that
    // platform's native jar is pulled. A different host platform would need its own coordinate.
    runtimeOnly(libs.sherpa.onnx.native.win.x64)

    testImplementation(project(":testing"))
    testImplementation(libs.kotlinx.coroutines.test)
}
