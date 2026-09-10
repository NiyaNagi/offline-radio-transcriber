plugins {
    id("ort.jvm-library")
}

// Empty but wired — technical design §2. The evaluation harness (build-plan P7) composes the
// pure JVM pipeline: :capture-api (WAV) + :segment + :asr-sherpa + :lexicon + :identity.
dependencies {
    implementation(project(":core"))
    implementation(project(":onnx"))
    implementation(project(":capture-api"))
    implementation(project(":segment"))
    implementation(project(":asr-api"))
    implementation(project(":asr-sherpa"))
    implementation(project(":lexicon"))
    implementation(project(":identity"))
    implementation(project(":rig"))
    // Wave F scaffolding (WP0'): the eval harness will need the LLM contract to measure
    // FR-ASR-15/16 rescoring and FR-DIG-3's digest quality; wired empty ahead of that wave.
    implementation(project(":llm-api"))
    implementation(project(":testing"))
}
