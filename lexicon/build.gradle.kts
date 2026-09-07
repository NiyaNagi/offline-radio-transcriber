plugins {
    id("ort.jvm-library")
}

// :lexicon is PURE — Kotlin only, no Android, depends only on :core (constitution VII;
// technical design §2). Bundled lexicon assets live in src/main/resources as versioned TSV.
dependencies {
    implementation(project(":core"))
}
