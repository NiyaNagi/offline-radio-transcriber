plugins {
    id("ort.jvm-library")
}

// :asr-api — AsrEngine/StreamingAsrEngine/Enhancer interfaces, the six hallucination-control
// RejectionRules, the model registry and transcript versioning (technical design §8). Pure JVM;
// per ModuleGraph it may depend on :core and :onnx only.
dependencies {
    implementation(project(":core"))
    implementation(project(":onnx"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(project(":testing"))
    testImplementation(libs.kotlinx.coroutines.test)
}
