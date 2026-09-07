plugins {
    id("ort.jvm-library")
}

// :testing holds the behavioural fakes, fixtures and deterministic seeds every module above
// depends on (test-plan §4). It is JVM-only and depends on :core alone.
dependencies {
    api(project(":core"))
    api(libs.junit.jupiter)
    api(libs.kotlinx.coroutines.test)
}
