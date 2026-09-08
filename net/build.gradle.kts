plugins {
    id("ort.android-library")
}

// :net — the only module permitted an HTTP client (technical design §16.1, constitution V).
// Model acquisition (build-plan P18): fetch, verify, resume, side-load, entirely behind
// HttpRangeClient so no test here (or in a caller) ever makes a real network call. Depends on
// :core only per ModuleGraph. See README.md for why java.net.HttpURLConnection was preferred
// over adding OkHttp.
dependencies {
    implementation(project(":core"))

    testImplementation(project(":testing"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
