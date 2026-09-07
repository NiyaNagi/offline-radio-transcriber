plugins {
    id("ort.android-library")
}

// Empty but wired — technical design §2. Android-only; implementation lands in a later wave.
dependencies {
    implementation(project(":core"))
    implementation(project(":onnx"))
    implementation(project(":capture-api"))
    implementation(project(":capture-android"))
    implementation(project(":segment"))
    implementation(project(":asr-api"))
    implementation(project(":asr-sherpa"))
    implementation(project(":lexicon"))
    implementation(project(":identity"))
    implementation(project(":rig"))
    implementation(project(":rig-usb"))
    implementation(project(":data"))
}
