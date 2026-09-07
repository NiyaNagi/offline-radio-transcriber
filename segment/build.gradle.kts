plugins {
    id("ort.jvm-library")
}

// :segment — VAD interface + wrapper and the IDLE→SPEECH→HANGOVER→CLOSED segmenter
// (technical design §6). Pure JVM. Per technical design §2 it may depend on :core and :onnx
// only; the ring buffer and capture source live one layer out, so the segmenter is fed frames.
dependencies {
    implementation(project(":core"))
    implementation(project(":onnx"))

    testImplementation(project(":testing"))
    testImplementation(project(":capture-api"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("reflect")) // AC-94's constructor-signature check
}
