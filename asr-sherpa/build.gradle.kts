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

    testImplementation(project(":testing"))
    testImplementation(libs.kotlinx.coroutines.test)
    // R-1001 (register — the halt this build report closes): this Windows-x64 native jar is a
    // *desktop-JVM test* dependency only — the one real production consumer of `RealSherpaDecoder`
    // is Android (`:pipeline`'s `AsrEngineProvisioning.kt`), which needs sherpa-onnx's own Android
    // `.so` files (fetched by `buildSrc`'s `FetchSherpaNativeTask` into `app/src/main/jniLibs/`,
    // never this coordinate) — and the *only* other consumer is this module's own gated
    // `RealSherpaDecoderRealModelTest`/`RealSileroVadRealModelTest` (`ORT_RUN_REAL_SHERPA=1`,
    // never ordinary CI). Previously declared `runtimeOnly` (a *main*-configuration dependency),
    // which meant `:pipeline` -> `:app` transitively inherited it onto the Android app's own
    // runtime/packaging classpath — confirmed against the built APK: 22 MB of
    // `sherpa-onnx/native/win-x64/{onnxruntime,sherpa-onnx-jni}.dll` at the APK root as inert Java
    // resources, on every install, on every device, useless on all of them. `testRuntimeOnly` is
    // the *correct* scope, not merely a workaround: it still resolves onto `:asr-sherpa:test`'s own
    // runtime classpath (this module's real-model tests still run exactly as before — confirmed by
    // `./gradlew :asr-sherpa:dependencies --configuration testRuntimeClasspath` still resolving it,
    // and by CHANGELOG's own verification run), but a `testRuntimeOnly` dependency is never part of
    // a module's `runtimeElements`/`runtimeClasspath` *variant*, so it is structurally absent from
    // everything that depends on `:asr-sherpa` as a library (`:pipeline`, `:app`) — no packaging
    // exclusion band-aid needed on the Android side for this jar at all (see `app/build.gradle.kts`
    // for the *separate*, narrower `sherpa-onnx/native/**` resource exclusion kept there as a
    // second line of defense against any future re-introduction of a runtime-scoped native
    // coordinate, documented in that file).
    testRuntimeOnly(libs.sherpa.onnx.native.win.x64)
}
