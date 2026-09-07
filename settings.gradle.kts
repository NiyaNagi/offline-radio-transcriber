pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx's JVM/JNI bindings are published only as GitHub Release assets, mirrored
        // to JitPack — not to Maven Central (see CHANGELOG.md, P10 follow-up). Scoped narrowly:
        // only :asr-sherpa consumes anything from here.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "offline-radio-transcriber"

// Every module in technical design §2. JVM modules first, then the Android-only six.
include(
    ":core",
    ":onnx",
    ":capture-api",
    ":segment",
    ":asr-api",
    ":asr-sherpa",
    ":lexicon",
    ":identity",
    ":rig",
    ":eval",
    ":testing",
    // Android-only
    ":capture-android",
    ":rig-usb",
    ":data",
    ":net",
    ":pipeline",
    ":app",
)
