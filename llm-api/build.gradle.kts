plugins {
    id("ort.jvm-library")
}

// :llm-api — the on-device LLM contract (build-plan P21, WPH): LlmEngine/LlmRequest/LlmResult,
// CallsignShapeFilter (FR-DIG-4's post-hoc guard) and FakeLlmEngine. Pure JVM; per ModuleGraph it
// may depend on :core only — :lexicon's real callsign grammar is unreachable from here, which is
// why CallsignShapeFilter implements its own conservative regex (see its own doc comment).
dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
